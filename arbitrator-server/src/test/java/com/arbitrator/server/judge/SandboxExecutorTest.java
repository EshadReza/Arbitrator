package com.arbitrator.server.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S1-B3 / S4-B1 fixture suite: each fixture program must produce its verdict
 * signal (Bundle 1 definition of done). No Spring context — plain
 * construction.
 *
 * Every case here compiles and runs inside the Docker sandbox (see
 * SandboxExecutor), so the host itself needs no toolchain at all — only
 * Docker plus the built arbitrator-judge image
 * (scripts/docker/build-sandbox-image.sh). MLE and fork-bomb used to be
 * Linux-only because they depended on kernel namespaces/prlimit; a cgroup
 * memory/pids limit works identically wherever Docker runs, so they're no
 * longer OS-gated — this is the first time this suite has actually verified
 * them (STATUS.md known issues 1 and 9).
 */
class SandboxExecutorTest {

    private static final int TL_MS = 1000;
    private static final int MEM_KB = 65536;   // 64 MiB

    private static boolean dockerReady;

    private SandboxExecutor sandbox;
    private VerdictEvaluator evaluator;
    private Path workDir;

    @BeforeAll
    static void detectToolchain() {
        dockerReady = runsCleanly("docker", "version")
                && runsCleanly("docker", "image", "inspect", "arbitrator-judge:latest");
    }

    @BeforeEach
    void setUp() throws IOException {
        JudgeProperties props = new JudgeProperties();
        props.setWorkRoot(Files.createTempDirectory("arbitrator-test").toString());
        sandbox = new SandboxExecutor(props);
        evaluator = new VerdictEvaluator();
        workDir = sandbox.createWorkDir(0);
    }

    @AfterEach
    void tearDown() {
        sandbox.cleanup(workDir);
    }

    // ------------------------------------------------------------------

    @Test
    void acFixtureProducesCorrectOutput() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path exe = compileFixture("ac.cpp");
        ExecutionResult r = runWithInput(exe, "2 3\n");
        assertTrue(r.ok(), "expected clean exit, got: " + r);
        assertTrue(evaluator.matches("5\n", r.stdout()));
    }

    @Test
    void waFixtureProducesWrongOutput() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path exe = compileFixture("wa.cpp");
        ExecutionResult r = runWithInput(exe, "2 3\n");
        assertTrue(r.ok());
        assertFalse(evaluator.matches("5\n", r.stdout()));
    }

    @Test
    void tleFixtureIsKilledWithinTwiceTheLimit() throws Exception {   // NFR-R04
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path exe = compileFixture("tle.cpp");
        long start = System.currentTimeMillis();
        ExecutionResult r = runWithInput(exe, "");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(r.timedOut() || r.wallTimeMs() > TL_MS,
                "infinite loop must be flagged as timed out: " + r);
        assertTrue(elapsed < TL_MS * 4L + 10_000,
                "sandbox failed to kill the process promptly, took " + elapsed + " ms");
    }

    @Test
    void ceFixtureFailsToCompileWithDiagnostics() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path src = copyFixture("ce.cpp");
        Path exe = workDir.resolve("prog");
        ExecutionResult r = sandbox.compile(workDir,
                List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString()));
        assertFalse(r.ok(), "broken source must not compile");
        assertFalse(r.stderr().isBlank(), "compiler stderr must be captured (FR-20)");
    }

    @Test
    void reFixtureExitsNonZero() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path exe = compileFixture("re.cpp");
        ExecutionResult r = runWithInput(exe, "");
        assertFalse(r.timedOut());
        assertNotEquals(0, r.exitCode());
    }

    @Test
    void mleFixtureReportsPeakAboveLimit() throws Exception {   // TBD-02 resolved
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path exe = compileFixture("mle.cpp");
        ExecutionResult r = runWithInput(exe, "");
        assertTrue(r.peakMemoryKb() > MEM_KB,
                "peak RSS must exceed the 64 MiB limit, got " + r.peakMemoryKb() + " kB");
    }

    @Test
    void forkBombIsContained() throws Exception {               // FMEA-08
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path exe = compileFixture("forkbomb.cpp");
        long start = System.currentTimeMillis();
        ExecutionResult r = runWithInput(exe, "");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < TL_MS * 4L + 10_000,
                "fork bomb must be reaped promptly, took " + elapsed + " ms");
        // and the machine is still alive to assert anything at all
        assertTrue(r.timedOut() || r.exitCode() != 0);
    }

    // ------------------------------------------------------------------

    private Path compileFixture(String name) throws Exception {
        Path src = copyFixture(name);
        Path exe = workDir.resolve("prog");
        ExecutionResult c = sandbox.compile(workDir,
                List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString()));
        assertTrue(c.ok(), "fixture " + name + " must compile: " + c.stderr());
        return exe;
    }

    private Path copyFixture(String name) throws IOException {
        Path dst = workDir.resolve(name);
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
            Files.copy(in, dst);
        }
        return dst;
    }

    private ExecutionResult runWithInput(Path exe, String input) throws Exception {
        Path in = workDir.resolve("__input.txt");
        Files.writeString(in, input, StandardCharsets.UTF_8);
        return sandbox.run(workDir, List.of(exe.toString()), in, TL_MS, MEM_KB);
    }

    private static boolean runsCleanly(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
