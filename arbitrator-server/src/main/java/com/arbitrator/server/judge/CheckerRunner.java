/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.arbitrator.common.enums.CheckerType;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Problem;

/**
 * Manages compilation and execution of custom C++ checker programs (FR-14).
 *
 * Checkers are instructor-provided binaries following the testlib convention:
 * {@code checker <input_file> <contestant_output_file> <expected_output_file>}.
 *
 * Checkers run under their own resource limits (configured in {@link JudgeProperties}).
 * Checker failure modes (crash, timeout, memory/output limit, non-zero unmapped exit code) produce
 * an {@code RE} verdict and are logged loudly server-side. They must not be reported as contestant
 * MLE/OLE because the instructor-provided checker, rather than the student's program, failed.
 */
@Component
public class CheckerRunner {

    private static final Logger log = LoggerFactory.getLogger(CheckerRunner.class);

    private final SandboxExecutor sandbox;
    private final JudgeProperties props;
    private final ConcurrentHashMap<Long, CompiledChecker> cache = new ConcurrentHashMap<>();

    private record CompiledChecker(String sourceHash, Path binaryPath) {}

    public CheckerRunner(SandboxExecutor sandbox, JudgeProperties props) {
        this.sandbox = sandbox;
        this.props = props;
    }

    /**
     * Evaluates a contestant's output against the expected output using the problem's custom checker.
     *
     * @param problem the problem entity carrying the checker source
     * @param inputFile path to the test case input file
     * @param contestantOutputFile path to the contestant's captured output
     * @param expectedOutputFile path to the test case expected output
     * @return {@link Verdict#AC}, {@link Verdict#WA}, or {@link Verdict#RE} if the checker fails
     */
    public Verdict check(Problem problem, Path inputFile, Path contestantOutputFile, Path expectedOutputFile)
            throws IOException, InterruptedException {
        if (problem.getCheckerType() != CheckerType.CUSTOM || problem.getCheckerSource() == null
                || problem.getCheckerSource().isBlank()) {
            log.warn("CheckerRunner invoked for problem {} but checkerType is not CUSTOM or source is missing",
                    problem.getId());
            return Verdict.RE;
        }

        Path binaryPath;
        try {
            binaryPath = getOrCompileChecker(problem.getId(), problem.getCheckerSource());
        } catch (CheckerCompilationException e) {
            log.error("Checker compilation failed for problem {}", problem.getId());
            return Verdict.RE;
        }

        // Stage only the four files this invocation needs in a fresh private
        // directory. SandboxExecutor mounts this directory alone, so neither
        // the cached checker source/binary nor any sibling submission becomes
        // visible inside the container.
        Path parent = contestantOutputFile.toAbsolutePath().getParent();
        Path workDir = Files.createTempDirectory(parent, "__checker-");
        try {
            SandboxExecutor.permitContainerAccess(workDir);
            Path stagedBinary = copyExecutable(binaryPath, workDir.resolve("checker_bin"));
            Path stagedInput = SafeSandboxFiles.copy(inputFile, workDir.resolve("input.txt"));
            Path stagedActual = SafeSandboxFiles.copy(contestantOutputFile, workDir.resolve("actual.txt"));
            Path stagedExpected = SafeSandboxFiles.copy(expectedOutputFile, workDir.resolve("expected.txt"));

            List<String> cmd = List.of(
                    stagedBinary.toString(), stagedInput.toString(),
                    stagedActual.toString(), stagedExpected.toString());
            int timeLimitMs = (int) props.getCheckerTimeLimitMs();
            int memoryLimitKb = (int) props.getCheckerMemoryLimitKb();
            ExecutionResult r = sandbox.run(workDir, cmd, stagedInput, timeLimitMs, memoryLimitKb);

            if (r.timedOut()) {
                log.error("Checker execution timed out after {} ms for problem {}", timeLimitMs, problem.getId());
                return Verdict.RE;
            }
            if (r.peakMemoryKb() > 0 && r.peakMemoryKb() > memoryLimitKb) {
                log.error("Checker for problem {} exceeded its memory limit: {} KiB used, {} KiB allowed",
                        problem.getId(), r.peakMemoryKb(), memoryLimitKb);
                return Verdict.RE;
            }
            if (r.outputLimitExceeded()) {
                log.error("Checker for problem {} exceeded the sandbox output limit", problem.getId());
                return Verdict.RE;
            }

            int exitCode = r.exitCode();
            if (exitCode == 0) {
                return Verdict.AC;
            } else if (exitCode == 1 || exitCode == 2) {
                return Verdict.WA;
            } else {
                log.error("Checker for problem {} failed with unmapped exit code {}", problem.getId(), exitCode);
                return Verdict.RE;
            }
        } catch (Exception e) {
            log.error("Checker execution failed for problem {} ({})", problem.getId(), e.getClass().getSimpleName());
            return Verdict.RE;
        } finally {
            sandbox.cleanup(workDir);
        }
    }

