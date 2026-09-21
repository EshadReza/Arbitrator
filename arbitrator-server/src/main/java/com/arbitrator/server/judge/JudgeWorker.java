/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.arbitrator.common.dto.VerdictEventDto;
import com.arbitrator.common.enums.CheckerType;
import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.TestCase;
import com.arbitrator.server.leaderboard.LeaderboardBroadcaster;
import com.arbitrator.server.realtime.VerdictPublisher;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.repo.UserRepository;

/**
 * UC-16: dequeue -> compile -> execute per test (ascending, fail-fast BR-05)
 * -> verdict -> persist -> push (FR-11, FR-15). One instance, called from the
 * queue's thread pool, so everything here must be thread-safe (it is: no
 * mutable state, each job owns its work dir).
 */
@Component
public class JudgeWorker {

    private static final Logger log = LoggerFactory.getLogger(JudgeWorker.class);

    private static final int COMPILER_OUTPUT_LIMIT = 4096;   // FR-20

    /** Per-test cap on the contestant output kept for the test-case view. */
    private static final int STORED_OUTPUT_LIMIT = 4096;

    private final SubmissionRepository submissions;
    private final ProblemRepository problems;
    private final TestCaseRepository testCases;
    private final UserRepository users;
    private final SandboxExecutor sandbox;
    private final VerdictEvaluator evaluator;
    private final CheckerRunner checkerRunner;
    private final VerdictPublisher publisher;
    private final LeaderboardBroadcaster leaderboardBroadcaster;
    private final JudgeProperties props;
    private final JdbcTemplate jdbc;

    public JudgeWorker(SubmissionRepository submissions,
                       ProblemRepository problems,
                       TestCaseRepository testCases,
                       UserRepository users,
                       SandboxExecutor sandbox,
                       VerdictEvaluator evaluator,
                       CheckerRunner checkerRunner,
                       VerdictPublisher publisher,
                       LeaderboardBroadcaster leaderboardBroadcaster,
                       JudgeProperties props,
                       JdbcTemplate jdbc) {
        this.submissions = submissions;
        this.problems = problems;
        this.testCases = testCases;
        this.users = users;
        this.sandbox = sandbox;
        this.evaluator = evaluator;
        this.checkerRunner = checkerRunner;
        this.publisher = publisher;
        this.leaderboardBroadcaster = leaderboardBroadcaster;
        this.props = props;
        this.jdbc = jdbc;
    }

    public void judge(long submissionId) {
        Submission sub = submissions.findById(submissionId).orElse(null);
        if (sub == null) {
            log.warn("Submission {} vanished before judging", submissionId);
            return;
        }
        sub.setStatus(Submission.Status.JUDGING);
        submissions.save(sub);

        Path workDir = null;
        try {
            workDir = sandbox.createWorkDir(submissionId);
            Outcome outcome = evaluate(sub, workDir);
            record(sub, outcome);
        } catch (Exception e) {
            log.error("Judging {} failed internally", submissionId, e);
            record(sub, Outcome.internalError(e.getMessage()));
        } finally {
            sandbox.cleanup(workDir);
        }
    }

    // ------------------------------------------------------------------

