/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.CustomRunRequest;
import com.arbitrator.common.enums.Language;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.service.ContestAccessService;

class CustomRunServiceLimitTest {

    @Test
    void sandboxFailureDoesNotBecomeStudentFacingInternalDetailAndSlotRecovers() {
        JudgeProperties props = new JudgeProperties();
        JudgeProperties.LanguageSpec python = new JudgeProperties.LanguageSpec();
        python.setSourceFile("main.py");
        python.setRun("python3 {src}");
        props.setLanguages(Map.of("python310", python));
        Problem problem = new Problem();
        problem.setContestId(1L);
        ProblemRepository problems = (ProblemRepository) Proxy.newProxyInstance(
                ProblemRepository.class.getClassLoader(), new Class<?>[] { ProblemRepository.class },
                (proxy, method, args) -> method.getName().equals("findById")
                        ? Optional.of(problem) : defaultValue(method.getReturnType()));
        ContestAccessService access = new ContestAccessService(null, null, null) {
            @Override public Contest requireReleasedAccess(long contestId, String username) { return new Contest(); }
        };
        SandboxExecutor broken = new SandboxExecutor(props) {
            @Override public Path createWorkDir(long submissionId) throws IOException {
                throw new IOException("PRIVATE_INTERNAL_SENTINEL /tmp/work SECRET_SQL");
            }
            @Override public void cleanup(Path workDir) {}
        };
        CustomRunService service = new CustomRunService(problems, broken, props, access);
        CustomRunRequest run = new CustomRunRequest(1L, Language.PYTHON310, "print(1)", "");
        for (int attempt = 0; attempt < 2; attempt++) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> service.run("alice", run));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatusCode());
            assertEquals("Unable to complete this request", error.getReason());
            assertFalse(error.getReason().contains("PRIVATE_INTERNAL_SENTINEL"));
        }
    }

    @Test
    void oversizedUtf8SourceIsRejectedBeforeProblemLookup() {
        JudgeProperties props = new JudgeProperties();
        CustomRunService service = new CustomRunService(null, null, props, null);
        String source = "😀".repeat(70_000);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.run("alice", new CustomRunRequest(1L, Language.CPP17, source, "")));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatusCode());
    }

    @Test
    void oneUserAndGlobalCustomRunConcurrencyAreBounded() throws Exception {
        JudgeProperties props = new JudgeProperties();
        props.setMaxCustomRuns(1);
        JudgeProperties.LanguageSpec python = new JudgeProperties.LanguageSpec();
        python.setSourceFile("main.py");
        python.setRun("python3 {src}");
        props.setLanguages(Map.of("python310", python));

        Problem problem = new Problem();
        problem.setContestId(1L);
        problem.setTimeLimitMs(1000);
        problem.setMemoryLimitKb(65536);
        ProblemRepository problems = (ProblemRepository) Proxy.newProxyInstance(
                ProblemRepository.class.getClassLoader(), new Class<?>[] { ProblemRepository.class },
                (proxy, method, args) -> method.getName().equals("findById")
                        ? Optional.of(problem) : defaultValue(method.getReturnType()));
        ContestAccessService access = new ContestAccessService(null, null, null) {
            @Override
            public Contest requireReleasedAccess(long contestId, String username) {
                return new Contest();
            }
        };
        BlockingSandbox sandbox = new BlockingSandbox(props);
        CustomRunService service = new CustomRunService(problems, sandbox, props, access);
        CustomRunRequest request = new CustomRunRequest(1L, Language.PYTHON310, "print(1)", "");

        CompletableFuture<?> first = CompletableFuture.runAsync(() -> service.run("alice", request));
        assertTrue(sandbox.started.await(2, TimeUnit.SECONDS));
        ResponseStatusException sameUser = assertThrows(ResponseStatusException.class,
                () -> service.run("alice", request));
        ResponseStatusException global = assertThrows(ResponseStatusException.class,
                () -> service.run("bob", request));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, sameUser.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, global.getStatusCode());

        sandbox.release.countDown();
        first.get(2, TimeUnit.SECONDS);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        return null;
    }

    private static final class BlockingSandbox extends SandboxExecutor {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingSandbox(JudgeProperties props) {
            super(props);
        }

        @Override
        public Path createWorkDir(long submissionId) throws IOException {
            return Files.createTempDirectory("custom-run-limit");
        }

        @Override
        public ExecutionResult run(Path workDir, List<String> command, Path inputFile,
                                   int timeLimitMs, int memoryLimitKb) throws InterruptedException {
            started.countDown();
            release.await();
            return new ExecutionResult(0, "", "", 1, 1, false, false);
        }

        @Override
        public void cleanup(Path workDir) {
            if (workDir == null) return;
            try (var paths = Files.walk(workDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }
}
