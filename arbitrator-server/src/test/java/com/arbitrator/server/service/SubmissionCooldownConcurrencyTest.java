/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.enums.Language;
import com.arbitrator.server.entity.*;
import com.arbitrator.server.judge.JudgeProperties;
import com.arbitrator.server.judge.JudgeQueue;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;

class SubmissionCooldownConcurrencyTest {
    @Test
    void parallelSubmissionsCannotBothPassCooldownAndPersist() throws Exception {
        var props = new JudgeProperties();
        props.setSubmitCooldownSeconds(3600);
        var usersReady = new CountDownLatch(2);
        var secondBacklogRead = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var saves = new AtomicInteger();
        var enqueues = new AtomicInteger();
        User user = new User() { @Override public Long getId() { return 1L; } };
        Problem problem = new Problem() { @Override public Long getId() { return 2L; } };
        problem.setContestId(3L);
        Contest contest = new Contest() { @Override public Long getId() { return 3L; } };
        UserService users = new UserService(null, new BCryptPasswordEncoder(4), null, null, null, null) {
            @Override public User requireByUsername(String username) {
                usersReady.countDown();
                try { assertTrue(usersReady.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return user;
            }
        };
        ContestService contests = new ContestService(null, new BCryptPasswordEncoder(4)) {
            @Override public Contest require(long id) { return contest; }
            @Override public void assertAcceptingSubmissions(Contest c, java.time.Instant at) {}
        };
        ContestAccessService access = new ContestAccessService(null, null, null) {
            @Override public Contest requireReleasedAccess(long id, String username) { return contest; }
        };
        SubmissionRepository submissions = (SubmissionRepository) Proxy.newProxyInstance(
                SubmissionRepository.class.getClassLoader(), new Class<?>[] {SubmissionRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "countByUserIdAndStatusNot" -> {
                        if (reads.incrementAndGet() >= 2) secondBacklogRead.countDown();
                        yield 0L;
                    }
                    case "findSourceCodesByUserIdAndProblemIdAndActiveTrue" -> List.of();
                    case "save" -> {
                        saves.incrementAndGet();
                        var id = Submission.class.getDeclaredField("id");
                        id.setAccessible(true);
                        id.set(args[0], 1L);
                        // Widen the old check-then-put race; atomic admission prevents a second read.
                        secondBacklogRead.await(250, TimeUnit.MILLISECONDS);
                        yield args[0];
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ProblemRepository problems = (ProblemRepository) Proxy.newProxyInstance(
                ProblemRepository.class.getClassLoader(), new Class<?>[] {ProblemRepository.class},
                (proxy, method, args) -> Optional.of(problem));
        JudgeQueue queue = new JudgeQueue(props, null, null) {
            @Override public boolean tryReserve() { return true; }
            @Override public int enqueueReserved(long id) { return enqueues.incrementAndGet(); }
        };
        SubmissionService service = new SubmissionService(submissions, problems, contests, access, users,
                queue, null, null, null, props);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> submit(service, "print(1)"));
            var second = pool.submit(() -> submit(service, "print(2)"));
            assertEquals(List.of(202, 429), java.util.stream.Stream.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)).sorted().toList());
            assertEquals(1, saves.get());
            assertEquals(1, enqueues.get());
            assertEquals(1, reads.get());
        } finally { pool.shutdownNow(); }
    }
    private static int submit(SubmissionService service, String code) {
        try {
            service.submit("alice", new SubmitRequest(2L, Language.CPP17, code), "127.0.0.1");
            return 202;
        } catch (ResponseStatusException e) { return e.getStatusCode().value(); }
    }
}
