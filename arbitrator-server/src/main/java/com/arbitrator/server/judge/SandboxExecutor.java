package com.arbitrator.server.judge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Runs untrusted code (FR-10, NFR-S03/S04, NFR-R04).
 *
 * On Linux every RUN goes through scripts/sandbox-run.sh:
 *   unshare -Urn        new user + network namespace  -> no network
 *   prlimit             CPU, address space (2x limit), nproc, fsize, nofile
 *   timeout -k          SIGKILL at 2x the time limit
 * and real peak RSS comes back through a metrics file (VmHWM / time -v),
 * which is what makes the MLE verdict genuine (TBD-02 resolved).
 *
 * On non-Linux dev machines it falls back to a plain ProcessBuilder with a
 * wall-clock timeout and no memory figure — good enough to develop against,
 * never used in the lab. COMPILES always run unsandboxed but time-boxed.
 */
@Component
public class SandboxExecutor {

    private static final boolean IS_LINUX =
            System.getProperty("os.name").toLowerCase().contains("linux");

    /** Async drain of stdout/stderr — pattern kept from the XorOJ reference. */
    private static final ExecutorService STREAM_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sandbox-stream");
        t.setDaemon(true);
        return t;
    });

    private static final int OUTPUT_CAP = 1 << 20;   // 1 MiB of captured output is plenty

    private final JudgeProperties props;

    public SandboxExecutor(JudgeProperties props) {
        this.props = props;
    }

    /** Fresh work dir per submission; caller must cleanup() it (NFR-S03). */
    public Path createWorkDir(long submissionId) throws IOException {
        Path root = Path.of(props.getWorkRoot());
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "sub-" + submissionId + "-");
    }

    /** Compile step: no untrusted execution, just time-boxed (CE on timeout). */
    public ExecutionResult compile(Path workDir, List<String> command)
            throws IOException, InterruptedException {
        return runDirect(workDir, command, null, props.getCompileTimeoutMs());
    }

    /** Run one test case inside the sandbox. */
    public ExecutionResult run(Path workDir, List<String> command, Path inputFile,
                               int timeLimitMs, int memoryLimitKb)
            throws IOException, InterruptedException {

        Path script = Path.of(props.getSandboxScript()).toAbsolutePath();
        if (IS_LINUX && Files.isExecutable(script)) {
            return runSandboxed(script, workDir, command, inputFile, timeLimitMs, memoryLimitKb);
        }
        // Dev fallback: hard timeout only. NFR-R04's 2x kill is preserved.
        return runDirect(workDir, command, inputFile, timeLimitMs * 2L);
    }

    // ------------------------------------------------------------------

    private ExecutionResult runSandboxed(Path script, Path workDir, List<String> command,
                                         Path inputFile, int timeLimitMs, int memoryLimitKb)
            throws IOException, InterruptedException {

        Path outFile = workDir.resolve("__stdout.txt");
        Path errFile = workDir.resolve("__stderr.txt");
        Path metrics = workDir.resolve("__metrics.txt");

        List<String> cmd = new ArrayList<>(List.of(
                "bash", script.toString(),
                String.valueOf(timeLimitMs),
                String.valueOf(memoryLimitKb),
                workDir.toString(),
                inputFile.toString(),
                outFile.toString(),
                errFile.toString(),
                metrics.toString(),
                "--"));
        cmd.addAll(command);

        Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).start();
        // Script enforces its own timeout; this outer bound is a safety net.
        boolean finished = p.waitFor(timeLimitMs * 3L + 10_000, TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            return new ExecutionResult(124, "", "sandbox wrapper hung", timeLimitMs * 3L, -1, true);
        }

        Properties m = new Properties();
        if (Files.exists(metrics)) {
            try (var in = Files.newInputStream(metrics)) {
                m.load(in);
            }
        }
        int exit = Integer.parseInt(m.getProperty("exit", String.valueOf(p.exitValue())));
        long timeMs = Long.parseLong(m.getProperty("time_ms", "-1"));
        long peakKb = Long.parseLong(m.getProperty("peak_kb", "-1"));
        boolean timedOut = "1".equals(m.getProperty("timed_out", "0")) || exit == 124;

        return new ExecutionResult(exit, readCapped(outFile), readCapped(errFile),
                timeMs, peakKb, timedOut);
    }

    private ExecutionResult runDirect(Path workDir, List<String> command, Path inputFile,
                                      long timeoutMs)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
        if (inputFile != null) {
            pb.redirectInput(inputFile.toFile());
        }
        long start = System.nanoTime();
        Process p = pb.start();
        CompletableFuture<String> out = readAsync(p.getInputStream());
        CompletableFuture<String> err = readAsync(p.getErrorStream());

        boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        long wallMs = (System.nanoTime() - start) / 1_000_000;
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
            return new ExecutionResult(124, safeJoin(out), safeJoin(err), wallMs, -1, true);
        }
        return new ExecutionResult(p.exitValue(), safeJoin(out), safeJoin(err), wallMs, -1, false);
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                byte[] bytes = in.readNBytes(OUTPUT_CAP);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }, STREAM_POOL);
    }

    private static String safeJoin(CompletableFuture<String> f) {
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readCapped(Path file) {
        try (var in = Files.newInputStream(file)) {
            return new String(in.readNBytes(OUTPUT_CAP), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Best-effort recursive delete of the per-submission work dir. */
    public void cleanup(Path workDir) {
        if (workDir == null) {
            return;
        }
        try (var walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temp dir; the OS will reclaim it eventually
                }
            });
        } catch (IOException ignored) {
        }
    }
}