    private Outcome evaluate(Submission sub, Path workDir)
            throws IOException, InterruptedException {

        JudgeProperties.LanguageSpec spec =
                props.getLanguages().get(sub.getLanguage().configKey());
        if (spec == null) {
            // Most likely cause: languages.yml isn't wired into
            // spring.config.import in application.yml, so this map is empty.
            log.error("No LanguageSpec bound for '{}' — check languages.yml is "
                    + "imported via spring.config.import in application.yml",
                    sub.getLanguage().configKey());
            return Outcome.internalError("Language not configured: " + sub.getLanguage());
        }

        String banned = forbiddenApiMessage(sub.getLanguage(), sub.getSourceCode());
        if (banned != null) {
            return Outcome.ce(banned);
        }

        Path src = workDir.resolve(spec.getSourceFile());
        Path exe = workDir.resolve("prog");
        SafeSandboxFiles.writeString(src, sub.getSourceCode());

        // --- compile (skipped for interpreted languages) ---
        if (spec.getCompile() != null && !spec.getCompile().isBlank()) {
            ExecutionResult c = sandbox.compile(workDir,
                    LanguageCommandPolicy.render(spec.getCompile(), src, exe, workDir));
            if (!c.ok()) {
                String raw = c.stderr().isBlank() ? c.stdout() : c.stderr();
                // Students see a clean "main.cpp:4:5: error", not the sandbox path.
                String msg = truncate(
                        CustomRunService.stripPaths(raw, workDir));
                return Outcome.ce(msg);
            }
        }

        Problem problem = problems.findById(sub.getProblemId()).orElseThrow();
        List<TestCase> tests = testCases.findByProblemIdOrderByIdxAsc(problem.getId());
        List<String> runCmd = LanguageCommandPolicy.render(spec.getRun(), src, exe, workDir);

        long maxTime = 0;
        long maxMem = -1;

        int testIndex = 0;
        for (TestCase tc : tests) {
            testIndex++;
            Path input = workDir.resolve("__input.txt");
            SafeSandboxFiles.writeString(input, tc.getInputData());

            ExecutionResult r = sandbox.run(workDir, runCmd, input,
                    problem.getTimeLimitMs(), problem.getMemoryLimitKb());

            maxTime = Math.max(maxTime, Math.max(r.wallTimeMs(), 0));
            maxMem = Math.max(maxMem, r.peakMemoryKb());

            Verdict v;
            if (r.timedOut() || r.wallTimeMs() > problem.getTimeLimitMs()) {
                v = Verdict.TLE;
            } else if (r.peakMemoryKb() > 0 && r.peakMemoryKb() > problem.getMemoryLimitKb()) {
                v = Verdict.MLE;
            } else if (r.outputLimitExceeded()) {
                v = Verdict.OLE;
            } else if (r.exitCode() != 0) {
                v = Verdict.RE;
            } else if (problem.getCheckerType() == CheckerType.CUSTOM) {
                Path contestantOut = workDir.resolve("__actual_output_" + tc.getIdx() + ".txt");
                Path expectedOut = workDir.resolve("__expected_output_" + tc.getIdx() + ".txt");
                try {
                    SafeSandboxFiles.writeString(contestantOut, r.stdout());
                    SafeSandboxFiles.writeString(expectedOut, tc.getExpectedOutput());
                    v = checkerRunner.check(problem, input, contestantOut, expectedOut);
                } finally {
                    // Hidden expected output must not survive until the next
                    // contestant execution in this reused submission workspace.
                    Files.deleteIfExists(expectedOut);
                    Files.deleteIfExists(contestantOut);
                }
            } else if (evaluator.matches(tc.getExpectedOutput(), r.stdout())) {
                v = Verdict.AC;
            } else {
                v = Verdict.WA;
            }

            saveTestResult(sub.getId(), tc.getIdx(), v, r);

            if (v != Verdict.AC) {
                return new Outcome(v, maxTime, maxMem, null, testIndex);   // fail-fast BR-05
            }
        }
        return new Outcome(Verdict.AC, maxTime, maxMem, null, -1);
    }

    private void record(Submission sub, Outcome o) {
        sub.setStatus(Submission.Status.DONE);
        sub.setVerdict(o.verdict);
        sub.setExecTimeMs(o.execTimeMs);
        sub.setPeakMemoryKb(o.peakMemoryKb);
        sub.setCompilerOutput(o.compilerOutput);
        sub.setFailedTestIndex(o.failedTestIndex);
        sub.setJudgedAt(Instant.now());
        submissions.save(sub);

        String username = users.findById(sub.getUserId())
                .map(u -> u.getUsername()).orElse(null);
        if (username != null) {
            publisher.publishVerdict(username, new VerdictEventDto(
                    sub.getId(), sub.getProblemId(), o.verdict,
                    o.execTimeMs, o.peakMemoryKb, o.compilerOutput, o.failedTestIndex));
        }

        // NFR-P03: standings within 5 s of the verdict, not at the next 30 s tick.
        leaderboardBroadcaster.broadcastNow();
    }

    private void saveTestResult(long submissionId, int testIndex, Verdict v, ExecutionResult r) {
        jdbc.update("""
                INSERT INTO submission_results
                    (submission_id, test_index, verdict, exec_time_ms, peak_memory_kb, actual_output)
                VALUES (?, ?, ?, ?, ?, ?)
                """, submissionId, testIndex, v.name(), r.wallTimeMs(), r.peakMemoryKb(),
                clipOutput(r.stdout()));
    }

    /**
     * What the program printed, kept only in the quantity a person would read.
     * The sandbox already caps capture at 1 MiB; storing that per test, per
     * submission, would grow the table faster than the submissions themselves.
     */
    private static String clipOutput(String out) {
        if (out == null) {
            return null;
        }
        return out.length() <= STORED_OUTPUT_LIMIT ? out : out.substring(0, STORED_OUTPUT_LIMIT);
    }

    /**
     * Threading and process-spawning APIs are disallowed for every
     * submission, on every language — a plain source-text scan run before
     * anything is written to disk or compiled, so a violation costs nothing
     * (no container, no compiler) and reports as a normal CE. Sandboxing
     * already contains the *blast radius* of these calls (no network,
     * --pids-limit, read-only rootfs, the container is thrown away either
     * way), but they're still banned outright: they're not something a
     * contest solution legitimately needs, and disallowing them removes an
     * entire class of "did the checker actually see what I think it saw"
     * questions (a background thread finishing after the main one returns,
     * a shelled-out command reading something it shouldn't).
     */
    private record Ban(Pattern pattern, String description) { }

