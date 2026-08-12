package com.arbitrator.server.judge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *  - only {@link JudgeProperties#getWorkRoot()} is ever bind-mounted (read-
 *    only) plus the specific call's own work dir (read-write) — the rest of
 *    the host filesystem is never visible, closing the gap the previous
 *    Linux path left open (STATUS.md known issue 10a)
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

    private static final ExecutorService STREAM_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sandbox-stream");
        t.setDaemon(true);
        return t;
    });

    /** Captured output cap; hitting it means OLE, not a host memory problem. */
    public static final int OUTPUT_CAP = 10 * 1024 * 1024;                   // 10 MiB

    private static final long COMPILE_MEMORY_KB = 2L * 1024 * 1024;          // 2 GiB
    private static final long MAX_FILE_SIZE_BYTES = 64L * 1024 * 1024;       // 64 MiB

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

    public SandboxExecutor(JudgeProperties props) {
        this.props = props;
        this.dockerBinary = resolveDockerBinary(props.getDockerBinary());
        dockerBinaryStatic = this.dockerBinary;
    }

    /**
     * If the configured binary already runs, use it as-is — this covers both
     * an explicit operator override (e.g. {@code ARBITRATOR_DOCKER_BIN}) and
     * the common case where a bare "docker" already resolves via PATH. Only
     * when the UNMODIFIED default fails do we go looking in the usual
     * per-OS install locations, and only ever return one of those if it
     * actually runs — never guess silently past that.
     */
    private static String resolveDockerBinary(String configured) {
        if (runsCleanly(configured, "version")) {
            return configured;
        }
        if (!"docker".equals(configured)) {
            return configured;   // an explicit override that doesn't work — surface the real reason, don't second-guess it
        }
        for (String candidate : DOCKER_CANDIDATES) {
            if (Files.isExecutable(Path.of(candidate)) && runsCleanly(candidate, "version")) {
                log.info("'docker' was not on PATH for this process; found a working one at {}", candidate);
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
        String failure = runCapture(dockerBinary, "version");
        if (failure != null) {
            log.error("Docker sandbox is NOT ready — every submission will fail (as a judge-error RE) "
                    + "until this is fixed.\n  Tried to run: {} version\n  Result: {}\n{}",
                    dockerBinary, failure, remediationFor(failure));
        } else if (!runsCleanly(dockerBinary, "image", "inspect", props.getDockerImage())) {
            log.error("Sandbox image '{}' not found. Run this platform's image-load/build script before "
                    + "judging any submission.", props.getDockerImage());
        } else {
            log.info("Docker sandbox ready: image {} via {}", props.getDockerImage(), dockerBinary);
        }

        Path root = Path.of(props.getWorkRoot());
        try {
            Files.createDirectories(root);
            permitContainerAccess(root);
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
        warnIfMemoryOversubscribed();
    }

    /**
     * Pure diagnostic, log-only: {@code arbitrator.judge.threads} concurrent
     * runs can each demand up to {@code MAX_MEMORY_KB * MEMORY_HEADROOM_MULTIPLIER}
     * (a problem's own upper bound, doubled for headroom — see
     * ProblemPackageService and MEMORY_HEADROOM_MULTIPLIER) at the same
     * time. Each container's own {@code --memory} is a hard per-container
     * cap, so no single run can overshoot it — but nothing relates the
     * THREAD COUNT to how much RAM is actually on the box, so a lab PC with
     * modest RAM and a full judge pool of high-memory-limit problems could
     * still be driven into real host-level swapping/OOM by ordinary
     * (non-malicious) contest load. This can't safely auto-correct itself —
     * NFR-P04 requires >= 10 parallel jobs as a product requirement, and
     * silently shrinking the pool would just make the contest slower with no
     * explanation — so it only logs, loud, once, at startup.
     */
    private void warnIfMemoryOversubscribed() {
        long totalPhysicalBytes;
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (!(os instanceof com.sun.management.OperatingSystemMXBean sunOs)) {
                return;                          // non-HotSpot JVM — nothing to report
            }
            totalPhysicalBytes = sunOs.getTotalMemorySize();
        } catch (Throwable ignored) {
            return;                              // best-effort diagnostic only
        }
        if (totalPhysicalBytes <= 0) {
            return;
        }
        // Duplicated from ProblemPackageService.MAX_MEMORY_KB rather than
        // imported — that class already depends on this package (CheckerRunner
        // et al.), and this is a one-line diagnostic, not worth introducing a
        // package cycle over. Keep the two in sync if either changes.
        long maxProblemMemoryKb = 1024 * 1024;   // 1 GiB — matches ProblemPackageService.MAX_MEMORY_KB
        long worstCaseBytes = (long) props.getThreads() * maxProblemMemoryKb * 1024L * MEMORY_HEADROOM_MULTIPLIER;
        double totalGiB = totalPhysicalBytes / (1024.0 * 1024 * 1024);
        double worstCaseGiB = worstCaseBytes / (1024.0 * 1024 * 1024);
        if (worstCaseBytes > totalPhysicalBytes * 0.7) {
            log.warn("Judge capacity check: {} threads x this judge's max allowed problem memory limit "
                    + "({} MiB, doubled for headroom) = {} GiB worst case, against {} GiB of RAM on this "
                    + "machine. A contest that actually hits that ceiling (several concurrent high-memory-"
                    + "limit submissions) risks real host-level swapping or an OOM kill outside any "
                    + "container. If problems here don't need memory limits anywhere near the {} MiB "
                    + "maximum, this is nothing to act on — but if this machine's RAM is genuinely tight, "
                    + "lower arbitrator.judge.threads or keep problem memory limits well under the max.",
                    props.getThreads(), maxProblemMemoryKb / 1024,
                    String.format("%.1f", worstCaseGiB), String.format("%.1f", totalGiB),
                    maxProblemMemoryKb / 1024);
        } else {
            log.info("Judge capacity check: {} threads x worst-case per-submission memory = {} GiB against "
                    + "{} GiB RAM — comfortable headroom.", props.getThreads(),
                    String.format("%.1f", worstCaseGiB), String.format("%.1f", totalGiB));
        }
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
        return runInContainer(workDir, command, null, props.getCompileTimeoutMs(),
                COMPILE_MEMORY_KB, COMPILE_PIDS_LIMIT);
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
                (long) memoryLimitKb * MEMORY_HEADROOM_MULTIPLIER, RUN_PIDS_LIMIT);
    }

    // ------------------------------------------------------------------

    /**
     * Below this much free space on the filesystem backing {@code workRoot},
     * refuse to start another container rather than let it run.
     *
     * {@code --tmpfs /tmp:...,size=64m} caps the container's OWN scratch
     * space, but {@code /sandbox} (this call's work dir) is a plain host
     * bind-mount with no size limit at all — Docker has no portable,
     * driver-independent way to cap a bind mount's total size the way it
     * caps a tmpfs. A submission's own {@code fsize} ulimit only bounds a
     * SINGLE file (64 MiB); nothing stopped one from writing many such files
     * as fast as the disk allows for the full length of its time limit —
     * multiplied by up to {@code RUN_PIDS_LIMIT} processes doing it at once,
     * and again by every concurrent submission across the thread pool. On a
     * lab PC with limited free disk, that is a real path to filling it
     * entirely, which does not fail cleanly: MySQL, the OS, and this very
     * process can all start erroring or hanging once there is nowhere left
     * to write. Checking free space before every run turns that into a
     * same-submission RE instead — cheap (one syscall) and checked often
     * enough that a burst gets stopped within a run or two, not after the
     * disk is actually gone.
     */
    private static final long MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024;   // 1 GiB

    private ExecutionResult runInContainer(Path workDir, List<String> command, Path inputFile,
                                           long timeLimitMs, long memoryLimitKb, int pidsLimit)
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
        String name = NAME_PREFIX + workDir.getFileName() + "-" + seq;

        List<String> cmd = new ArrayList<>(List.of(
                dockerBinary, "run", "--rm", "-i",
                "--name", name,
                "--network", "none",
                "--memory", memoryLimitKb + "k",
                "--memory-swap", memoryLimitKb + "k",
                "--pids-limit", String.valueOf(pidsLimit),
                "--cpus", "1",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--read-only",
                "--tmpfs", "/tmp:rw,size=64m,exec",
                "--ulimit", "fsize=" + MAX_FILE_SIZE_BYTES,
                "--ulimit", "nofile=64:64",
                "-v", props.getWorkRoot() + ":/base:ro",
                "-v", workDir + ":/sandbox:rw",
                "-w", "/sandbox",
                "--user", SANDBOX_UID_GID,
                props.getDockerImage(),
                "timeout", "-k", "1", hardKillS + "s",
                "/usr/bin/time", "-o", "/sandbox/" + rusageName, "-v"));
        for (String token : command) {
            cmd.add(translatePath(workDir, token));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile());
        if (inputFile != null) {
            pb.redirectInput(inputFile.toFile());
        } else {
            pb.redirectInput(new File("/dev/null"));
        }

        long start = System.nanoTime();
        Process p = pb.start();
        LIVE.add(name);

        CompletableFuture<String> out = readAsync(p.getInputStream());
        CompletableFuture<String> err = readAsync(p.getErrorStream());

        try {
            boolean finished = p.waitFor(javaWaitMs, TimeUnit.MILLISECONDS);
            long wallMs = (System.nanoTime() - start) / 1_000_000;
            if (!finished) {
                // The inner `timeout` should have already killed it; this is
                // the safety net for a wedged container (e.g. docker daemon
                // itself stalled) — same two-layer shape as before.
                killContainerQuietly(name);
                p.destroyForcibly();
                return new ExecutionResult(124, safeJoin(out), safeJoin(err), wallMs, -1, true);
            }

            int exit = p.exitValue();
            if (exit == 125) {
                throw new IOException("docker run failed before the sandbox container started: "
                        + safeJoin(err));
            }
            return parseContainerResult(exit, safeJoin(out), safeJoin(err), wallMs, rusageFile);
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
                                                         long wallMs, Path rusageFile) {
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

        return new ExecutionResult(exit, stdout, stderr, elapsedMs, peakKb, timedOut);
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
     * ({@code /sandbox}); anything else under the shared work root (e.g. a
     * cached compiled checker binary living in a sibling directory — see
     * CheckerRunner) maps to the read-only mount ({@code /base}). Anything
     * else (flags like {@code -O2}, relative binary names) passes through
     * unchanged.
     */
    private String translatePath(Path workDir, String token) {
        String wd = workDir.toString();
        if (token.equals(wd) || token.startsWith(wd + File.separator)) {
            return "/sandbox" + token.substring(wd.length());
        }
        String root = Path.of(props.getWorkRoot()).toString();
        if (token.equals(root) || token.startsWith(root + File.separator)) {
            return "/base" + token.substring(root.length());
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
    private static void permitContainerAccess(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem (Windows dev machine) — Docker Desktop's
            // own file sharing handles this case without help.
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
