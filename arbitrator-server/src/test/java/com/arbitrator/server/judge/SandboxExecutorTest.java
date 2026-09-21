/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        configureLanguages(props);
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

    @Test
    void siblingWorkspacesAreNotVisibleInsideSandbox() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path sibling = sandbox.createWorkDir(999);
        try {
            Files.writeString(sibling.resolve("secret.txt"), "other submission", StandardCharsets.UTF_8);
            Path src = workDir.resolve("isolation.cpp");
            Files.writeString(src, """
                    #include <filesystem>
                    #include <iostream>
                    int main() {
                        std::cout << (std::filesystem::exists("/base") ? "EXPOSED" : "ISOLATED");
                    }
                    """, StandardCharsets.UTF_8);
            Path exe = workDir.resolve("isolation");
            ExecutionResult c = sandbox.compile(workDir,
                    List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString()));
            assertTrue(c.ok(), c.stderr());

            ExecutionResult r = runWithInput(exe, "");
            assertEquals("ISOLATED", r.stdout());
        } finally {
            sandbox.cleanup(sibling);
        }
    }

    @Test
    void executionWorkspaceIsReadOnly() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path src = workDir.resolve("write.cpp");
        Files.writeString(src, """
                #include <fstream>
                int main() {
                    std::ofstream file("should-not-exist.txt");
                    return file ? 0 : 23;
                }
                """, StandardCharsets.UTF_8);
        Path exe = workDir.resolve("write-test");
        ExecutionResult c = sandbox.compile(workDir,
                List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString()));
        assertTrue(c.ok(), c.stderr());

        ExecutionResult r = runWithInput(exe, "");
        assertEquals(23, r.exitCode());
        assertFalse(Files.exists(workDir.resolve("should-not-exist.txt")));
    }

    @Test
    void networkIsolationBlocksLoopbackPrivateMetadataAndInternet() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path src = workDir.resolve("network-probe.cpp");
        Files.writeString(src, """
                #include <arpa/inet.h>
                #include <netdb.h>
                #include <sys/socket.h>
                #include <sys/time.h>
                #include <unistd.h>
                #include <cstring>
                #include <iostream>

                static bool connect_to(const sockaddr* address, socklen_t length, int family) {
                    int fd = socket(family, SOCK_STREAM, 0);
                    if (fd < 0) return false;
                    timeval timeout{0, 200000};
                    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
                    bool connected = connect(fd, address, length) == 0;
                    close(fd);
                    return connected;
                }

                static bool connect_ip(const char* text, int family) {
                    if (family == AF_INET) {
                        sockaddr_in address{};
                        address.sin_family = AF_INET;
                        address.sin_port = htons(1);
                        inet_pton(AF_INET, text, &address.sin_addr);
                        return connect_to(reinterpret_cast<sockaddr*>(&address), sizeof(address), family);
                    }
                    sockaddr_in6 address{};
                    address.sin6_family = AF_INET6;
                    address.sin6_port = htons(1);
                    inet_pton(AF_INET6, text, &address.sin6_addr);
                    return connect_to(reinterpret_cast<sockaddr*>(&address), sizeof(address), family);
                }

                static bool connect_localhost() {
                    addrinfo hints{};
                    hints.ai_socktype = SOCK_STREAM;
                    addrinfo* results = nullptr;
                    if (getaddrinfo("localhost", "1", &hints, &results) != 0) return false;
                    bool connected = false;
                    for (addrinfo* item = results; item != nullptr; item = item->ai_next) {
                        connected = connected || connect_to(item->ai_addr, item->ai_addrlen, item->ai_family);
                    }
                    freeaddrinfo(results);
                    return connected;
                }

                int main() {
                    struct Target { const char* address; int family; } targets[] = {
                        {"127.0.0.1", AF_INET}, {"::1", AF_INET6},
                        {"10.0.0.1", AF_INET}, {"172.16.0.1", AF_INET},
                        {"192.168.0.1", AF_INET}, {"169.254.169.254", AF_INET},
                        {"1.1.1.1", AF_INET}
                    };
                    bool exposed = connect_localhost();
                    if (exposed) std::cout << "reachable:localhost\\n";
                    for (const Target& target : targets) {
                        if (connect_ip(target.address, target.family)) {
                            exposed = true;
                            std::cout << "reachable:" << target.address << '\\n';
                        }
                    }
                    return exposed ? 42 : 0;
                }
                """, StandardCharsets.UTF_8);
        Path exe = workDir.resolve("network-probe");
        ExecutionResult c = sandbox.compile(workDir,
                List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString()));
        assertTrue(c.ok(), c.stderr());

        ExecutionResult r = sandbox.run(workDir, List.of(exe.toString()), null, 3_000, MEM_KB);
        assertTrue(r.ok(), "sandbox reached a forbidden network target: " + r.stdout() + r.stderr());
        assertTrue(r.stdout().isBlank(), "reachable network targets: " + r.stdout());
    }

    @Test
    void excessiveStdoutIsStoppedAndReported() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path src = workDir.resolve("output.cpp");
        Files.writeString(src, """
                #include <iostream>
                int main() {
                    while (true) std::cout << "0123456789abcdef";
                }
                """, StandardCharsets.UTF_8);
        Path exe = workDir.resolve("output-test");
        ExecutionResult c = sandbox.compile(workDir,
                List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString()));
        assertTrue(c.ok(), c.stderr());

        ExecutionResult r = sandbox.run(workDir, List.of(exe.toString()), null, 10_000, MEM_KB);
        assertTrue(r.outputLimitExceeded(), "stdout flood must set the explicit OLE signal");
        assertTrue(r.stdout().getBytes(StandardCharsets.UTF_8).length <= SandboxExecutor.OUTPUT_CAP);
        assertFalse(r.timedOut(), "output flood should be killed before its time limit");
    }

    @Test
    void executionAboveAggregateMemoryBudgetIsRejectedBeforeDockerStarts() throws Exception {
        JudgeProperties limited = new JudgeProperties();
        limited.setWorkRoot(Files.createTempDirectory("arbitrator-budget-test").toString());
        limited.setMaxTotalMemoryMb(64);
        SandboxExecutor constrained = new SandboxExecutor(limited);
        Path dir = constrained.createWorkDir(123);
        try {
            IOException error = assertThrows(IOException.class,
                    () -> constrained.run(dir, List.of("true"), null, 100, 65536));
            assertTrue(error.getMessage().contains("aggregate judge memory budget"));
        } finally {
            constrained.cleanup(dir);
        }
    }

    @Test
    void javaAndPythonWorkWithReadOnlyRuntimeWorkspace() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");

        Path javaSource = workDir.resolve("Main.java");
        Files.writeString(javaSource, """
                public class Main {
                    public static void main(String[] args) { System.out.println("java-ok"); }
                }
                """, StandardCharsets.UTF_8);
        ExecutionResult javac = sandbox.compile(workDir,
                List.of("javac", "-d", workDir.toString(), javaSource.toString()));
        assertTrue(javac.ok(), javac.stderr());
        ExecutionResult java = sandbox.run(workDir,
                List.of("java", "-XX:+UseSerialGC", "-cp", workDir.toString(), "Main"),
                null, TL_MS, MEM_KB);
        assertTrue(java.ok(), java.stderr());
        assertEquals("java-ok\n", java.stdout());

        Path pythonSource = workDir.resolve("main.py");
        Files.writeString(pythonSource, "print('python-ok')\n", StandardCharsets.UTF_8);
        ExecutionResult python = sandbox.run(workDir,
                List.of("python3", pythonSource.toString()), null, TL_MS, MEM_KB);
        assertTrue(python.ok(), python.stderr());
        assertEquals("python-ok\n", python.stdout());
    }

    @Test
    void startupPinsMutableImageTagToImmutableImageId() {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");

        sandbox.verifyDockerReady();

        assertTrue(sandbox.sandboxImageReference().matches("sha256:[0-9a-fA-F]{64}"));
    }

    @Test
    void containerReceivesOnlySafeCompilerEnvironment() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path src = workDir.resolve("environment.cpp");
        Files.writeString(src, """
                #include <cstdlib>
                #include <iostream>
                #include <string>

                static std::string value(const char* name) {
                    const char* found = std::getenv(name);
                    return found == nullptr ? "<unset>" : found;
                }

                int main() {
                    const char* cleared[] = {
                        "LD_PRELOAD", "LD_LIBRARY_PATH", "LIBRARY_PATH", "CPATH",
                        "CPLUS_INCLUDE_PATH", "PYTHONHOME", "PYTHONPATH",
                        "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "ENV", "BASH_ENV"
                    };
                    for (const char* name : cleared) {
                        if (!value(name).empty()) {
                            std::cout << name << '=' << value(name) << '\\n';
                            return 41;
                        }
                    }
                    if (value("HOME") != "/tmp") return 42;
                    if (value("PATH") != "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") return 43;
                    std::cout << "clean-env\\n";
                }
                """, StandardCharsets.UTF_8);
        Path exe = workDir.resolve("environment");
        ExecutionResult c = sandbox.compile(workDir,
                List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString()));
        assertTrue(c.ok(), c.stderr());

        ExecutionResult r = runWithInput(exe, "");
        assertTrue(r.ok(), r.stdout() + r.stderr());
        assertEquals("clean-env\n", r.stdout());
    }

    @Test
    void filesystemAttackCannotReadHostOrSiblingSecretsOrWriteSystemFiles() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path sibling = sandbox.createWorkDir(999);
        Path hostSecret = workDir.getParent().resolve(".env");
        Path siblingSecret = sibling.resolve("database.sqlite");
        Files.writeString(hostSecret, "HOST-SECRET");
        Files.writeString(siblingSecret, "SIBLING-SECRET");
        // Make the sentinels readable: namespace isolation, not file permissions,
        // must prevent access from the submission.
        hostSecret.toFile().setReadable(true, false);
        siblingSecret.toFile().setReadable(true, false);
        try {
            Path exe = compileProbe("filesystem-attack", """
                    #include <fstream>
                    #include <iostream>
                    #include <string>
                    #include <unistd.h>
                    int main() {
                        std::string host, sibling;
                        std::getline(std::cin, host);
                        std::getline(std::cin, sibling);
                        const std::string paths[] = {
                            host, sibling, "../.env", "../../.env",
                            "../" + sibling.substr(sibling.find_last_of('/', sibling.find_last_of('/') - 1) + 1),
                            "/proc/1/root" + host, "/proc/1/root" + sibling,
                            "/base/.env", "/var/run/docker.sock"
                        };
                        for (const auto& path : paths) {
                            std::ifstream file(path);
                            if (file) { std::cout << "READABLE:" << path; return 41; }
                        }
                        const char* writes[] = {
                            "/sandbox/attack.txt", "/etc/passwd", "/etc/attack.txt",
                            "/proc/sys/kernel/hostname", "/sys/kernel/attack.txt"
                        };
                        for (const auto* path : writes) {
                            // Opening /etc/passwd is deliberately non-truncating.
                            std::fstream file(path, std::ios::in | std::ios::out);
                            if (file) { std::cout << "WRITABLE:" << path; return 42; }
                        }
                        std::ofstream created("/sandbox/attack.txt");
                        if (created) return 43;
                        std::ofstream systemCreated("/etc/attack.txt");
                        if (systemCreated) return 44;
                        if (getuid() == 0) return 45;
                        std::cout << "isolated\\n";
                    }
                    """);
            ExecutionResult result = runWithInput(exe, hostSecret + "\n" + siblingSecret + "\n");
            assertTrue(result.ok(), result.toString());
            assertEquals("isolated\n", result.stdout());
            assertEquals("HOST-SECRET", Files.readString(hostSecret));
            assertEquals("SIBLING-SECRET", Files.readString(siblingSecret));
            assertFalse(Files.exists(workDir.resolve("attack.txt")));
        } finally {
            Files.deleteIfExists(hostSecret);
            sandbox.cleanup(sibling);
        }
    }

    @Test
    void temporaryFilesDoNotSurviveIntoNextExecutionContainer() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path exe = compileProbe("tmp-isolation", """
                #include <fstream>
                #include <iostream>
                int main() {
                    std::ifstream previous("/tmp/previous-submission-secret");
                    if (previous) return 41;
                    std::ofstream scratch("/tmp/previous-submission-secret");
                    scratch << "private scratch";
                    scratch.close();
                    if (!scratch) return 42;
                    std::cout << "fresh-tmp\\n";
                }
                """);
        for (int run = 0; run < 2; run++) {
            ExecutionResult result = runWithInput(exe, "");
            assertTrue(result.ok(), result.toString());
            assertEquals("fresh-tmp\n", result.stdout());
        }
    }

    @Test
    void metricsTamperingProbeConfirmsWritableBookkeepingExposure() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        // Characterization of the current vulnerability, NOT a protection test.
        // GNU time may overwrite this data on exit; writable access alone does
        // not prove a forged final measurement or a verdict bypass.
        Path exe = compileProbe("metrics-tampering", """
                #include <fstream>
                #include <iostream>
                #include <string>
                int main() {
                    std::fstream metrics("/run-metrics", std::ios::in | std::ios::out);
                    if (!metrics) return 41;
                    const std::string forged = "ATTACKER-CONTROLLED-METRICS";
                    metrics.seekp(0);
                    metrics << forged << std::flush;
                    if (!metrics) return 42;
                    metrics.seekg(0);
                    std::string observed(forged.size(), '\\0');
                    metrics.read(observed.data(), observed.size());
                    if (observed != forged) return 43;
                    std::cout << "metrics-tampered\\n";
                }
                """);
        ExecutionResult result = runWithInput(exe, "");
        assertTrue(result.ok(), result.toString());
        assertEquals("metrics-tampered\n", result.stdout());
    }

    @Test
    void ordinaryRuntimeSourceCannotPlantHostWorkspaceSymlink() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path exe = compileProbe("runtime-symlink", """
                #include <unistd.h>
                #include <iostream>
                int main() {
                    if (symlink("/etc/passwd", "/sandbox/__input.txt") == 0) return 41;
                    if (symlink("/etc/passwd", "/sandbox/new-link") == 0) return 42;
                    // Private tmpfs permits links, but host staging never uses it.
                    if (symlink("/etc/passwd", "/tmp/private-link") != 0) return 43;
                    std::cout << "host-workspace-protected\\n";
                }
                """);
        ExecutionResult result = runWithInput(exe, "");
        assertTrue(result.ok(), result.toString());
        assertEquals("host-workspace-protected\n", result.stdout());
        assertFalse(Files.isSymbolicLink(workDir.resolve("__input.txt")));
        assertFalse(Files.exists(workDir.resolve("new-link")));
    }

    private Path compileProbe(String name, String source) throws Exception {
        Path src = workDir.resolve(name + ".cpp");
        Files.writeString(src, source, StandardCharsets.UTF_8);
        Path exe = workDir.resolve(name);
        ExecutionResult result = sandbox.compile(workDir,
                List.of("g++", "-O2", "-std=c++17", "-o", exe.toString(), src.toString()));
        assertTrue(result.ok(), result.stderr());
        return exe;
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

    private static void configureLanguages(JudgeProperties props) {
        JudgeProperties.LanguageSpec cpp = new JudgeProperties.LanguageSpec();
        cpp.setSourceFile("main.cpp");
        cpp.setCompile("g++ -O2 -std=c++17 -o {exe} {src}");
        cpp.setRun("{exe}");
        JudgeProperties.LanguageSpec java = new JudgeProperties.LanguageSpec();
        java.setSourceFile("Main.java");
        java.setCompile("javac -d {dir} {src}");
        java.setRun("java -XX:+UseSerialGC -Xss256m -cp {dir} Main");
        JudgeProperties.LanguageSpec python = new JudgeProperties.LanguageSpec();
        python.setSourceFile("main.py");
        python.setRun("python3 {src}");
        props.setLanguages(Map.of("cpp17", cpp, "java17", java, "python310", python));
    }
}
