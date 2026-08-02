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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Runs untrusted code (FR-10, NFR-S03/S04, NFR-R04, FMEA-02/08).
 *
 * Every externally-triggered process — the COMPILER as well as the submission —
 * runs under hard limits. Sandboxing only the run step was a real hole: a source
 * file of {@code #include </dev/urandom>} makes the preprocessor read an endless
 * stream and exhaust host RAM before any program is even produced.
 *
 * Two enforcement paths:
 *  - Linux: scripts/sandbox-run.sh — unshare -Urn (no network) + prlimit +
 *    timeout -k, with real peak RSS from /usr/bin/time.
 *  - Anywhere else (dev machines): {@code sh -c 'ulimit …; exec …'}, which still
 *    caps CPU, address space, process count and file size, plus an RSS sampler
 *    so MLE is a real verdict instead of a mislabelled RE.
 *
 * Whatever the path, three things always hold:
 *  1. the whole process TREE is killed, not just the direct child — otherwise a
 *     fork bomb's children survive and peg the CPU;
 *  2. every live process is tracked and killed on JVM shutdown, because Java
 *     does NOT reap child processes when it exits;
 *  3. captured output is capped, so a program printing forever cannot exhaust
 *     memory or disk (surfaced as OLE).
 */
@Component
public class SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutor.class);

    private static final boolean IS_LINUX =
            System.getProperty("os.name").toLowerCase().contains("linux");

    private static final ExecutorService STREAM_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sandbox-stream");
        t.setDaemon(true);
        return t;
    });

    /** Captured output cap; hitting it means OLE, not a host memory problem. */
    public static final int OUTPUT_CAP = 1 << 20;                            // 1 MiB
    private static final long COMPILE_ADDRESS_SPACE_KB = 2L * 1024 * 1024;   // 2 GiB
    private static final long MAX_FILE_SIZE_BLOCKS = 65536;                  // 64 MiB (512B blocks)

    /**
     * How many processes a submission may add beyond what the account already
     * has. Normal code needs one; the headroom covers a language runtime that
     * spawns helpers and the other judge threads running concurrently, while
     * still stopping a fork bomb after a few dozen instead of thousands.
     *
     * Too small is not "safer": the cap is per-UID, so a tight value makes
     * legitimate submissions fail intermittently as the machine's own process
     * count drifts.
     */
    private static final int MAX_EXTRA_PROCESSES = 32;

    /**
     * Every process started here and not yet reaped. The shutdown hook empties
     * it — without that, stopping the server leaves compiled submissions
     * ("prog") running forever at full CPU.
     */
    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> LIVE.forEach(SandboxExecutor::destroyTree), "sandbox-reaper"));
    }

    private final JudgeProperties props;

    public SandboxExecutor(JudgeProperties props) {
        this.props = props;
    }

    /**
     * Kills any submission binary left over from a previous run and clears the
     * work root. A server that was killed mid-judge leaves those processes
     * alive — they are not the JVM's children any more, so nothing else will
     * ever reap them, and they accumulate across restarts.
     */
    @PostConstruct
    void sweepStaleProcesses() {
        Path root = Path.of(props.getWorkRoot());
        killByWorkDir(root);
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
            log.info("Cleared stale judge work root {}", root);
        } catch (IOException ignored) {
            // root does not exist yet on a first run — nothing to sweep
        }
    }

    /** Fresh work dir per submission; caller must cleanup() it (NFR-S03). */
    public Path createWorkDir(long submissionId) throws IOException {
        Path root = Path.of(props.getWorkRoot());
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "sub-" + submissionId + "-");
    }

    /**
     * Compile step — also limited. The compiler processes attacker-supplied
     * input and can be driven to unbounded CPU, memory and disk use.
     */
    public ExecutionResult compile(Path workDir, List<String> command)
            throws IOException, InterruptedException {
        long timeoutMs = props.getCompileTimeoutMs();
        return runLimited(workDir, command, null, timeoutMs,
                timeoutMs / 1000 + 5, COMPILE_ADDRESS_SPACE_KB, false);
    }

    /** Run one test case under the problem's limits. */
    public ExecutionResult run(Path workDir, List<String> command, Path inputFile,
                               int timeLimitMs, int memoryLimitKb)
            throws IOException, InterruptedException {

        Path script = Path.of(props.getSandboxScript()).toAbsolutePath();
        if (IS_LINUX && Files.isExecutable(script)) {
            return runSandboxed(script, workDir, command, inputFile, timeLimitMs, memoryLimitKb);
        }
        // Dev fallback: still limited, and NFR-R04's 2x kill is preserved.
        return runLimited(workDir, command, inputFile, timeLimitMs * 2L,
                timeLimitMs / 1000 + 1, memoryLimitKb * 2L, true);
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
                String.valueOf(timeLimitMs), String.valueOf(memoryLimitKb),
                workDir.toString(), inputFile.toString(),
                outFile.toString(), errFile.toString(), metrics.toString(), "--"));
        cmd.addAll(command);

        Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).start();
        LIVE.add(p);
        boolean timedOutWrapper = false;
        try {
            if (!p.waitFor(timeLimitMs * 3L + 10_000, TimeUnit.MILLISECONDS)) {
                timedOutWrapper = true;
            }
        } finally {
            destroyTree(p);
            LIVE.remove(p);
        }
        if (timedOutWrapper) {
            return new ExecutionResult(124, "", "sandbox wrapper hung",
                    timeLimitMs * 3L, -1, true);
        }

        Properties m = new Properties();
        if (Files.exists(metrics)) {
            try (var in = Files.newInputStream(metrics)) {
                m.load(in);
            }
        }
        int exit = Integer.parseInt(m.getProperty("exit", "1"));
        long timeMs = Long.parseLong(m.getProperty("time_ms", "-1"));
        long peakKb = Long.parseLong(m.getProperty("peak_kb", "-1"));
        boolean timedOut = "1".equals(m.getProperty("timed_out", "0")) || exit == 124;

        return new ExecutionResult(exit, readCapped(outFile), readCapped(errFile),
                timeMs, peakKb, timedOut);
    }

    /**
     * Non-Linux path, but still bounded. ulimit is applied in a shell wrapper so
     * the limits are in force before the untrusted program is exec'd:
     * -t CPU seconds, -f file size, -u processes, -v address space.
     */
    private ExecutionResult runLimited(Path workDir, List<String> command, Path inputFile,
                                       long wallTimeoutMs, long cpuSeconds,
                                       long addressSpaceKb, boolean runPhase)
            throws IOException, InterruptedException {

        // Process cap, applied to the RUN phase only.
        //
        // On macOS/BSD RLIMIT_NPROC counts every process owned by the user, not
        // descendants of this shell, so an absolute value like 64 makes even the
        // compiler die with "posix_spawn failed: Resource temporarily
        // unavailable". The cap must therefore be RELATIVE to what the account
        // already has: a submitted program legitimately needs one process, so
        // current + 8 leaves normal code untouched while a fork bomb starts
        // failing after 8 children instead of reaching thousands.
        //
        // This is the real containment. destroyTree() alone cannot do it —
        // ProcessHandle.descendants() is a snapshot, and a bomb forks faster
        // than that snapshot can be walked, so children escape and survive.
        // The compile phase gets no -u at all: compilers spawn helper processes.
        String nprocLimit = "";
        if (runPhase) {
            int base = userProcessCount();
            if (base > 0) {
                nprocLimit = "ulimit -u %d 2>/dev/null; ".formatted(base + MAX_EXTRA_PROCESSES);
            }
        }
        // -v is unsupported on some shells, hence 2>/dev/null on each.
        String limited = (nprocLimit + "ulimit -t %d 2>/dev/null; ulimit -f %d 2>/dev/null; "
                + "ulimit -v %d 2>/dev/null; exec %s").formatted(
                Math.max(1, cpuSeconds), MAX_FILE_SIZE_BLOCKS,
                Math.max(65536, addressSpaceKb), shellQuote(command));

        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", limited)
                .directory(workDir.toFile());
        if (inputFile != null) {
            pb.redirectInput(inputFile.toFile());
        }

        long start = System.nanoTime();
        Process p = pb.start();
        LIVE.add(p);

        CompletableFuture<String> out = readAsync(p.getInputStream());
        CompletableFuture<String> err = readAsync(p.getErrorStream());
        AtomicLong peakKb = runPhase ? samplePeakRss(p) : new AtomicLong(-1);

        try {
            boolean finished = p.waitFor(wallTimeoutMs, TimeUnit.MILLISECONDS);
            long wallMs = (System.nanoTime() - start) / 1_000_000;
            if (!finished) {
                destroyTree(p);
                return new ExecutionResult(124, safeJoin(out), safeJoin(err),
                        wallMs, peakKb.get(), true);
            }
            return new ExecutionResult(p.exitValue(), safeJoin(out), safeJoin(err),
                    wallMs, peakKb.get(), false);
        } finally {
            destroyTree(p);
            LIVE.remove(p);
        }
    }

    /**
     * Polls RSS so MLE is detectable off Linux. Without it peakMemoryKb stays
     * -1, the MLE branch never fires, and a memory hog is reported as RE.
     */
    private static AtomicLong samplePeakRss(Process p) {
        AtomicLong peak = new AtomicLong(-1);
        Thread sampler = new Thread(() -> {
            String pid = String.valueOf(p.pid());
            while (p.isAlive()) {
                try {
                    Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", pid)
                            .redirectErrorStream(true).start();
                    String line = new String(ps.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8).trim();
                    ps.waitFor(1, TimeUnit.SECONDS);
                    if (!line.isEmpty()) {
                        long rss = Long.parseLong(line.split("\\s+")[0]);
                        peak.updateAndGet(prev -> Math.max(prev, rss));
                    }
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    return;                 // process gone, or ps unavailable
                }
            }
        }, "rss-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return peak;
    }

    /**
     * Kills the process and every descendant. Killing only the direct child
     * leaves a fork bomb's children running — exactly how orphaned "prog"
     * processes end up pegging the CPU after the server stops.
     */
    private static void destroyTree(Process p) {
        if (p == null) {
            return;
        }
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) {
            // descendants() can race with exit; the direct kill below still runs
        }
        p.destroyForcibly();
        try {
            p.waitFor(5, TimeUnit.SECONDS);      // reap; don't leave a zombie
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Kill anything still running when the Spring context closes. */
    @PreDestroy
    void shutdown() {
        if (!LIVE.isEmpty()) {
            log.warn("Killing {} judge process(es) still running at shutdown", LIVE.size());
        }
        LIVE.forEach(SandboxExecutor::destroyTree);
        LIVE.clear();
    }

    /**
     * Processes currently owned by this user. RLIMIT_NPROC is per-UID on
     * macOS/BSD, so the run-phase cap has to be expressed relative to this.
     * Returns -1 when it cannot be determined, in which case no cap is applied
     * rather than risking a limit that blocks legitimate submissions.
     */
    private static int userProcessCount() {
        try {
            Process ps = new ProcessBuilder("/bin/sh", "-c", "ps -u \"$(id -u)\" | wc -l")
                    .redirectErrorStream(true).start();
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!ps.waitFor(3, TimeUnit.SECONDS)) {
                ps.destroyForcibly();
                return -1;
            }
            return Integer.parseInt(out.split("\\s+")[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Kills anything still executing out of a work directory, matched by path.
     *
     * destroyTree() walks a snapshot of descendants, which a rapidly forking
     * program outruns; those survivors keep the binary's path in their command
     * line, so a pattern kill catches exactly the strays it missed and nothing
     * belonging to another submission.
     */
    private static void killByWorkDir(Path workDir) {
        try {
            new ProcessBuilder("pkill", "-9", "-f", workDir.toString())
                    .redirectErrorStream(true).start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // pkill absent (non-POSIX host); the limits above are the real guard
        }
    }

    private static String shellQuote(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (String arg : command) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append('\'').append(arg.replace("'", "'\\''")).append('\'');
        }
        return sb.toString();
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readNBytes(OUTPUT_CAP), StandardCharsets.UTF_8);
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

    /** Kills stragglers from this submission, then deletes its work dir. */
    public void cleanup(Path workDir) {
        if (workDir == null) {
            return;
        }
        // Must happen BEFORE the delete: a still-running binary would otherwise
        // keep executing from a path that no longer exists, which is exactly how
        // orphaned "prog" processes end up pegging the CPU with nothing on disk
        // to trace them back to.
        killByWorkDir(workDir);

        try (var walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temp dir; the OS reclaims it eventually
                }
            });
        } catch (IOException ignored) {
        }
    }
}
