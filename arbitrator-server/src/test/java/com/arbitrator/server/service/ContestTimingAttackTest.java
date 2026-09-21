/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.enums.*;
import com.arbitrator.server.entity.*;
import com.arbitrator.server.judge.*;
import com.arbitrator.server.repo.*;

/** Real timing guard and submission pipeline; repository/access/queue doubles.
 * Uses the real server clock, not a fake exact-nanosecond boundary claim. */
class ContestTimingAttackTest {
    final ContestService guard = new ContestService(null, new BCryptPasswordEncoder(4));

    @ParameterizedTest @EnumSource(ContestState.class)
    void onlyActiveAndFrozenStatesAcceptScoredSubmissions(ContestState state) {
        Contest contest = running(); contest.setState(state);
        if (state == ContestState.ACTIVE || state == ContestState.FROZEN)
            assertDoesNotThrow(() -> guard.assertAcceptingSubmissions(contest));
        else assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> guard.assertAcceptingSubmissions(contest)).getStatusCode().value());
    }

    @Test void futureAndMissingStartsAreRejectedEvenWithActiveState() {
        Contest contest = running(); contest.setStartTime(Instant.now().plusSeconds(60));
        denied(contest); contest.setStartTime(null); denied(contest);
    }

    @Test void elapsedDeadlineAndBoundaryInstantAreRejectedWithoutSchedulerStateChange() {
        Contest contest = running(); contest.setEndedAt(Instant.now().minusSeconds(1));
        denied(contest);
        contest.setEndedAt(Instant.now()); // Boundary or later when guard executes.
        denied(contest);
        assertEquals(ContestState.ACTIVE, contest.getState());
    }

    @Test void missingEndIsRejected() {
        Contest contest = new Contest() { @Override public Instant endTime() { return null; } };
        contest.setState(ContestState.ACTIVE); contest.setStartTime(Instant.now().minusSeconds(1));
        denied(contest);
    }

    @Test void completedPauseExtendsDeadlineOnServerAndPausedStateStillRejects() {
        Contest contest = running(); contest.setStartTime(Instant.now().minusSeconds(90));
        contest.setDurationMinutes(1); denied(contest);
        contest.setPausedMillis(60_000); assertDoesNotThrow(() -> guard.assertAcceptingSubmissions(contest));
        contest.setState(ContestState.PAUSED); contest.setPausedAt(Instant.now()); denied(contest);
    }

    @Test void delayedPersistencePreservesValidatedPreDeadlineAdmissionTime() {
        AtomicReference<Instant> deadline = new AtomicReference<>();
        Contest contest = new Contest() {
            @Override public Long getId() { return 3L; }
            @Override public Instant endTime() { return deadline.get(); }
        };
        contest.setState(ContestState.ACTIVE); contest.setStartTime(Instant.now().minusSeconds(1));
        AtomicReference<Instant> admitted = new AtomicReference<>();
        Submission result = pipeline(contest, at -> {
            deadline.set(at.plusMillis(150));
            guard.assertAcceptingSubmissions(contest, at);
            admitted.set(at);
            assertTrue(admitted.get().isBefore(deadline.get()));
        }, () -> {
            // Harmless, bounded delay at the existing queue reservation boundary.
            long stop = System.nanoTime() + 2_000_000_000L;
            while (Instant.now().isBefore(deadline.get()) && System.nanoTime() < stop)
                LockSupport.parkNanos(1_000_000);
            assertFalse(Instant.now().isBefore(deadline.get()));
        });
        assertEquals(admitted.get(), result.getQueuedAt());
        assertTrue(result.getQueuedAt().isBefore(deadline.get()));
        denied(contest); // A fresh request after deadline is correctly rejected.
        System.out.println("Timing probe: admitted=" + admitted.get() + ", deadline=" + deadline.get()
                + ", stored=" + result.getQueuedAt());
    }

    @Test void pauseBetweenGuardAndReservationDoesNotCancelAlreadyAdmittedRequest() {
        Contest contest = running();
        Submission result = pipeline(contest, at -> guard.assertAcceptingSubmissions(contest, at),
                () -> { contest.setState(ContestState.PAUSED); contest.setPausedAt(Instant.now()); });
        assertNotNull(result.getQueuedAt()); denied(contest);
        System.out.println("Timing probe: already-admitted request persisted during PAUSED state");
    }

    private Contest running() {
        Contest contest = new Contest() { @Override public Long getId() { return 3L; } };
        contest.setState(ContestState.ACTIVE); contest.setStartTime(Instant.now().minusSeconds(1));
        contest.setDurationMinutes(60); return contest;
    }
    private void denied(Contest contest) {
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> guard.assertAcceptingSubmissions(contest)).getStatusCode().value());
    }
    @Test void exactServerAdmissionBoundariesAreStartInclusiveAndEndExclusive() {
        Contest contest = running();
        Instant start = Instant.parse("2026-09-17T10:00:00Z");
        Instant end = start.plusSeconds(60);
        contest.setStartTime(start); contest.setEndedAt(end);
        assertThrows(ResponseStatusException.class, () -> guard.assertAcceptingSubmissions(contest, start.minusNanos(1)));
        assertDoesNotThrow(() -> guard.assertAcceptingSubmissions(contest, start));
        assertDoesNotThrow(() -> guard.assertAcceptingSubmissions(contest, end.minusNanos(1)));
        assertThrows(ResponseStatusException.class, () -> guard.assertAcceptingSubmissions(contest, end));
        assertThrows(ResponseStatusException.class, () -> guard.assertAcceptingSubmissions(contest, end.plusNanos(1)));
    }

    private Submission pipeline(Contest contest, Consumer<Instant> admission, Runnable reservation) {
        JudgeProperties props = new JudgeProperties(); props.setSubmitCooldownSeconds(0);
        AtomicReference<Submission> saved = new AtomicReference<>();
        User user = new User() { @Override public Long getId() { return 1L; } };
        Problem problem = new Problem() { @Override public Long getId() { return 2L; } };
        problem.setContestId(3L);
        UserService users = new UserService(null, new BCryptPasswordEncoder(4), null, null, null, null) {
            @Override public User requireByUsername(String name) { return user; }
        };
        ContestService contests = new ContestService(null, new BCryptPasswordEncoder(4)) {
            @Override public Contest require(long id) { return contest; }
            @Override public void assertAcceptingSubmissions(Contest value, Instant at) { admission.accept(at); }
        };
        ContestAccessService access = new ContestAccessService(null, null, null) {
            @Override public Contest requireReleasedAccess(long id, String name) { return contest; }
        };
        SubmissionRepository submissions = (SubmissionRepository) Proxy.newProxyInstance(
                SubmissionRepository.class.getClassLoader(), new Class<?>[]{SubmissionRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "countByUserIdAndStatusNot" -> 0L;
                    case "findSourceCodesByUserIdAndProblemIdAndActiveTrue" -> List.of();
                    case "save" -> {
                        Submission submission = (Submission) args[0];
                        var id = Submission.class.getDeclaredField("id"); id.setAccessible(true); id.set(submission, 1L);
                        saved.set(submission); yield submission;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ProblemRepository problems = (ProblemRepository) Proxy.newProxyInstance(
                ProblemRepository.class.getClassLoader(), new Class<?>[]{ProblemRepository.class},
                (proxy, method, args) -> Optional.of(problem));
        JudgeQueue queue = new JudgeQueue(props, null, null) {
            @Override public boolean tryReserve() { reservation.run(); return true; }
            @Override public int enqueueReserved(long id) { return 1; }
        };
        // enqueueReserved is overridden: this queue never starts worker threads.
        SubmissionService service = new SubmissionService(submissions, problems, contests, access, users,
                queue, null, null, null, props);
        assertNotNull(service.submit("alice", new SubmitRequest(2L, Language.CPP17, "int main(){}"), "127.0.0.1"));
        return Objects.requireNonNull(saved.get());
    }
}
