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
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

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
        sandbox.cleanup(Path.of(props.getWorkRoot()));
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
    void checkerCompilerAndRuntimeOutputNeverEnterOperationalLog() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Logger logger = (Logger) LoggerFactory.getLogger(CheckerRunner.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            assertEquals(Verdict.RE, check(901L, "#error PRIVATE_CHECKER_SOURCE_SENTINEL\nint main(){return 0;}"));
            Problem problem = createCustomProblem(902L, """
                    #include <fstream>
                    #include <iostream>
                    #include <string>
                    int main(int argc, char* argv[]) {
                        std::ifstream expected(argv[3]);
                        std::string secret;
                        std::getline(expected, secret);
                        std::cerr << secret;
                        return 42;
                    }
                    """);
            Path input = createFile("secret-input.txt", "1\n");
            Path actual = createFile("secret-actual.txt", "1\n");
            Path expected = createFile("secret-expected.txt", "PRIVATE_HIDDEN_TEST_SENTINEL\n");
            assertEquals(Verdict.RE, checkerRunner.check(problem, input, actual, expected));
            assertEquals(2, appender.list.size());
            for (var event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("PRIVATE_"));
                assertTrue(event.getThrowableProxy() == null);
            }
        } finally {
            logger.detachAppender(appender); appender.stop();
        }
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

    @Test
    void checkerExceedingConfiguredMemoryLimitReturnsRuntimeError() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        props.setCheckerMemoryLimitKb(64 * 1024); // container receives ~128 MiB headroom
        String source = """
                #include <sys/resource.h>
                #include <vector>
                int main() {
                    std::vector<unsigned char> memory(96ULL * 1024 * 1024);
                    volatile unsigned char* touched = memory.data();
                    for (std::size_t i = 0; i < memory.size(); i += 4096) touched[i] = 1;
                    rusage usage{};
                    if (getrusage(RUSAGE_SELF, &usage) != 0) return 42;
                    return usage.ru_maxrss > 64 * 1024 ? 0 : 43;
                }
                """;
        // The container retains observation headroom, but the checker policy
        // must reject measured usage above the configured limit.
        assertEquals(Verdict.RE, check(201L, source));
    }

    @Test
    void checkerCannotReachNetworkEndpoints() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        String source = """
                #include <arpa/inet.h>
                #include <sys/socket.h>
                #include <unistd.h>
                bool connects(const char* ip, int port) {
                    int fd = socket(AF_INET, SOCK_STREAM, 0);
                    if (fd < 0) return false;
                    sockaddr_in address{}; address.sin_family = AF_INET;
                    address.sin_port = htons(port); inet_pton(AF_INET, ip, &address.sin_addr);
                    bool ok = connect(fd, (sockaddr*)&address, sizeof(address)) == 0;
                    close(fd); return ok;
                }
                int main() {
                    return connects("127.0.0.1", 3306)
                        || connects("169.254.169.254", 80)
                        || connects("1.1.1.1", 53) ? 42 : 0;
                }
                """;
        assertEquals(Verdict.AC, check(202L, source));
    }

    @Test
    void checkerCannotReadHostOrSiblingFilesOrWriteRuntimeMount() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path root = Path.of(props.getWorkRoot());
        Path hostSecret = root.resolve("host-secret.txt");
        Path sibling = sandbox.createWorkDir(999);
        Path siblingSecret = sibling.resolve("sibling-secret.txt");
        Files.writeString(hostSecret, "HOST-CHECKER-SECRET");
        Files.writeString(siblingSecret, "SIBLING-CHECKER-SECRET");
        String source = """
                #include <fstream>
                #include <string>
                bool readable(const std::string& p) { std::ifstream f(p); return bool(f); }
                bool writable(const std::string& p) {
                    std::ofstream f(p); f << "attack" << std::flush; return bool(f);
                }
                int main() {
                    const std::string host = "%s", sibling = "%s";
                    if (readable(host) || readable(sibling)
                        || readable("/proc/1/root" + host)
                        || readable("/proc/1/root" + sibling)
                        || readable("/base/host-secret.txt")) return 41;
                    if (writable("/sandbox/new-file") || writable("/sandbox/input.txt")
                        || writable("/etc/checker-attack")) return 42;
                    return 0;
                }
                """.formatted(hostSecret, siblingSecret);
        assertEquals(Verdict.AC, check(203L, source));
        assertEquals("HOST-CHECKER-SECRET", Files.readString(hostSecret));
        assertEquals("SIBLING-CHECKER-SECRET", Files.readString(siblingSecret));
        assertFalse(Files.exists(workDir.resolve("new-file")));
    }

    @Test
    void checkerForkPressureHitsPidLimitAndRecovers() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        String source = """
                #include <sys/wait.h>
                #include <unistd.h>
                #include <vector>
                int main() {
                    int gate[2]; if (pipe(gate) != 0) return 41;
                    std::vector<pid_t> children;
                    bool limited = false;
                    for (int i = 0; i < 256; ++i) {
                        pid_t child = fork();
                        if (child < 0) { limited = true; break; }
                        if (child == 0) { close(gate[1]); char c; read(gate[0], &c, 1); _exit(0); }
                        children.push_back(child);
                    }
                    close(gate[0]); close(gate[1]);
                    for (pid_t child : children) waitpid(child, nullptr, 0);
                    return limited && children.size() < 64 ? 0 : 42;
                }
                """;
        assertEquals(Verdict.AC, check(204L, source));
        assertEquals(Verdict.AC, check(205L, "int main() { return 0; }"),
                "PID exhaustion must not affect the next checker container");
    }

    @Test
    void checkerOutputFloodIsKilledAndReturnsRuntimeError() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        String source = """
                #include <iostream>
                int main() { while (true) std::cerr.put(' '); }
                """;
        long start = System.currentTimeMillis();
        assertEquals(Verdict.RE, check(206L, source));
        assertTrue(System.currentTimeMillis() - start < 10_000,
                "output flood must be stopped promptly");
    }

    private Verdict check(long problemId, String source) throws Exception {
        Problem problem = createCustomProblem(problemId, source);
        Path input = createFile("input-" + problemId + ".txt", "1\n");
        Path actual = createFile("actual-" + problemId + ".txt", "1\n");
        Path expected = createFile("expected-" + problemId + ".txt", "1\n");
        return checkerRunner.check(problem, input, actual, expected);
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