    /**
     * Validates that a checker source string compiles cleanly during package import.
     *
     * @param source C++ source code of the checker
     * @throws CheckerCompilationException if compilation fails
     */
    public void validateChecker(String source) throws CheckerCompilationException {
        Path tempDir = null;
        try {
            Path workRoot = Path.of(props.getWorkRoot());
            Path root = workRoot.resolve("checkers");
            Files.createDirectories(root);
            SandboxExecutor.restrictToOwner(workRoot);
            SandboxExecutor.restrictToOwner(root);
            tempDir = Files.createTempDirectory(root, "checker-val-");
            SandboxExecutor.permitContainerAccess(tempDir);
            Path src = tempDir.resolve("checker.cpp");
            Path exe = tempDir.resolve("checker_bin");
            SafeSandboxFiles.writeString(src, source);

            List<String> compileCmd = List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString());
            ExecutionResult c = sandbox.compile(tempDir, compileCmd);
            if (!c.ok()) {
                String err = c.stderr().isBlank() ? c.stdout() : c.stderr();
                throw new CheckerCompilationException(err);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CheckerCompilationException("Compilation exception: " + e.getMessage());
        } finally {
            if (tempDir != null) {
                sandbox.cleanup(tempDir);
            }
        }
    }

    private synchronized Path getOrCompileChecker(Long problemId, String source)
            throws CheckerCompilationException {
        String sourceHash = hashSource(source);
        CompiledChecker existing = (problemId != null) ? cache.get(problemId) : null;
        if (existing != null && existing.sourceHash().equals(sourceHash) && Files.exists(existing.binaryPath())) {
            return existing.binaryPath();
        }

        Path workRoot = Path.of(props.getWorkRoot());
        Path root = workRoot.resolve("checkers");
        try {
            Files.createDirectories(root);
            SandboxExecutor.restrictToOwner(workRoot);
            SandboxExecutor.restrictToOwner(root);
            Path problemDir = (problemId != null)
                    ? root.resolve("problem-" + problemId)
                    : Files.createTempDirectory(root, "checker-compile-");
            Files.createDirectories(problemDir);
            SandboxExecutor.permitContainerAccess(problemDir);

            Path src = problemDir.resolve("checker.cpp");
            Path exe = problemDir.resolve("checker_bin");
            SafeSandboxFiles.writeString(src, source);

            List<String> compileCmd = List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString());
            ExecutionResult c = sandbox.compile(problemDir, compileCmd);
            if (!c.ok()) {
                String err = c.stderr().isBlank() ? c.stdout() : c.stderr();
                throw new CheckerCompilationException(err);
            }

            CompiledChecker compiled = new CompiledChecker(sourceHash, exe);
            if (problemId != null) {
                cache.put(problemId, compiled);
            }
            return exe;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CheckerCompilationException("Compilation exception: " + e.getMessage());
        }
    }

    private static String hashSource(String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(source.hashCode());
        }
    }

    private static Path copyExecutable(Path source, Path target) throws IOException {
        SafeSandboxFiles.copy(source, target);
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // Docker Desktop preserves executable files copied from its Linux VM.
            target.toFile().setExecutable(true, false);
        }
        return target;
    }
}
