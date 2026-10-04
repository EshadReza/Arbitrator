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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * them (docs/audit-history.md (historical sandbox verification)).
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
    void cloudMetadataHttpPortIsUnroutableDuringCompileAndExecution() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path probe = workDir.resolve("metadata_probe.py");
        Files.writeString(probe, """
                import errno
                import socket
                from pathlib import Path

                # A local positive control proves socket operations work;
                # the denied metadata connection must be a routing boundary.
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1', 0))
                    listener.listen(1)
                    with socket.create_connection(listener.getsockname(), timeout=0.5):
                        pass

                routes = Path('/proc/net/route').read_text().splitlines()[1:]
                assert all(row.split()[0] == 'lo' for row in routes if row.strip()), routes
                try:
                    connection = socket.create_connection(('169.254.169.254', 80), timeout=0.5)
                except OSError as failure:
                    # Refusal or timeout could simply mean no metadata service
                    # exists on this host. Require an actual unreachable route.
                    assert failure.errno in (errno.ENETUNREACH, errno.EHOSTUNREACH), failure
                else:
                    connection.close()
                    raise AssertionError('Metadata HTTP port is reachable')
                # No HTTP request is sent and no credentials are retrieved.
                print('metadata-network-isolated')
                """, StandardCharsets.UTF_8);
        List<String> command = List.of("python3", probe.toString());
        // Exercise both flag sets, including the writable compiler sandbox.
        ExecutionResult compiled = sandbox.compile(workDir, command);
        assertTrue(compiled.ok(), compiled.stdout() + compiled.stderr());
        assertEquals("metadata-network-isolated\n", compiled.stdout());

        ExecutionResult executed = sandbox.run(workDir, command, null, 3_000, MEM_KB);
        assertTrue(executed.ok(), executed.stdout() + executed.stderr());
        assertEquals("metadata-network-isolated\n", executed.stdout());
    }

    @Test
    void externalDnsIsUnavailableDuringCompileAndExecution() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path probe = workDir.resolve("dns_probe.py");
        Files.writeString(probe, """
                import errno
                import ipaddress
                import os
                import socket
                import struct
                from pathlib import Path

                os.environ['RES_OPTIONS'] = 'attempts:1 timeout:1'
                # Local hosts-file resolution is legitimate, not DNS egress.
                assert socket.getaddrinfo('localhost', 53)
                assert all(row.split()[0] == 'lo'
                           for row in Path('/proc/net/route').read_text().splitlines()[1:]
                           if row.strip())
                targets = {'1.1.1.1', '8.8.8.8', '127.0.0.11'}
                for line in Path('/etc/resolv.conf').read_text().splitlines():
                    parts = line.split()
                    if len(parts) >= 2 and parts[0] == 'nameserver':
                        targets.add(parts[1])

                name = 'arbitrator-dns-isolation.invalid'
                query = struct.pack('!HHHHHH', 0x4242, 0x0100, 1, 0, 0, 0)
                for label in name.split('.'):
                    query += bytes([len(label)]) + label.encode('ascii')
                query += bytes([0]) + struct.pack('!HH', 1, 1)
                unreachable = {errno.ENETUNREACH, errno.EHOSTUNREACH,
                               errno.EAFNOSUPPORT, errno.EADDRNOTAVAIL}
                for target in targets:
                    address = ipaddress.ip_address(target)
                    family = socket.AF_INET if address.version == 4 else socket.AF_INET6
                    for kind in (socket.SOCK_DGRAM, socket.SOCK_STREAM):
                        try:
                            with socket.socket(family, kind) as channel:
                                channel.settimeout(0.2)
                                if kind == socket.SOCK_DGRAM:
                                    channel.sendto(query, (target, 53))
                                    channel.recvfrom(4096)
                                else:
                                    channel.connect((target, 53))
                        except OSError as failure:
                            if not address.is_loopback:
                                # Mere refusal/timeout is not proof of isolation.
                                assert failure.errno in unreachable, 'DNS target had a route'
                            else:
                                # A local resolver must not answer/forward DNS.
                                assert isinstance(failure, TimeoutError) or failure.errno in (
                                    unreachable | {errno.ECONNREFUSED}), failure
                        else:
                            raise AssertionError('DNS resolver reachable')

                try:
                    socket.getaddrinfo(name, 53, type=socket.SOCK_DGRAM)
                except socket.gaierror:
                    pass
                else:
                    raise AssertionError('External DNS name resolved')
                print('dns-network-isolated')
                """, StandardCharsets.UTF_8);
        List<String> command = List.of("python3", probe.toString());
        ExecutionResult compiled = sandbox.compile(workDir, command);
        assertTrue(compiled.ok(), compiled.stdout() + compiled.stderr());
        assertEquals("dns-network-isolated\n", compiled.stdout());

        ExecutionResult executed = sandbox.run(workDir, command, null, 5_000, MEM_KB);
        assertTrue(executed.ok(), executed.stdout() + executed.stderr());
        assertEquals("dns-network-isolated\n", executed.stdout());
    }

    @Test
    void procSystemMetadataVisibilityIsCharacterizedWithoutDumpingValues() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path probe = workDir.resolve("proc_visibility.py");
        Files.writeString(probe, """
                from pathlib import Path

                # Characterization, not proof that shared-hardware side channels
                # are prevented. Report readability only, never the raw values.
                for name in ('cpuinfo', 'meminfo', 'uptime', 'stat', 'loadavg'):
                    try:
                        with Path('/proc', name).open('rb') as source:
                            source.read(1)
                        state = 'readable'
                    except OSError:
                        state = 'blocked'
                    print(name + ':' + state)
                """, StandardCharsets.UTF_8);
        ExecutionResult result = sandbox.run(workDir,
                List.of("python3", probe.toString()), null, 1_000, MEM_KB);
        assertTrue(result.ok(), result.stderr());
        List<String> states = result.stdout().lines().toList();
        assertEquals(5, states.size());
        List<String> names = List.of("cpuinfo", "meminfo", "uptime", "stat", "loadavg");
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            assertTrue(states.get(index).equals(name + ":readable")
                    || states.get(index).equals(name + ":blocked"), states.get(index));
        }
        System.out.println("Sandbox /proc visibility (values omitted): " + String.join(", ", states));
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

        assertFalse(sandbox.isRuntimeReady());
        sandbox.verifyDockerReady();

        assertTrue(sandbox.sandboxImageReference().matches("sha256:[0-9a-fA-F]{64}"));
        assertTrue(sandbox.isRuntimeReady());
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

    @ParameterizedTest
    @ValueSource(strings = {"cpp", "java", "python"})
    void supportedLanguagesCannotAccessHostSecretsOrEscapeRuntimeIsolation(String language) throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path hostSecret = workDir.getParent().resolve("fake-host-secret.txt");
        Path sibling = Files.createDirectory(workDir.getParent().resolve("fake-sibling"));
        Path siblingSecret = sibling.resolve("fake-secret.txt");
        Files.writeString(hostSecret, "FAKE-HOST-SECRET");
        Files.writeString(siblingSecret, "FAKE-SIBLING-SECRET");
        try {
            // Ensure denial is not just the host temp directory's default mode 0700.
            assertTrue(workDir.getParent().toFile().setReadable(true, false));
            assertTrue(workDir.getParent().toFile().setExecutable(true, false));
            assertTrue(sibling.toFile().setReadable(true, false));
            assertTrue(sibling.toFile().setExecutable(true, false));
            assertTrue(hostSecret.toFile().setReadable(true, false));
            assertTrue(siblingSecret.toFile().setReadable(true, false));
            List<String> command = compileLanguageIsolationFixture(language);
            Files.writeString(workDir.resolve("positive-control.txt"), "mounted-control");
            ExecutionResult result = runLanguageProbe(command,
                    "isolation\n" + hostSecret + "\n" + siblingSecret + "\n", 3_000);
            assertTrue(result.ok(), result.toString());
            assertEquals("isolation-ok\n", result.stdout());
            assertEquals("FAKE-HOST-SECRET", Files.readString(hostSecret));
            assertEquals("FAKE-SIBLING-SECRET", Files.readString(siblingSecret));
            assertFalse(Files.exists(workDir.resolve("forbidden-write")));
        } finally {
            Files.deleteIfExists(hostSecret);
            Files.deleteIfExists(siblingSecret);
            Files.deleteIfExists(sibling);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cpp", "java", "python"})
    void supportedLanguagesHaveRuntimeTimeBounds(String language) throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        List<String> command = compileLanguageIsolationFixture(language);
        assertLanguagePositiveControl(command);
        ExecutionResult result = runLanguageProbe(command, "loop\n", TL_MS);
        assertTrue(result.timedOut(), result.toString());
        assertTrue(result.wallTimeMs() < 15_000, "bounded timeout must terminate the probe");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cpp", "java", "python"})
    void supportedLanguagesHaveIndependentStdoutAndStderrBounds(String language) throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        List<String> command = compileLanguageIsolationFixture(language);
        assertLanguagePositiveControl(command);
        for (String stream : List.of("stdout", "stderr")) {
            ExecutionResult result = runLanguageProbe(command, stream + "\n", 3_000);
            assertTrue(result.outputLimitExceeded(), language + " " + stream + " must be capped");
            assertTrue(result.stdout().length() <= SandboxExecutor.OUTPUT_CAP);
            assertTrue(result.stderr().length() <= SandboxExecutor.OUTPUT_CAP);
        }
    }

    private void assertLanguagePositiveControl(List<String> command) throws Exception {
        ExecutionResult result = runLanguageProbe(command, "control\n", 3_000);
        assertTrue(result.ok(), result.toString());
        assertEquals("control-ok\n", result.stdout());
    }

    private ExecutionResult runLanguageProbe(List<String> command, String input, int timeMs) throws Exception {
        Path in = workDir.resolve("__input.txt");
        Files.writeString(in, input, StandardCharsets.UTF_8);
        return sandbox.run(workDir, command, in, timeMs, MEM_KB);
    }

    private List<String> compileLanguageIsolationFixture(String language) throws Exception {
        Path source = copyFixture("isolation." + switch (language) {
            case "cpp" -> "cpp";
            case "java" -> "java";
            case "python" -> "py";
            default -> throw new IllegalArgumentException(language);
        });
        if (language.equals("java")) {
            source = Files.move(source, workDir.resolve("Main.java"));
        }
        Path exe = workDir.resolve("prog");
        String key = switch (language) {
            case "cpp" -> "cpp17";
            case "java" -> "java17";
            default -> "python310";
        };
        JudgeProperties props = new JudgeProperties();
        configureLanguages(props);
        JudgeProperties.LanguageSpec spec = props.getLanguages().get(key);
        if (spec.getCompile() != null && !spec.getCompile().isBlank()) {
            ExecutionResult result = sandbox.compile(workDir,
                    LanguageCommandPolicy.render(spec.getCompile(), source, exe, workDir));
            assertTrue(result.ok(), result.stderr());
        }
        return LanguageCommandPolicy.render(spec.getRun(), source, exe, workDir);
    }

    @ParameterizedTest
    @ValueSource(strings = {"threads", "tmpfs", "file-size", "privilege"})
    void boundedResourceAndPrivilegeAttacksAreContainedAndRecover(String mode) throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        List<String> command = List.of("python3", copyFixture("hostile_bounds.py").toString());
        assertLanguagePositiveControl(command);
        ExecutionResult result = runLanguageProbe(command, mode + "\n", 5_000);
        assertTrue(result.ok(), result.toString());
        assertEquals(mode + "-contained\n", result.stdout());
        assertLanguagePositiveControl(command);
    }

    @Test
    void oversizedSyntheticCompilerWorkspaceIsRejectedWithoutFillingHostDisk() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        Path probe = workDir.resolve("compiler_size_probe.py");
        Files.writeString(probe, """
                from pathlib import Path
                # Diagnostic compiler-mode invocation, not a toolchain exploit.
                # Sparse files measure logical output size without filling disk.
                for index in range(3):
                    with Path('oversized-' + str(index)).open('wb') as output:
                        output.truncate(48 * 1024 * 1024)
                print('compiler-output-created')
                """);
        ExecutionResult result;
        try {
            result = sandbox.compile(workDir, List.of("python3", probe.toString()));
            assertEquals("compiler-output-created\n", result.stdout());
            assertFalse(result.ok());
            assertTrue(result.stderr().contains("Compilation workspace exceeded 128 MiB"), result.stderr());
            for (int index = 0; index < 3; index++) {
                assertEquals(48L * 1024 * 1024, Files.size(workDir.resolve("oversized-" + index)));
            }
        } finally {
            for (int index = 0; index < 3; index++) {
                Files.deleteIfExists(workDir.resolve("oversized-" + index));
            }
        }
        assertLanguagePositiveControl(List.of("python3", copyFixture("hostile_bounds.py").toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"signals", "detached-timeout", "orphan"})
    void signalAndDetachedChildAttacksLeaveNoContainerBehind(String mode) throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        List<String> command = List.of("python3", copyFixture("hostile_bounds.py").toString());
        assertLanguagePositiveControl(command);
        long start = System.nanoTime();
        ExecutionResult result = runLanguageProbe(command, mode + "\n", TL_MS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        // Must finish before the child's private 10-second fallback; otherwise
        // natural child exit could falsely look like successful container cleanup.
        assertTrue(elapsedMs < (mode.equals("orphan") ? 5_000 : 8_000),
                "child/signal attack must terminate promptly, took " + elapsedMs + " ms");
        if (mode.equals("orphan")) {
            assertTrue(result.ok(), result.toString());
        } else {
            assertFalse(result.ok(), "timeout evasion must not finish successfully");
        }
        List<String> lines = result.stdout().lines().toList();
        assertEquals(2, lines.size(), result.stdout());
        assertEquals(mode + "-ready", lines.get(1));
        String containerId = lines.get(0);
        assertTrue(containerId.matches("[a-f0-9]{12}"), "expected this probe's Docker hostname");
        assertContainerRemoved(containerId);
        assertLanguagePositiveControl(command);
    }

    @Test
    void runningHostLoopbackServiceIsNotReachableFromSubmission() throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        List<String> command = List.of("python3", copyFixture("hostile_bounds.py").toString());
        try (ServerSocket listener = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(500);
            // Host positive control proves a listening service, not an unused port.
            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                 Socket accepted = listener.accept()) {
                assertTrue(client.isConnected() && accepted.isConnected());
            }
            ExecutionResult result = runLanguageProbe(command,
                    "host-loopback\n" + listener.getLocalPort() + "\n", 3_000);
            assertTrue(result.ok(), result.toString());
            assertEquals("host-loopback-contained\n", result.stdout());
            assertThrows(SocketTimeoutException.class, () -> {
                try (Socket unexpected = listener.accept()) {
                    throw new AssertionError("submission connected to the host listener");
                }
            });
        }
        assertLanguagePositiveControl(command);
    }

    private static void assertContainerRemoved(String containerId) throws Exception {
        // Read-only lookup of one exact probe identity; never enumerate/remove
        // unrelated containers or interpret daemon failure as proof of cleanup.
        for (int attempt = 0; attempt < 20; attempt++) {
            Process inspect = new ProcessBuilder("docker", "container", "inspect", containerId)
                    .redirectErrorStream(true).start();
            assertTrue(inspect.waitFor(5, TimeUnit.SECONDS), "Docker inspect must finish");
            String output = new String(inspect.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (inspect.exitValue() != 0) {
                assertTrue(output.contains("No such container") || output.contains("No such object"), output);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("probe container survived completion: " + containerId);
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
