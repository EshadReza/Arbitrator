package com.arbitrator.server.judge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    public CustomRunService(ProblemRepository problems, SandboxExecutor sandbox,
                            JudgeProperties props, ContestAccessService contestAccess) {
        this.problems = problems;
        this.sandbox = sandbox;
        this.props = props;
        this.contestAccess = contestAccess;
    }

    public CustomRunResultDto run(String username, CustomRunRequest req) {
        if (req.sourceCode() == null || req.sourceCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty source code");
        }
        if (req.language() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Language is required");
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
                    "Language not configured: " + req.language());
        }

        Path workDir = null;
        try {
            workDir = sandbox.createWorkDir(-System.nanoTime());   // negative: not a submission
            Path src = workDir.resolve(spec.getSourceFile());
            Path exe = workDir.resolve("prog");
            Files.writeString(src, req.sourceCode(), StandardCharsets.UTF_8);

            if (spec.getCompile() != null && !spec.getCompile().isBlank()) {
                ExecutionResult c = sandbox.compile(workDir,
                        render(spec.getCompile(), src, exe, workDir));
                if (!c.ok()) {
                    String msg = c.stderr().isBlank() ? c.stdout() : c.stderr();
                    return new CustomRunResultDto(false,
                            truncate(stripPaths(msg, workDir)),
                            "", "", -1, false);
                }
            }

            Path inputFile = workDir.resolve("__input.txt");
            Files.writeString(inputFile, input, StandardCharsets.UTF_8);

            ExecutionResult r = sandbox.run(workDir, render(spec.getRun(), src, exe, workDir),
                    inputFile, problem.getTimeLimitMs(), problem.getMemoryLimitKb());

            return new CustomRunResultDto(true, "", truncate(r.stdout()), truncate(r.stderr()),
                    Math.max(r.wallTimeMs(), 0), r.timedOut());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Run interrupted");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not run: " + e.getMessage());
        } finally {
            sandbox.cleanup(workDir);
        }
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
