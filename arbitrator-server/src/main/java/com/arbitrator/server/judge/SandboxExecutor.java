/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Runs untrusted code (FR-10, NFR-S03/S04, NFR-R04, FMEA-02/08).
 *
 * Every externally-triggered process — the COMPILER as well as the submission
 * itself, and the custom checker — executes inside a throwaway Docker
 * container (scripts/docker/Dockerfile, built by
 * scripts/docker/build-sandbox-image.sh). Nothing participant-supplied ever
 * runs as a direct child of this JVM, on any platform: there is no
 * host-process fallback here, unlike the previous unshare(Linux)/ulimit(dev)
 * split. Docker Desktop/Engine is required everywhere this server runs.
 *
 * Per container:
 *  - {@code --network none}                 no network at all (NFR-S04)
 *  - {@code --memory}/{@code --memory-swap}  equal, so it's a real cgroup
 *                                            hard RSS cap, not the address-
 *                                            space-only bound the old
 *                                            {@code prlimit --as} gave
 *  - {@code --pids-limit}                    fork-bomb containment (FMEA-08)
 *  - {@code --cap-drop ALL} + no-new-privileges, {@code --read-only} root fs
 *    with a small tmpfs for scratch space
 *  - only the specific call's own work dir is bind-mounted (writable for
 *    compilation, read-only for contestant/checker execution).
 *    The shared judge work root is deliberately never mounted: doing so
 *    would let a submission read concurrent submissions and cached checker
 *    material even if the mount itself were read-only.
 *
 * The command runs wrapped as {@code timeout -k1 <2xTL>s /usr/bin/time -o
 * <rusage> -v <cmd>} *inside* the container — the same technique
 * sandbox-run.sh used host-side: {@code /usr/bin/time} gives the true inner
 * elapsed time (excluding container startup overhead) and peak RSS in one
 * read, and {@code timeout}'s exit code 124 is the one unambiguous signal
 * that OUR deadline (not an OOM kill, which also exits 137) is what ended
 * the process — see {@link #parseContainerResult}.
 */
@Component
public class SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutor.class);

    private static final ExecutorService STREAM_POOL = Executors.newFixedThreadPool(64, r -> {
        Thread t = new Thread(r, "sandbox-stream");
        t.setDaemon(true);
        return t;
    });

    /** Captured output cap; hitting it means OLE, not a host memory problem. */
    public static final int OUTPUT_CAP = 1024 * 1024;                        // 1 MiB per stream

    private static final long COMPILE_MEMORY_KB = 2L * 1024 * 1024;          // 2 GiB
    private static final long MAX_FILE_SIZE_BYTES = 64L * 1024 * 1024;       // 64 MiB
    private static final long MAX_COMPILE_WORKSPACE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_COMPILE_FILES = 4096;

    /**
     * Process cap inside the container. Compile gets more headroom than run:
     * g++ forks cc1plus/as/collect2, javac spawns its own helper threads (not
     * processes, but give it room too); a fork bomb only needs to be stopped
     * well short of exhausting the host, not pinned to the bare minimum a
     * legitimate toolchain uses.
     */
    private static final int COMPILE_PIDS_LIMIT = 200;
    private static final int RUN_PIDS_LIMIT = 64;

    /**
     * The container's own {@code --memory} hard cap is set to this multiple
     * of the problem's stated limit, not the limit itself. A program that
     * merely touches the limit must be OBSERVABLE exceeding it — with the
     * container cap equal to the limit, the cgroup OOM-kills the process at
     * (or fractionally under, per its own accounting) the exact boundary, so
     * peak RSS can never be measured as greater than the limit and a real MLE
     * is misreported as RE instead. Matches the old host-side sandbox-run.sh
     * script's ">1x is already MLE" reasoning — MLE is still decided purely
     * by JudgeWorker comparing peakMemoryKb against the problem's own limit,
     * this only gives the container room to let that comparison see a real
     * number above it before anything gets killed.
     */
    private static final int MEMORY_HEADROOM_MULTIPLIER = 2;

    /** Fixed non-root uid/gid baked into scripts/docker/Dockerfile. */
    private static final String SANDBOX_UID_GID = "10001:10001";

    private static final Pattern PEAK_RSS =
            Pattern.compile("Maximum resident set size \\(kbytes\\):\\s*(\\d+)");
    private static final Pattern ELAPSED =
            Pattern.compile("Elapsed \\(wall clock\\) time.*:\\s*([0-9:.]+)\\s*$", Pattern.MULTILINE);

    /**
     * Container names currently running, so the shutdown hook can kill them —
     * without this, stopping the server leaves judged submissions' containers
     * running (Docker does not tie a container's life to the client that
     * started it).
     */
    private static final Set<String> LIVE = ConcurrentHashMap.newKeySet();
    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * Mirrors the resolved docker binary for the shutdown hook and other
     * static contexts that run without a {@link SandboxExecutor} reference.
     * Set once from the constructor; every Spring context has exactly one
     * {@link SandboxExecutor}, so there is no multi-value race.
     */
    private static volatile String dockerBinaryStatic = "docker";

    /**
     * Absolute paths tried, in order, when the configured binary is still the
     * unmodified default ({@code "docker"}) and a bare PATH lookup fails.
     * GUI/IDE-launched processes (Eclipse's "Run As", a desktop .desktop
     * launcher, double-clicking a jar) very often inherit a minimal PATH —
     * {@code /usr/bin:/bin} on some Linux desktop environments, or nothing
     * useful at all — even though "docker" resolves fine from an interactive
     * terminal on the very same machine. Checking these candidates removes
     * the need for a machine-specific {@code docker-binary} override on every
     * platform this server is ever deployed to (macOS, Ubuntu, Windows).
     */
    private static final List<String> DOCKER_CANDIDATES = List.of(
            "/usr/bin/docker",                                                  // Linux (apt/dnf, Docker Engine)
            "/usr/local/bin/docker",                                            // macOS Homebrew; some Linux installs
            "/snap/bin/docker",                                                 // Ubuntu snap package
            "/opt/homebrew/bin/docker",                                         // macOS Homebrew, Apple Silicon
            "/Applications/Docker.app/Contents/Resources/bin/docker",           // macOS Docker Desktop
            "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe",    // Windows Docker Desktop
            "C:\\ProgramData\\DockerDesktop\\version-bin\\docker.exe");

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> LIVE.forEach(SandboxExecutor::killContainerQuietly), "sandbox-reaper"));
    }

    private final JudgeProperties props;

    /** Resolved once at startup — see {@link #resolveDockerBinary}. Used for every docker invocation. */
    private final String dockerBinary;
    /** Immutable image ID after startup verification; tag only before Spring invokes {@link #verifyDockerReady()}. */
    private volatile String sandboxImageReference;
    private final Semaphore containerSlots;
    private final Semaphore memorySlots;
    private final int memoryBudgetMb;

    public SandboxExecutor(JudgeProperties props) {
        this.props = props;
        this.dockerBinary = resolveDockerBinary(props.getDockerBinary());
        this.sandboxImageReference = props.getDockerImage();
        dockerBinaryStatic = this.dockerBinary;
        this.containerSlots = new Semaphore(Math.max(1, props.getMaxActiveContainers()), true);
        this.memoryBudgetMb = resolveMemoryBudgetMb(props.getMaxTotalMemoryMb());
        this.memorySlots = new Semaphore(memoryBudgetMb, true);
    }

    /**
     * If the configured binary already runs, use it as-is — this covers both
     * an explicit operator override (e.g. {@code ARBITRATOR_DOCKER_BIN}) and
     * the common case where a bare "docker" already resolves via PATH.
     *
     * If it does NOT run, always fall through to the per-OS candidate list —
     * including when {@code configured} is itself an explicit absolute path,
     * not just the unmodified default. An earlier version of this method
     * trusted an explicit override completely and skipped the fallback
     * search entirely, on the theory that "an operator who pointed at a
     * specific path wants exactly that path." In practice that's backwards:
     * this override almost always comes from a personal, git-ignored
     * application-local.yml (rules.md Rule 6) that one developer set up by
     * copying a path that worked on THEIR machine (see this same file's
     * history — the exact bug this comment is about: a macOS Homebrew path,
     * {@code /usr/local/bin/docker}, hardcoded here and then copied onto a
     * Linux machine where Docker actually lives at {@code /usr/bin/docker}).
     * Nobody is intentionally pointing at a path they know is wrong; trying
     * the standard candidates too can only help, never mask a real problem —
     * verifyDockerReady() still reports the true failure reason if every
     * candidate, including the configured one, comes up empty.
     */
    private static String resolveDockerBinary(String configured) {
        if (runsCleanly(configured, "version")) {
            return configured;
        }
        for (String candidate : DOCKER_CANDIDATES) {
            if (!candidate.equals(configured) && Files.isExecutable(Path.of(candidate))
                    && runsCleanly(candidate, "version")) {
                log.info("Configured docker binary '{}' did not run; found a working one at {} instead",
                        configured, candidate);
                return candidate;
            }
        }
        return configured;
    }

    /**
     * Fails loud, not silent: if Docker isn't reachable or the sandbox image
     * hasn't been built, every submission from here on returns a clear
     * judge-error RE (see JudgeWorker's internalError) instead of quietly
     * running participant code some other way. Also sweeps containers and
     * work-dir leftovers from a server that was killed mid-judge.
     */
    @PostConstruct
    void verifyDockerReady() {
        LanguageCommandPolicy.validateAll(props);
        String failure = runCapture(dockerBinary, "version");
        if (failure != null) {
            log.error("Docker sandbox is NOT ready — every submission will fail (as a judge-error RE) "
                    + "until this is fixed.\n  Tried to run: {} version\n  Result: {}\n{}",
                    dockerBinary, failure, remediationFor(failure));
        } else if (!runsCleanly(dockerBinary, "image", "inspect", props.getDockerImage())) {
            log.error("Sandbox image '{}' not found. Run this platform's image-load/build script before "
                    + "judging any submission.", props.getDockerImage());
        } else {
            String imageId = captureSuccessfulOutput(dockerBinary, "image", "inspect",
                    "--format={{.Id}}", props.getDockerImage());
            if (imageId == null || !imageId.matches("sha256:[0-9a-fA-F]{64}")) {
                throw new IllegalStateException("Could not resolve sandbox image '"
                        + props.getDockerImage() + "' to an immutable Docker image ID");
            }
            sandboxImageReference = imageId;
            log.info("Docker sandbox ready: image {} pinned for this server run as {} via {}",
                    props.getDockerImage(), imageId, dockerBinary);
        }

        Path root = Path.of(props.getWorkRoot());
        try {
            Files.createDirectories(root);
            restrictToOwner(root);
        } catch (IOException ignored) {
            // best effort; createWorkDir() will surface any real problem per-submission
        }
        sweepStaleContainers();
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
            Files.createDirectories(root);
            log.info("Cleared stale judge work root {}", root);
        } catch (IOException ignored) {
            // root does not exist yet on a first run — nothing to sweep
        }
        logAdmissionCapacity();
    }

    private void logAdmissionCapacity() {
        long worstCaseMb = (long) props.getThreads() * 2048; // 1 GiB problem limit, doubled for measurement
        log.info("Sandbox admission: at most {} active containers and {} MiB aggregate reserved memory",
                props.getMaxActiveContainers(), memoryBudgetMb);
        if (worstCaseMb > memoryBudgetMb) {
            log.info("The {} judge workers could request {} MiB at maximum problem limits; memory admission "
                    + "will safely serialize some executions to remain within the {} MiB budget.",
                    props.getThreads(), worstCaseMb, memoryBudgetMb);
        }
    }

    private static int resolveMemoryBudgetMb(int configuredMb) {
        if (configuredMb > 0) {
            return configuredMb;
        }
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                long derived = sunOs.getTotalMemorySize() * 70 / 100 / (1024 * 1024);
                return (int) Math.max(1, Math.min(Integer.MAX_VALUE, derived));
            }
        } catch (Throwable ignored) {
            // Conservative fallback below.
        }
        return 2048;
    }

    /** Every container this process has ever started is named with this prefix. */
    private static final String NAME_PREFIX = "arbitrator-sbx-";

    private void sweepStaleContainers() {
        try {
            Process ps = new ProcessBuilder(dockerBinary, "ps", "-aq",
                    "--filter", "name=" + NAME_PREFIX)
                    .redirectErrorStream(true).start();
            String ids = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            ps.waitFor(10, TimeUnit.SECONDS);
            if (!ids.isBlank()) {
                List<String> cmd = new ArrayList<>(List.of(dockerBinary, "rm", "-f"));
                cmd.addAll(List.of(ids.split("\\s+")));
                new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(15, TimeUnit.SECONDS);
                log.info("Removed {} stale sandbox container(s) from a previous run", ids.split("\\s+").length);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // docker unavailable — already logged loudly above
        }
    }

    /** Fresh work dir per submission; caller must cleanup() it (NFR-S03). */
    public Path createWorkDir(long submissionId) throws IOException {
        Path root = Path.of(props.getWorkRoot());
        Files.createDirectories(root);
        restrictToOwner(root);
        Path dir = Files.createTempDirectory(root, "sub-" + submissionId + "-");
        permitContainerAccess(dir);
        return dir;
    }

    /**
     * Compile step — also containerized. The compiler processes
     * attacker-supplied input and can be driven to unbounded CPU, memory and
     * disk use (e.g. {@code #include </dev/urandom>} feeding the
     * preprocessor), and previously ran with no network isolation at all.
     */
    public ExecutionResult compile(Path workDir, List<String> command)
            throws IOException, InterruptedException {
        ExecutionResult result = runInContainer(workDir, command, null, props.getCompileTimeoutMs(),
                COMPILE_MEMORY_KB, COMPILE_PIDS_LIMIT, false);
        if (result.ok()) {
            String violation = compileWorkspaceViolation(workDir);
            if (violation != null) {
                return new ExecutionResult(1, result.stdout(), violation, result.wallTimeMs(),
                        result.peakMemoryKb(), false, false);
            }
        }
        return result;
    }

    /**
     * Run one test case (or a custom checker) under the problem's limits.
     * The container's own cap is given headroom above memoryLimitKb — see
     * MEMORY_HEADROOM_MULTIPLIER — but MLE is still decided by JudgeWorker
     * comparing the returned peakMemoryKb against the problem's real limit.
     */
    public ExecutionResult run(Path workDir, List<String> command, Path inputFile,
                               int timeLimitMs, int memoryLimitKb)
            throws IOException, InterruptedException {
        return runInContainer(workDir, command, inputFile, timeLimitMs,
                (long) memoryLimitKb * MEMORY_HEADROOM_MULTIPLIER, RUN_PIDS_LIMIT, true);
    }

    // ------------------------------------------------------------------

    /**
     * Below this much free space on the filesystem backing {@code workRoot},
     * refuse to start another container rather than let it run.
     *
     * {@code --tmpfs /tmp:...,size=64m} is the only general-purpose writable
     * area during execution; the submission workspace is mounted read-only.
     * Compilation still needs a writable workspace, so it is protected by
     * the per-file ulimit plus a post-compile total-size and file-count check.
     * This reserve check additionally refuses new work before the host disk
     * reaches the point where Docker, MySQL, or the server could fail.
     */
    private static final long MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024;   // 1 GiB

    private ExecutionResult runInContainer(Path workDir, List<String> command, Path inputFile,
                                           long timeLimitMs, long memoryLimitKb, int pidsLimit,
                                           boolean readOnlyWorkspace)
            throws IOException, InterruptedException {

        int requestedMemoryMb = Math.toIntExact((memoryLimitKb + 1023) / 1024);
        if (requestedMemoryMb > memoryBudgetMb) {
            throw new IOException("Sandbox requires " + requestedMemoryMb
                    + " MiB but the aggregate judge memory budget is " + memoryBudgetMb + " MiB");
        }
        containerSlots.acquire();
        boolean memoryAcquired = false;
        try {
            memorySlots.acquire(requestedMemoryMb);
            memoryAcquired = true;
            return runAdmittedContainer(workDir, command, inputFile, timeLimitMs,
                    memoryLimitKb, pidsLimit, readOnlyWorkspace);
        } finally {
            if (memoryAcquired) {
                memorySlots.release(requestedMemoryMb);
            }
            containerSlots.release();
        }
    }

    private ExecutionResult runAdmittedContainer(Path workDir, List<String> command, Path inputFile,
                                                  long timeLimitMs, long memoryLimitKb, int pidsLimit,
                                                  boolean readOnlyWorkspace)
            throws IOException, InterruptedException {

        long freeBytes = Files.getFileStore(workDir).getUsableSpace();
        if (freeBytes < MIN_FREE_BYTES) {
            throw new IOException("Judge work disk is nearly full (" + (freeBytes / (1024 * 1024))
                    + " MiB free, need at least " + (MIN_FREE_BYTES / (1024 * 1024))
                    + " MiB) — refusing to start another sandbox run rather than risk filling the host disk. "
                    + "Free up space under the judge work root or increase its filesystem.");
        }

        permitContainerAccess(workDir);

        long hardKillS = Math.max(1, (timeLimitMs * 2 + 999) / 1000);   // SIGKILL at 2x, NFR-R04
        long javaWaitMs = hardKillS * 1000 + 15_000;                    // outer safety net; see class javadoc

        long seq = SEQ.incrementAndGet();
        String rusageName = "__rusage_" + seq + ".txt";
        Path rusageFile = workDir.resolve(rusageName);
        Files.createFile(rusageFile);
        permitContainerWrite(rusageFile);
        String name = NAME_PREFIX + workDir.getFileName() + "-" + seq;

        List<String> cmd = new ArrayList<>(List.of(
                dockerBinary, "run", "--rm", "-i",
                "--name", name,
                "--network", "none",
                "--ipc", "none",
                "--memory", memoryLimitKb + "k",
                "--memory-swap", memoryLimitKb + "k",
                "--pids-limit", String.valueOf(pidsLimit),
                "--cpus", "1",
                "--cpu-shares", "256",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=64m",
                "--ulimit", "fsize=" + MAX_FILE_SIZE_BYTES,
                "--ulimit", "nofile=64:64",
                "--ulimit", "core=0:0",
                "--env", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "--env", "HOME=/tmp",
                "--env", "LANG=C.UTF-8",
                "--env", "LC_ALL=C.UTF-8",
                "--env", "LD_PRELOAD=",
                "--env", "LD_LIBRARY_PATH=",
                "--env", "LIBRARY_PATH=",
                "--env", "CPATH=",
                "--env", "CPLUS_INCLUDE_PATH=",
                "--env", "PYTHONHOME=",
                "--env", "PYTHONPATH=",
                "--env", "JAVA_TOOL_OPTIONS=",
                "--env", "_JAVA_OPTIONS=",
                "--env", "JDK_JAVA_OPTIONS=",
                "--env", "ENV=",
                "--env", "BASH_ENV=",
                "-v", workDir + ":/sandbox:" + (readOnlyWorkspace ? "ro" : "rw"),
                "-v", rusageFile + ":/run-metrics:rw",
                "-w", "/sandbox",
                "--user", SANDBOX_UID_GID,
                sandboxImageReference,
                "timeout", "-k", "1", hardKillS + "s",
                "/usr/bin/time", "-o", "/run-metrics", "-v"));
        for (String token : command) {
            cmd.add(translatePath(workDir, token));
        }

        // No real stdin needed for the compile step (and some checker
        // invocations) — `docker run -i` still needs SOME stdin source, or
        // its process just blocks reading from whatever this JVM's own
        // stdin happens to be. "/dev/null" was the fix, but that path
        // doesn't exist on Windows — every compile failed there with
        // "Cannot run program ... \dev\null (The system cannot find the
        // path specified)" (Windows has no single portable null-device
        // path; NUL is a reserved device name, not a real filesystem path,
        // and isn't valid input to java.io.File the same way). Leaving
        // stdin on the default PIPE and closing our end of it immediately
        // after start() gives the same "stdin is at EOF right away" result
        // without depending on any OS-specific path at all.
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile());
        if (inputFile != null) {
            pb.redirectInput(inputFile.toFile());
        }

        long start = System.nanoTime();
        Process p = pb.start();
        if (inputFile == null) {
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
                // best effort; the container's own timeout still bounds worst case
            }
        }
        LIVE.add(name);

        CompletableFuture<CapturedOutput> out = readAsync(p.getInputStream());
        CompletableFuture<CapturedOutput> err = readAsync(p.getErrorStream());
        out.thenAccept(captured -> {
            if (captured.exceeded()) {
                killContainerQuietly(name);
            }
        });
        err.thenAccept(captured -> {
            if (captured.exceeded()) {
                killContainerQuietly(name);
            }
        });

        try {
            boolean finished = p.waitFor(javaWaitMs, TimeUnit.MILLISECONDS);
            long wallMs = (System.nanoTime() - start) / 1_000_000;
            if (!finished) {
                // The inner `timeout` should have already killed it; this is
                // the safety net for a wedged container (e.g. docker daemon
                // itself stalled) — same two-layer shape as before.
                killContainerQuietly(name);
                p.destroyForcibly();
                CapturedOutput stdout = safeJoin(out);
                CapturedOutput stderr = safeJoin(err);
                return new ExecutionResult(124, stdout.text(), stderr.text(), wallMs, -1, true,
                        stdout.exceeded() || stderr.exceeded());
            }

            int exit = p.exitValue();
            CapturedOutput stdout = safeJoin(out);
            CapturedOutput stderr = safeJoin(err);
            boolean outputLimitExceeded = stdout.exceeded() || stderr.exceeded();
            // 125 is docker's own documented code for "the daemon received
            // the request but failed to create/start the container" — but
            // it is NOT the only way docker can fail before anything inside
            // the container ever ran. "permission denied ... docker.sock"
            // (not in the docker group yet) and "cannot connect to the
            // Docker daemon" (daemon not running) both exit 1, the CLI's
            // generic client-side failure code, indistinguishable from a
            // real compile/runtime failure by exit code alone. Left
            // unhandled, that raw docker error text was landing in
            // Outcome.ce() and shown to the student as if it were THEIR
            // compile error — a judge-infrastructure problem reported as
            // their own mistake. The reliable signal instead: /usr/bin/time
            // runs INSIDE the container wrapping the real command, and
            // writes its report even when that command fails (a genuine
            // compile error still produces a rusage file) — the only
            // legitimate case it does NOT is a timeout kill (exit 124,
            // handled on its own above, /usr/bin/time killed mid-write).
            // So: non-zero, not a timeout, and no rusage file at all means
            // docker itself never got a container running, full stop.
            boolean metricsMissing = !Files.exists(rusageFile) || Files.size(rusageFile) == 0;
            if (exit == 125 || (exit != 0 && exit != 124 && metricsMissing && !outputLimitExceeded)) {
                throw new IOException("docker run failed before the sandbox container started: "
                        + stderr.text());
            }
            return parseContainerResult(exit, stdout.text(), stderr.text(), wallMs, rusageFile,
                    outputLimitExceeded);
        } finally {
            LIVE.remove(name);
            Files.deleteIfExists(rusageFile);
        }
    }

    /**
     * Turns the container's raw exit code + {@code /usr/bin/time -v} output
     * into an {@link ExecutionResult}.
     *
     * Exit 124 is the ONE unambiguous "our own {@code timeout} deadline
     * fired" signal (GNU coreutils: 124 only when timeout itself judged the
     * command overran, regardless of whether SIGTERM or the {@code -k}
     * SIGKILL follow-up actually ended it). A cgroup OOM kill also delivers
     * SIGKILL and exits 137 — the SAME raw code {@code timeout} would report
     * after its own SIGKILL escalation — so 137 alone is NOT trustworthy as
     * a timeout signal (verified empirically: a memory-hog fixture here hits
     * exit 137 within milliseconds, nowhere near its wall-clock deadline).
     * MLE detection instead relies entirely on the measured peak RSS versus
     * the problem's limit, exactly as it did before Docker — see
     * JudgeWorker.evaluate(), unchanged.
     */
    private static ExecutionResult parseContainerResult(int exit, String stdout, String stderr,
                                                         long wallMs, Path rusageFile,
                                                         boolean outputLimitExceeded) {
        boolean timedOut = exit == 124;

        long peakKb = -1;
        long elapsedMs = wallMs;
        if (Files.exists(rusageFile)) {
            try {
                String text = Files.readString(rusageFile, StandardCharsets.UTF_8);
                Matcher peak = PEAK_RSS.matcher(text);
                if (peak.find()) {
                    peakKb = Long.parseLong(peak.group(1));
                }
                Matcher elapsed = ELAPSED.matcher(text);
                if (elapsed.find()) {
                    Long parsed = parseElapsed(elapsed.group(1));
                    if (parsed != null) {
                        elapsedMs = parsed;
                    }
                }
            } catch (IOException ignored) {
                // fall back to the Java-measured wall clock bracket below
            }
        }

        return new ExecutionResult(exit, stdout, stderr, elapsedMs, peakKb, timedOut, outputLimitExceeded);
    }

    /** Parses GNU time's {@code H:MM:SS} or {@code M:SS.ss} elapsed format into milliseconds. */
    private static Long parseElapsed(String raw) {
        try {
            String[] parts = raw.split(":");
            double secs;
            if (parts.length == 3) {
                secs = Long.parseLong(parts[0]) * 3600.0 + Long.parseLong(parts[1]) * 60.0
                        + Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                secs = Long.parseLong(parts[0]) * 60.0 + Double.parseDouble(parts[1]);
            } else {
                return null;
            }
            return Math.round(secs * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Rewrites an absolute host path token to its path inside the container.
     * Tokens under this call's own work dir map to the read-write mount
     * ({@code /sandbox}). Anything else (flags like {@code -O2}, relative
     * binary names) passes through unchanged. Callers must stage every file a
     * process needs inside its private work directory; paths into sibling
     * judge directories are intentionally inaccessible.
     *
     * The container is always Linux regardless of host OS, so the result
     * must always use forward slashes — but only the matched prefix is
     * replaced with {@code /sandbox}; the remainder of
     * the token was left exactly as {@link Path#toString()} produced it,
     * which on Windows means backslashes (Windows' own separator). That
     * gave the compiler a literal path like {@code /sandbox\main.cpp} —
     * {@code \m} is not a path separator to a Linux shell, just two
     * characters, so g++ reported the whole thing as one nonexistent
     * filename. Normalizing the suffix's separators fixes it on Windows and
     * is a no-op everywhere else (macOS/Linux never produce backslashes
     * here in the first place).
     */
    private String translatePath(Path workDir, String token) {
        String wd = workDir.toString();
        if (token.equals(wd) || token.startsWith(wd + File.separator)) {
            return "/sandbox" + token.substring(wd.length()).replace('\\', '/');
        }
        return token;
    }

    /**
     * Containers run as a fixed, image-baked uid (not this JVM's host user),
     * so the directory being bind-mounted read-write must be open to any
     * uid. It's pure ephemeral scratch space under the work root — nothing
     * sensitive — so this costs nothing on either native Linux Docker or
     * Docker Desktop's file-sharing layer.
     */
    static void permitContainerAccess(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem (Windows dev machine) — Docker Desktop's
            // own file sharing handles this case without help.
        }
    }

    private static void permitContainerWrite(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
        } catch (UnsupportedOperationException ignored) {
            if (!file.toFile().setWritable(true, false)) {
                throw new IOException("Could not make sandbox metrics file writable: " + file);
            }
        }
    }

    /** The shared parent must never be traversable by other host users. */
    static void restrictToOwner(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem; platform ACLs govern access instead.
        }
    }

    /** Best-effort SIGKILL + forced removal; tolerates the container already being gone. */
    private static void killContainerQuietly(String name) {
        try {
            new ProcessBuilder(dockerBinaryStatic, "kill", name)
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder(dockerBinaryStatic, "rm", "-f", name)
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    private static boolean runsCleanly(String... cmd) {
        return runCapture(cmd) == null;
    }

    private static String captureSuccessfulOutput(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 ? output : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    String sandboxImageReference() {
        return sandboxImageReference;
    }

    /** @return null on success, or the combined stdout/stderr (or exception message) on failure. */
    private static String runCapture(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return null;
            }
            String reason = finished ? "exit " + p.exitValue() : "timed out waiting for it";
            return out.isBlank() ? "(no output, " + reason + ")" : out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Turns whatever {@code docker version} printed on failure into an actual
     * next step, instead of a bare "Docker is not available" that sends
     * whoever set up the lab PC hunting through Docker's own docs. The three
     * cases below are what actually happens on a fresh machine, in the order
     * a first-time setup usually hits them: not installed/not on PATH, not
     * running, and — the Linux-specific one that trips people up because the
     * fix needs a fresh login, not just a new terminal — installed and
     * running but this user was only just added to the docker group.
     */
    private static String remediationFor(String failureOutput) {
        String lower = failureOutput.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied") && lower.contains("docker.sock")) {
            return "  Fix: this user isn't in the 'docker' group yet. Run:\n"
                    + "    sudo usermod -aG docker $USER\n"
                    + "  then fully LOG OUT and back in (a new terminal window is not enough — group\n"
                    + "  membership only takes effect on a fresh login session) and try again.";
        }
        if (lower.contains("cannot connect") || lower.contains("is the docker daemon running")) {
            return "  Fix: Docker is installed but not running. Start Docker Desktop, or on Linux run:\n"
                    + "    sudo systemctl start docker";
        }
        if (lower.contains("no such file or directory") || lower.contains("cannot run program")
                || lower.contains("not recognized")) {
            return "  Fix: docker isn't installed, or isn't reachable from however this server was "
                    + "launched.\n  Install Docker, or set ARBITRATOR_DOCKER_BIN to its full path.";
        }
        return "  Check that Docker is installed, running, and this user can run 'docker version' "
                + "from a terminal.";
    }

    /** Kill anything still running when the Spring context closes. */
    @PreDestroy
    void shutdown() {
        if (!LIVE.isEmpty()) {
            log.warn("Killing {} sandbox container(s) still running at shutdown", LIVE.size());
        }
        LIVE.forEach(SandboxExecutor::killContainerQuietly);
        LIVE.clear();
    }

    private record CapturedOutput(String text, boolean exceeded) { }

    private static CompletableFuture<CapturedOutput> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                byte[] bytes = in.readNBytes(OUTPUT_CAP + 1);
                boolean exceeded = bytes.length > OUTPUT_CAP;
                int kept = Math.min(bytes.length, OUTPUT_CAP);
                return new CapturedOutput(new String(bytes, 0, kept, StandardCharsets.UTF_8), exceeded);
            } catch (IOException e) {
                return new CapturedOutput("", false);
            }
        }, STREAM_POOL);
    }

    private static CapturedOutput safeJoin(CompletableFuture<CapturedOutput> f) {
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new CapturedOutput("", false);
        }
    }

    private static String compileWorkspaceViolation(Path workDir) throws IOException {
        long bytes = 0;
        long files = 0;
        try (var walk = Files.walk(workDir)) {
            var paths = walk.iterator();
            while (paths.hasNext()) {
                Path path = paths.next();
                BasicFileAttributes attributes = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    return "Compilation produced a symbolic link (not allowed)";
                }
                if (attributes.isDirectory()) {
                    continue;
                }
                if (!attributes.isRegularFile()) {
                    return "Compilation produced a non-regular file (not allowed)";
                }
                files++;
                bytes += attributes.size();
                if (files > MAX_COMPILE_FILES) {
                    return "Compilation produced too many files (limit " + MAX_COMPILE_FILES + ")";
                }
                if (bytes > MAX_COMPILE_WORKSPACE_BYTES) {
                    return "Compilation workspace exceeded "
                            + (MAX_COMPILE_WORKSPACE_BYTES / 1024 / 1024) + " MiB";
                }
            }
        }
        return null;
    }

    /** Kills any leftover container for this work dir, then deletes it. */
    public void cleanup(Path workDir) {
        if (workDir == null) {
            return;
        }
        try {
            Process ps = new ProcessBuilder(dockerBinaryStatic, "ps", "-aq",
                    "--filter", "name=" + NAME_PREFIX + workDir.getFileName())
                    .redirectErrorStream(true).start();
            String ids = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            ps.waitFor(5, TimeUnit.SECONDS);
            if (!ids.isBlank()) {
                List<String> cmd = new ArrayList<>(List.of(dockerBinaryStatic, "rm", "-f"));
                cmd.addAll(List.of(ids.split("\\s+")));
                new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // docker unavailable — nothing to clean up on the container side
        }

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