    private static final List<Ban> CPP_BANS = List.of(
            new Ban(Pattern.compile("#\\s*include\\s*<thread>"), "threading (<thread>)"),
            new Ban(Pattern.compile("\\bstd::thread\\b|\\bstd::jthread\\b"), "threading (std::thread)"),
            new Ban(Pattern.compile("#\\s*include\\s*<future>"), "threading (<future> / std::async)"),
            new Ban(Pattern.compile("\\bstd::async\\b"), "threading (std::async)"),
            new Ban(Pattern.compile("#\\s*include\\s*<omp\\.h>|\\b_Pragma\\s*\\(\\s*\"omp"), "threading (OpenMP)"),
            new Ban(Pattern.compile("\\bsystem\\s*\\("), "process execution (system())"),
            new Ban(Pattern.compile("\\bpopen\\s*\\("), "process execution (popen())"),
            new Ban(Pattern.compile("\\bfork\\s*\\("), "process spawning (fork())"),
            new Ban(Pattern.compile("\\bexec[lv]p?e?\\s*\\("), "process execution (the exec family)"));

    private static final List<Ban> JAVA_BANS = List.of(
            new Ban(Pattern.compile("\\bnew\\s+Thread\\b"), "threading (Thread)"),
            new Ban(Pattern.compile("\\bextends\\s+Thread\\b"), "threading (Thread subclass)"),
            new Ban(Pattern.compile("\\bExecutors\\."), "threading (Executors)"),
            new Ban(Pattern.compile("\\bExecutorService\\b"), "threading (ExecutorService)"),
            new Ban(Pattern.compile("\\bCompletableFuture\\b"), "threading (CompletableFuture)"),
            new Ban(Pattern.compile("\\bForkJoinPool\\b"), "threading (ForkJoinPool)"),
            new Ban(Pattern.compile("\\bnew\\s+Timer\\s*\\("), "threading (java.util.Timer)"),
            new Ban(Pattern.compile("\\bRuntime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\b"),
                    "process execution (Runtime.exec)"),
            new Ban(Pattern.compile("\\bProcessBuilder\\b"), "process execution (ProcessBuilder)"));

    private static final List<Ban> PY_BANS = List.of(
            new Ban(Pattern.compile("(?m)^\\s*import\\s+threading\\b"), "threading (threading module)"),
            new Ban(Pattern.compile("(?m)^\\s*from\\s+threading\\s+import\\b"), "threading (threading module)"),
            new Ban(Pattern.compile("(?m)^\\s*import\\s+multiprocessing\\b"), "threading (multiprocessing module)"),
            new Ban(Pattern.compile("(?m)^\\s*from\\s+multiprocessing\\s+import\\b"),
                    "threading (multiprocessing module)"),
            new Ban(Pattern.compile("(?m)^\\s*import\\s+concurrent\\.futures\\b"), "threading (concurrent.futures)"),
            new Ban(Pattern.compile("(?m)^\\s*from\\s+concurrent\\.futures\\s+import\\b"),
                    "threading (concurrent.futures)"),
            new Ban(Pattern.compile("\\bos\\.system\\s*\\("), "process execution (os.system())"),
            new Ban(Pattern.compile("\\bos\\.popen\\s*\\("), "process execution (os.popen())"),
            new Ban(Pattern.compile("\\bos\\.exec[lv]p?e?\\s*\\("), "process execution (the os.exec family)"),
            new Ban(Pattern.compile("\\bos\\.fork\\s*\\("), "process spawning (os.fork())"),
            new Ban(Pattern.compile("(?m)^\\s*import\\s+subprocess\\b"), "process execution (subprocess module)"),
            new Ban(Pattern.compile("(?m)^\\s*from\\s+subprocess\\s+import\\b"),
                    "process execution (subprocess module)"));

    private static final Map<Language, List<Ban>> BANS_BY_LANGUAGE = Map.of(
            Language.CPP17, CPP_BANS,
            Language.JAVA17, JAVA_BANS,
            Language.PYTHON310, PY_BANS);

    private static String forbiddenApiMessage(Language language, String source) {
        for (Ban ban : BANS_BY_LANGUAGE.getOrDefault(language, List.of())) {
            if (ban.pattern().matcher(source).find()) {
                return "Not allowed in submissions: " + ban.description() + ". "
                        + "Threading and process-spawning APIs are disabled on this judge.";
            }
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= COMPILER_OUTPUT_LIMIT ? s : s.substring(0, COMPILER_OUTPUT_LIMIT);
    }

    private record Outcome(Verdict verdict, long execTimeMs, long peakMemoryKb,
                           String compilerOutput, int failedTestIndex) {

        static Outcome ce(String compilerOutput) {
            return new Outcome(Verdict.CE, -1, -1, compilerOutput, -1);
        }

        /**
         * Checker/infrastructure failure maps to RE per §6.1 glossary.
         *
         * The message rides along in compilerOutput because that is the only
         * free-text channel to the client. Dropping it made a judge-side
         * misconfiguration — an unbound languages.yml above, say — arrive as a
         * bare RE indistinguishable from the student's own crash, with nothing
         * on screen or in the stored record to say otherwise.
         */
        static Outcome internalError(String message) {
            return new Outcome(Verdict.RE, -1, -1,
                    message == null ? null : truncate("Judge error: " + message), -1);
        }
    }
}
