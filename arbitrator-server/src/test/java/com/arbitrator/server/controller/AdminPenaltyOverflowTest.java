package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.UserRepository;

/** Controller-side arithmetic must preserve valid negative corrections without int wraparound. */
class AdminPenaltyOverflowTest {
    private final Submission row = new Submission();
    private final AtomicInteger saves = new AtomicInteger();
    private final AdminStandingsController controller = controller();

    @Test
    void positiveOverflowIsRejectedWithoutSaving() {
        row.setManualPenaltyDelta(Integer.MAX_VALUE);

        assertEquals(HttpStatus.BAD_REQUEST, controller.adjustPenalty(3, "student", 1).getStatusCode());
        assertEquals(Integer.MAX_VALUE, row.getManualPenaltyDelta());
        assertEquals(0, saves.get());

        assertEquals(HttpStatus.OK, controller.adjustPenalty(3, "student", -5).getStatusCode());
        assertEquals(Integer.MAX_VALUE - 5, row.getManualPenaltyDelta());
        assertEquals(1, saves.get());
    }

    @Test
    void negativeOverflowIsRejectedWithoutSaving() {
        row.setManualPenaltyDelta(Integer.MIN_VALUE);

        assertEquals(HttpStatus.BAD_REQUEST, controller.adjustPenalty(3, "student", -1).getStatusCode());
        assertEquals(Integer.MIN_VALUE, row.getManualPenaltyDelta());
        assertEquals(0, saves.get());

        assertEquals(HttpStatus.OK, controller.adjustPenalty(3, "student", 5).getStatusCode());
        assertEquals(Integer.MIN_VALUE + 5, row.getManualPenaltyDelta());
        assertEquals(1, saves.get());
    }

    @Test
    void ordinaryPositiveAndNegativeAdjustmentsStillWork() {
        assertEquals(HttpStatus.OK, controller.adjustPenalty(3, "student", -5).getStatusCode());
        assertEquals(-5, row.getManualPenaltyDelta());
        assertEquals(HttpStatus.OK, controller.adjustPenalty(3, "student", 10).getStatusCode());
        assertEquals(5, row.getManualPenaltyDelta());
        assertEquals(2, saves.get());
    }

    private AdminStandingsController controller() {
        row.setUserId(7L);
        User user = new User() {
            @Override public Long getId() { return 7L; }
        };
        UserRepository users = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(), new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByUsername")) return Optional.of(user);
                    throw new UnsupportedOperationException(method.getName());
                });
        SubmissionRepository submissions = (SubmissionRepository) Proxy.newProxyInstance(
                SubmissionRepository.class.getClassLoader(), new Class<?>[]{SubmissionRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByContestIdAndActiveTrueOrderByQueuedAtAsc" -> List.of(row);
                    case "save" -> {
                        saves.incrementAndGet();
                        yield args[0];
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new AdminStandingsController(null, null, submissions, users, null, null, null);
    }
}
