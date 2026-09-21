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
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.arbitrator.common.enums.CheckerType;
import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.TestCase;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.leaderboard.LeaderboardBroadcaster;
import com.arbitrator.server.realtime.VerdictPublisher;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.repo.UserRepository;

/**
 * Unit tests for {@link JudgeWorker} pipeline ordering and short-circuit behavior (FR-14).
 * Mockito-free.
 */
class JudgeWorkerTest {

    private static boolean dockerReady;

    private SandboxExecutor sandbox;
    private JudgeProperties props;
    private TrackingCheckerRunner checkerRunner;
    private JudgeWorker judgeWorker;

    private Submission savedSubmission;
    private List<TestCase> configuredTests;
    private Path injectedLinkTarget;
    private String injectedLinkName;
    private boolean injectedLinkTested;

    @BeforeAll
    static void detectToolchain() {
        dockerReady = runsCleanly("docker", "version")
                && runsCleanly("docker", "image", "inspect", "arbitrator-judge:latest");
    }

    @BeforeEach
    void setUp() throws IOException {
        props = new JudgeProperties();
        props.setWorkRoot(Files.createTempDirectory("arbitrator-judgeworker-test").toString());

        JudgeProperties.LanguageSpec cppSpec = new JudgeProperties.LanguageSpec();
        cppSpec.setSourceFile("main.cpp");
        cppSpec.setCompile("g++ -O2 -std=c++17 -o {exe} {src}");
        cppSpec.setRun("{exe}");
        props.setLanguages(java.util.Map.of("cpp17", cppSpec));

        sandbox = new SandboxExecutor(props) {
            @Override
            public ExecutionResult compile(Path dir, List<String> command)
                    throws IOException, InterruptedException {
                ExecutionResult result = super.compile(dir, command);
                if (result.ok() && injectedLinkTarget != null) {
                    // Explicit fault injection: simulate a compiler-stage link.
                    // This is NOT evidence ordinary C++ source can execute ln.
                    // ln itself runs under the real compilation container UID,
                    // mounts and limits; no host-side symlink creation is used.
                    ExecutionResult planted = super.compile(dir, List.of("ln", "-s",
                            injectedLinkTarget.toString(), dir.resolve(injectedLinkName).toString()));
                    assertFalse(planted.ok(), "compilation must reject the planted symlink");
                    assertTrue(Files.isSymbolicLink(dir.resolve(injectedLinkName)));
                    injectedLinkTested = true;
                    return planted;
                }
                return result;
            }
        };
        checkerRunner = new TrackingCheckerRunner(sandbox, props);

        Problem customProblem = new Problem();
        setId(customProblem, 1L);
        customProblem.setContestId(1L);
        customProblem.setCode("A");
        customProblem.setTitle("Custom Problem");
        customProblem.setTimeLimitMs(1000);
        customProblem.setMemoryLimitKb(262144);
        customProblem.setCheckerType(CheckerType.CUSTOM);
        customProblem.setCheckerSource("int main() { return 0; }");

        TestCase testCase = new TestCase();
        testCase.setProblemId(1L);
        testCase.setIdx(1);
        testCase.setInputData("2 3\n");
        testCase.setExpectedOutput("5\n");
        configuredTests = List.of(testCase);

        ProblemRepository problemRepo = fake(ProblemRepository.class, (method, args) -> {
            if ("findById".equals(method)) return Optional.of(customProblem);
            return null;
        });

        TestCaseRepository testCaseRepo = fake(TestCaseRepository.class, (method, args) -> {
            if ("findByProblemIdOrderByIdxAsc".equals(method)) return configuredTests;
            return null;
        });

        SubmissionRepository submissionRepo = fake(SubmissionRepository.class, (method, args) -> {
            if ("findById".equals(method)) return Optional.ofNullable(savedSubmission);
            if ("save".equals(method)) {
                savedSubmission = (Submission) args[0];
                return savedSubmission;
            }
            return null;
        });

        UserRepository userRepo = fake(UserRepository.class, (method, args) -> Optional.of(new User()));

        VerdictPublisher publisher = new VerdictPublisher(null) {
            @Override
            public void publishVerdict(String username, com.arbitrator.common.dto.VerdictEventDto event) {
                // no-op for test
            }
        };

        LeaderboardBroadcaster leaderboard = new LeaderboardBroadcaster(null, null, null, null) {
            @Override
            public void broadcastNow() {
                // no-op for test
            }
        };

        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                return 1; // no-op for test
            }
        };

        judgeWorker = new JudgeWorker(
                submissionRepo, problemRepo, testCaseRepo, userRepo,
                sandbox, new VerdictEvaluator(), checkerRunner,
                publisher, leaderboard, props, jdbc
        );
    }

    @AfterEach
    void tearDown() {
        Path root = Path.of(props.getWorkRoot());
        sandbox.cleanup(root);
    }

    @Test
    void contestantReShortCircuitsCheckerInvocation() {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");

        // Contestant code that crashes (exit 1)
        String crashingCode = """
                #include <cstdlib>
                int main() {
                    std::exit(1);
                }
                """;

        Submission sub = createSubmission(10L, crashingCode);
        savedSubmission = sub;

        judgeWorker.judge(sub.getId());

        assertEquals(Verdict.RE, savedSubmission.getVerdict());
        assertFalse(checkerRunner.checkCalled,
                "Checker MUST NOT be invoked when contestant program crashes (pipeline short-circuit)");
    }

    @Test
    void contestantTleShortCircuitsCheckerInvocation() {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");

        // Contestant code that infinite loops
        String tleCode = """
                int main() {
                    while (true) {}
                }
                """;

        Submission sub = createSubmission(11L, tleCode);
        savedSubmission = sub;

        judgeWorker.judge(sub.getId());

        assertEquals(Verdict.TLE, savedSubmission.getVerdict());
        assertFalse(checkerRunner.checkCalled,
                "Checker MUST NOT be invoked when contestant program TLEs (pipeline short-circuit)");
    }

    @Test
    void hiddenExpectedOutputIsRemovedBeforeNextTestRuns() {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");

        TestCase second = new TestCase();
        second.setProblemId(1L);
        second.setIdx(2);
        second.setInputData("4 5\n");
        second.setExpectedOutput("9\n");
        configuredTests = List.of(configuredTests.get(0), second);

        String probingCode = """
                #include <filesystem>
                int main() {
                    for (const auto& entry : std::filesystem::directory_iterator(".")) {
                        if (entry.path().filename().string().find("__expected_output_") == 0) return 42;
                    }
                    return 0;
                }
                """;

        Submission sub = createSubmission(12L, probingCode);
        savedSubmission = sub;
        judgeWorker.judge(sub.getId());

        assertEquals(Verdict.AC, savedSubmission.getVerdict(),
                "a later test execution must not see a previous test's hidden expected output");
    }

    @ParameterizedTest
    @CsvSource({"__input.txt, input", "__actual_output_1.txt, output",
            "__expected_output_1.txt, output"})
    void plantedCompilationSymlinkCannotRedirectJudgeHostWrite(String linkName, String contents)
            throws Exception {
        assumeTrue(dockerReady, "docker (with arbitrator-judge image) not available");
        // Protection regression using explicit compiler-stage fault injection.
        // The outside target is harmless and never mounted into the container.
        Path target = Files.createTempFile("arbitrator-symlink-sentinel-", ".txt");
        try {
            Files.writeString(target, "DO-NOT-OVERWRITE");
            injectedLinkTarget = target;
            injectedLinkName = linkName;
            savedSubmission = createSubmission(13L, """
                    #include <iostream>
                    int main() { std::cout << "5\\n"; }
                    """);
            judgeWorker.judge(savedSubmission.getId());
            assertTrue(injectedLinkTested, "the planted symlink probe actually ran");
            assertEquals("DO-NOT-OVERWRITE", Files.readString(target));
            assertEquals(Verdict.CE, savedSubmission.getVerdict());
            assertFalse(checkerRunner.checkCalled);
            assertTrue(Files.exists(target), "cleanup must not delete the outside target");
        } finally {
            Files.deleteIfExists(target);
        }
    }

    private Submission createSubmission(long id, String sourceCode) {
        Submission sub = new Submission();
        setId(sub, id);
        sub.setUserId(1L);
        sub.setProblemId(1L);
        sub.setContestId(1L);
        sub.setLanguage(Language.CPP17);
        sub.setSourceCode(sourceCode);
        return sub;
    }

    private static class TrackingCheckerRunner extends CheckerRunner {
        boolean checkCalled = false;

        public TrackingCheckerRunner(SandboxExecutor sandbox, JudgeProperties props) {
            super(sandbox, props);
        }

        @Override
        public Verdict check(Problem problem, Path inputFile, Path contestantOutputFile, Path expectedOutputFile) {
            checkCalled = true;
            return Verdict.AC;
        }
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

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> iface, RepoHandler handler) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[] { iface },
                (proxy, method, args) -> handler.handle(method.getName(), args));
    }

    @FunctionalInterface
    private interface RepoHandler {
        Object handle(String method, Object[] args) throws Throwable;
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
