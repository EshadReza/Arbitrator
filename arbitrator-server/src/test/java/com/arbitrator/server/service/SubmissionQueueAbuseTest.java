/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.enums.Language;
import com.arbitrator.server.entity.*;
import com.arbitrator.server.judge.JudgeProperties;
import com.arbitrator.server.judge.JudgeQueue;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;

class SubmissionQueueAbuseTest {
    @Test
    void perUserBlocksAtTwentyRemainsBlockedThroughFiveAndRecoversBelowFiveWithoutBlockingOthers() {
        var fixture = new Fixture(0);
        fixture.pending.get(1L).set(20);
        blocked(fixture, "alice");
        fixture.pending.get(1L).set(19);
        blocked(fixture, "alice");
        fixture.pending.get(1L).set(5);
        blocked(fixture, "alice");
        assertEquals(0, fixture.saves.get());
        assertEquals(0, fixture.problemReads.get());
        fixture.submit("bob");
        fixture.pending.get(1L).set(4);
        fixture.submit("alice");
        assertEquals(5, fixture.pending.get(1L).get());
        fixture.submit("alice"); // After recovery, the trip point is twenty again, not five.
        assertEquals(6, fixture.pending.get(1L).get());
        assertEquals(3, fixture.saves.get());
        assertEquals(3, fixture.enqueues.get());
    }

    @Test
    void sixteenParallelAttemptsAtNineteenPendingAdmitExactlyOne() throws Exception {
        var fixture = new Fixture(0);
        fixture.pending.get(1L).set(19);
        var pool = Executors.newFixedThreadPool(16);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 16; i++) tasks.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { fixture.submit("alice"); return 202; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            }));
            start.countDown();
            int accepted = 0, rejected = 0;
            for (var task : tasks) {
                int status = task.get(5, TimeUnit.SECONDS);
                if (status == 202) accepted++;
                else { assertEquals(429, status); rejected++; }
            }
            assertEquals(1, accepted);
            assertEquals(15, rejected);
            assertEquals(20, fixture.pending.get(1L).get());
            assertEquals(1, fixture.saves.get());
            assertEquals(1, fixture.enqueues.get());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void fullGlobalQueueRejectsBeforePersistenceAndRecoversWhenOneSlotIsReleased() throws Exception {
        var fixture = new Fixture(0);
        fixture.slots.acquire(50);
        blocked(fixture, "alice");
        assertEquals(0, fixture.saves.get());
        assertEquals(0, fixture.enqueues.get());
        assertEquals(0, fixture.releases.get());
        fixture.slots.release();
        fixture.submit("alice");
        assertEquals(1, fixture.saves.get());
        assertEquals(1, fixture.enqueues.get());
        assertEquals(0, fixture.slots.availablePermits());
    }

    @Test
    void persistenceFailureReleasesReservedCapacityAndDoesNotConsumeAcceptedSubmissionCooldown() {
        var fixture = new Fixture(3600);
        fixture.failSave.set(true);
        assertThrows(IllegalStateException.class, () -> fixture.submit("alice"));
        assertEquals(50, fixture.slots.availablePermits());
        assertEquals(1, fixture.releases.get());
        assertEquals(0, fixture.enqueues.get());
        assertEquals(0, fixture.pending.get(1L).get());
        fixture.failSave.set(false);
        fixture.submit("alice");
        assertEquals(49, fixture.slots.availablePermits());
        assertEquals(1, fixture.enqueues.get());
    }

    private static void blocked(Fixture fixture, String name) {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class,
                () -> fixture.submit(name)).getStatusCode());
    }

    /** In-memory repository/admission doubles; no database rows or actual judge executions. */
    private static class Fixture {
        final Map<Long, AtomicLong> pending = Map.of(1L, new AtomicLong(), 2L, new AtomicLong());
        final AtomicInteger saves = new AtomicInteger(), enqueues = new AtomicInteger(),
                releases = new AtomicInteger(), problemReads = new AtomicInteger(), sources = new AtomicInteger();
        final AtomicBoolean failSave = new AtomicBoolean();
        final Semaphore slots = new Semaphore(50);
        final SubmissionService service;

        Fixture(int cooldown) {
            var props = new JudgeProperties();
            props.setSubmitCooldownSeconds(cooldown);
            Problem problem = new Problem() { @Override public Long getId() { return 7L; } };
            problem.setContestId(9L);
            Contest contest = new Contest() { @Override public Long getId() { return 9L; } };
            UserService users = new UserService(null, new BCryptPasswordEncoder(4), null, null, null, null) {
                @Override public User requireByUsername(String name) {
                    return new User() { @Override public Long getId() { return name.equals("alice") ? 1L : 2L; } };
                }
            };
            ContestService contests = new ContestService(null, new BCryptPasswordEncoder(4)) {
                @Override public Contest require(long id) { return contest; }
                @Override public void assertAcceptingSubmissions(Contest c, java.time.Instant at) {}
            };
            ContestAccessService access = new ContestAccessService(null, null, null) {
                @Override public Contest requireReleasedAccess(long id, String name) { return contest; }
            };
            SubmissionRepository submissions = (SubmissionRepository) Proxy.newProxyInstance(
                    SubmissionRepository.class.getClassLoader(), new Class<?>[] {SubmissionRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "countByUserIdAndStatusNot" -> pending.get((Long) args[0]).get();
                        case "findSourceCodesByUserIdAndProblemIdAndActiveTrue" -> List.of();
                        case "save" -> {
                            if (failSave.get()) throw new IllegalStateException("controlled database write failure");
                            Submission sub = (Submission) args[0];
                            var id = Submission.class.getDeclaredField("id");
                            id.setAccessible(true);
                            id.set(sub, (long) saves.incrementAndGet());
                            pending.get(sub.getUserId()).incrementAndGet();
                            yield sub;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            ProblemRepository problems = (ProblemRepository) Proxy.newProxyInstance(
                    ProblemRepository.class.getClassLoader(), new Class<?>[] {ProblemRepository.class},
                    (proxy, method, args) -> { problemReads.incrementAndGet(); return Optional.of(problem); });
            JudgeQueue queue = new JudgeQueue(props, null, null) {
                @Override public boolean tryReserve() { return slots.tryAcquire(); }
                @Override public void releaseReservation() { releases.incrementAndGet(); slots.release(); }
                @Override public int enqueueReserved(long id) { return enqueues.incrementAndGet(); }
            };
            service = new SubmissionService(submissions, problems, contests, access, users, queue,
                    null, null, null, props);
        }
        void submit(String name) {
            service.submit(name, new SubmitRequest(7L, Language.PYTHON310,
                    "print(" + sources.incrementAndGet() + ")"), "127.0.0.1");
        }
    }
}
