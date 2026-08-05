package com.arbitrator.server.judge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.arbitrator.common.dto.VerdictEventDto;
import com.arbitrator.common.enums.CheckerType;
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

        Path src = workDir.resolve(spec.getSourceFile());
        Path exe = workDir.resolve("prog");
        Files.writeString(src, sub.getSourceCode(), StandardCharsets.UTF_8);

        // --- compile (skipped for interpreted languages) ---
        if (spec.getCompile() != null && !spec.getCompile().isBlank()) {
            ExecutionResult c = sandbox.compile(workDir, render(spec.getCompile(), src, exe, workDir));
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
        List<String> runCmd = render(spec.getRun(), src, exe, workDir);

        long maxTime = 0;
        long maxMem = -1;

        for (TestCase tc : tests) {
            Path input = workDir.resolve("__input.txt");
            Files.writeString(input, tc.getInputData(), StandardCharsets.UTF_8);

            ExecutionResult r = sandbox.run(workDir, runCmd, input,
                    problem.getTimeLimitMs(), problem.getMemoryLimitKb());

            maxTime = Math.max(maxTime, Math.max(r.wallTimeMs(), 0));
            maxMem = Math.max(maxMem, r.peakMemoryKb());

            Verdict v;
            if (r.timedOut() || r.wallTimeMs() > problem.getTimeLimitMs()) {
                v = Verdict.TLE;
            } else if (r.peakMemoryKb() > 0 && r.peakMemoryKb() > problem.getMemoryLimitKb()) {
                v = Verdict.MLE;
            } else if (r.stdout().length() >= SandboxExecutor.OUTPUT_CAP) {
                // A program printing without bound would otherwise be judged on
                // truncated output and look like a plain WA.
                v = Verdict.OLE;
            } else if (r.exitCode() != 0) {
                v = Verdict.RE;
            } else if (problem.getCheckerType() == CheckerType.CUSTOM) {
                Path contestantOut = workDir.resolve("__actual_output_" + tc.getIdx() + ".txt");
                Path expectedOut = workDir.resolve("__expected_output_" + tc.getIdx() + ".txt");
                Files.writeString(contestantOut, r.stdout(), StandardCharsets.UTF_8);
                Files.writeString(expectedOut, tc.getExpectedOutput(), StandardCharsets.UTF_8);
                v = checkerRunner.check(problem, input, contestantOut, expectedOut);
            } else if (evaluator.matches(tc.getExpectedOutput(), r.stdout())) {
                v = Verdict.AC;
            } else {
                v = Verdict.WA;
            }

            saveTestResult(sub.getId(), tc.getIdx(), v, r);

            if (v != Verdict.AC) {
                return new Outcome(v, maxTime, maxMem, null, tc.getIdx());   // fail-fast BR-05
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
                    (submission_id, test_index, verdict, exec_time_ms, peak_memory_kb)
                VALUES (?, ?, ?, ?, ?)
                """, submissionId, testIndex, v.name(), r.wallTimeMs(), r.peakMemoryKb());
    }

    /** Fills {src} {exe} {dir} into a whitespace-separated command template. */
    private static List<String> render(String template, Path src, Path exe, Path dir) {
        List<String> out = new ArrayList<>();
        for (String token : template.trim().split("\\s+")) {
            out.add(token
                    .replace("{src}", src.toString())
                    .replace("{exe}", exe.toString())
                    .replace("{dir}", dir.toString()));
        }
        return out;
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

        /** Checker/infrastructure failure maps to RE per §6.1 glossary. */
        static Outcome internalError(String message) {
            return new Outcome(Verdict.RE, -1, -1, null, -1);
        }
    }
}
