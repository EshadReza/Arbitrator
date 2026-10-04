/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.CustomRunRequest;
import com.arbitrator.common.dto.CustomRunResultDto;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.service.ContestAccessService;

/**
 * Runs a student's code against input they typed themselves.
 *
 * Nothing is persisted: no submission row, no verdict, no effect on standings
 * or penalty. It exists so a contestant can debug without burning an attempt,
 * which is what every real judge offers.
 *
 * It goes through the same {@link SandboxExecutor} as real judging, so the
 * identical limits apply — this must never become a way to run unbounded code.
 */
@Service
public class CustomRunService {

    /** Typed input is small; anything larger is a misuse of the feature. */
    private static final int MAX_INPUT_CHARS = 64 * 1024;

    private final ProblemRepository problems;
    private final SandboxExecutor sandbox;
    private final JudgeProperties props;
    private final ContestAccessService contestAccess;
    private final Semaphore runSlots;
    private final Set<String> activeUsers = ConcurrentHashMap.newKeySet();

    public CustomRunService(ProblemRepository problems, SandboxExecutor sandbox,
                            JudgeProperties props, ContestAccessService contestAccess) {
        this.problems = problems;
        this.sandbox = sandbox;
        this.props = props;
        this.contestAccess = contestAccess;
        this.runSlots = new Semaphore(Math.max(1, props.getMaxCustomRuns()), true);
    }

    public CustomRunResultDto run(String username, CustomRunRequest req) {
        if (req.sourceCode() == null || req.sourceCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty source code");
        }
        if (req.language() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Language is required");
        }
        int sourceCap = props.getMaxSourceBytes();
        if (req.sourceCode().length() > sourceCap
                || req.sourceCode().getBytes(StandardCharsets.UTF_8).length > sourceCap) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Source code is limited to " + sourceCap + " UTF-8 bytes");
        }
        String input = req.input() == null ? "" : req.input();
        if (input.length() > MAX_INPUT_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Custom input is limited to " + (MAX_INPUT_CHARS / 1024) + " KB");
        }

        Problem problem = problems.findById(req.problemId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));
        contestAccess.requireReleasedAccess(problem.getContestId(), username);

        JudgeProperties.LanguageSpec spec =
                props.getLanguages().get(req.language().configKey());
        if (spec == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to complete this request");
        }

        if (!activeUsers.add(username)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You already have a custom run in progress");
        }
        if (!runSlots.tryAcquire()) {
            activeUsers.remove(username);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "The custom-run service is busy; try again shortly");
        }

        Path workDir = null;
        try {
            workDir = sandbox.createWorkDir(-System.nanoTime());   // negative: not a submission
            Path src = workDir.resolve(spec.getSourceFile());
            Path exe = workDir.resolve("prog");
            SafeSandboxFiles.writeString(src, req.sourceCode());

            if (spec.getCompile() != null && !spec.getCompile().isBlank()) {
                ExecutionResult c = sandbox.compile(workDir,
                        LanguageCommandPolicy.render(spec.getCompile(), src, exe, workDir));
                if (!c.ok()) {
                    String msg = c.stderr().isBlank() ? c.stdout() : c.stderr();
                    return new CustomRunResultDto(false,
                            truncate(stripPaths(msg, workDir)),
                            "", "", -1, false);
                }
            }

            Path inputFile = workDir.resolve("__input.txt");
            SafeSandboxFiles.writeString(inputFile, input);

            ExecutionResult r = sandbox.run(workDir,
                    LanguageCommandPolicy.render(spec.getRun(), src, exe, workDir),
                    inputFile, problem.getTimeLimitMs(), problem.getMemoryLimitKb());

            String stderr = r.stderr();
            if (r.outputLimitExceeded()) {
                stderr = stderr + (stderr.isBlank() ? "" : "\n") + "Output limit exceeded.";
            }
            return new CustomRunResultDto(true, "", truncate(r.stdout()), truncate(stderr),
                    Math.max(r.wallTimeMs(), 0), r.timedOut());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to complete this request");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to complete this request");
        } finally {
            sandbox.cleanup(workDir);
            runSlots.release();
            activeUsers.remove(username);
        }
    }

    /**
     * Compiler messages carry the absolute sandbox path, which is noise to a
     * student and needlessly exposes server layout. Reduce it to the bare
     * source file name so errors read like a local build.
     */
    static String stripPaths(String message, Path workDir) {
        if (message == null) {
            return "";
        }
        return message
                .replace(workDir.toString() + "/", "")
                .replace(workDir.toString(), "");
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 8192 ? s : s.substring(0, 8192) + "\n… truncated";
    }
}
