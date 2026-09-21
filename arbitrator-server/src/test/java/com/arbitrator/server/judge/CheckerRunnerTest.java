/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arbitrator.common.enums.CheckerType;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Problem;

/**
 * Tests for {@link CheckerRunner} (FR-14).
 *
 * Mockito-free unit tests exercising custom checker execution, failure modes, and timeouts.
 */
class CheckerRunnerTest {

    /**
     * Stale guard fixed: the checker compiles and runs INSIDE the Docker
     * sandbox now (S4-B1), same as every submission — a host g++ has had
     * nothing to do with it since Docker sandboxing replaced the old
     * unshare/ulimit split. Checking "g++ on PATH" instead of Docker
     * readiness meant these tests ran (and failed for real reasons, not a
     * skip) on any machine with g++ installed but Docker not yet configured
     * — exactly SandboxExecutorTest's own dockerReady check, duplicated here
     * rather than shared, since these are plain unit tests with no shared
     * base class.
     */
    private static boolean dockerReady;

    private SandboxExecutor sandbox;
    private JudgeProperties props;
    private CheckerRunner checkerRunner;
    private Path workDir;

    @BeforeAll
    static void detectToolchain() {
        dockerReady = runsCleanly("docker", "version")
                && runsCleanly("docker", "image", "inspect", "arbitrator-judge:latest");
    }

    @BeforeEach
    void setUp() throws IOException {
        props = new JudgeProperties();
        props.setWorkRoot(Files.createTempDirectory("arbitrator-checker-test").toString());
        sandbox = new SandboxExecutor(props);
        checkerRunner = new CheckerRunner(sandbox, props);
        workDir = sandbox.createWorkDir(0);
    }

    @AfterEach
    void tearDown() {
        sandbox.cleanup(workDir);
    }

    @Test
    void multipleValidOutputsAccepted() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");

        // Checker that accepts either "10 20" or "20 10" (permutation of multiset)
        String source = """
                #include <iostream>
                #include <fstream>
                using namespace std;
                int main(int argc, char* argv[]) {
                    if (argc < 4) return 2;
                    ifstream out(argv[2]);
                    int a, b;
                    if (!(out >> a >> b)) return 1;
                    int extra;
                    if (out >> extra) return 1;
                    if ((a == 10 && b == 20) || (a == 20 && b == 10)) return 0;
                    return 1;
                }
                """;

        Problem p = createCustomProblem(101L, source);
        Path in = createFile("in.txt", "10 20\n");
        Path exp = createFile("exp.txt", "10 20\n");

        Path out1 = createFile("out1.txt", "10 20\n");
        Path out2 = createFile("out2.txt", "20 10\n");
        Path out3 = createFile("out3.txt", "10 99\n");

        assertEquals(Verdict.AC, checkerRunner.check(p, in, out1, exp));
        assertEquals(Verdict.AC, checkerRunner.check(p, in, out2, exp));
        assertEquals(Verdict.WA, checkerRunner.check(p, in, out3, exp));
        try (var files = Files.list(workDir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("__checker-")),
                    "per-invocation checker staging directories must be removed immediately");
        }
    }

    @Test
    void checkerCrashReturnsRuntimeError() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");

        String source = """
                int main() {
                    return 42; // non-zero unmapped exit code
                }
                """;

        Problem p = createCustomProblem(102L, source);
        Path in = createFile("in.txt", "1\n");
        Path out = createFile("out.txt", "1\n");
        Path exp = createFile("exp.txt", "1\n");

        Verdict v = checkerRunner.check(p, in, out, exp);
        assertEquals(Verdict.RE, v, "Checker crash must report RE, not AC or WA");
    }

    @Test
    void checkerHangsKilledByTimeoutReturnsRuntimeError() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");

        String source = """
                int main() {
                    while (true) {}
                    return 0;
                }
                """;

        props.setCheckerTimeLimitMs(500); // 500ms budget

        Problem p = createCustomProblem(103L, source);
        Path in = createFile("in.txt", "1\n");
        Path out = createFile("out.txt", "1\n");
        Path exp = createFile("exp.txt", "1\n");

        long start = System.currentTimeMillis();
        Verdict v = checkerRunner.check(p, in, out, exp);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(Verdict.RE, v, "Checker hang must produce RE verdict");
        assertTrue(elapsed < 4000, "Checker timeout must kill promptly, took " + elapsed + " ms");
    }

    private Problem createCustomProblem(long id, String checkerSource) {
        Problem p = new Problem();
        setId(p, id);
        p.setCheckerType(CheckerType.CUSTOM);
        p.setCheckerSource(checkerSource);
        return p;
    }

    private Path createFile(String name, String content) throws IOException {
        Path p = workDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private static boolean runsCleanly(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void setId(Object entity, Long id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
