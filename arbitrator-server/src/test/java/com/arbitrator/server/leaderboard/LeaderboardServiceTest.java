/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Role;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.service.ContestService;

/**
 * Penalty and ranking rules: FR-17, FR-18, BR-03, BR-04, BR-06.
 * Mockito-free (see CLAUDE.md) — plain proxies over the repository interfaces.
 */
class LeaderboardServiceTest {

    private static final Instant START = Instant.parse("2026-05-01T10:00:00Z");

    private LeaderboardService service;
    private List<Submission> allSubmissions;
    private List<Problem> allProblems;
    private List<User> allUsers;
    private final AtomicLong ids = new AtomicLong();

    @BeforeEach
    void setUp() {
        allSubmissions = new ArrayList<>();
        allProblems = new ArrayList<>();
        allUsers = new ArrayList<>();

        Contest contest = new Contest();
        contest.setTitle("Lab Contest #1");
        contest.setState(ContestState.ACTIVE);
        contest.setStartTime(START);
        contest.setDurationMinutes(300);
        setField(contest, "id", 1L);

        SubmissionRepository submissions = fake(SubmissionRepository.class, (m, a) ->
                "findByContestIdAndActiveTrueOrderByQueuedAtAsc".equals(m)
                        ? List.copyOf(allSubmissions) : null);
        ProblemRepository problems = fake(ProblemRepository.class, (m, a) ->
                "findByContestIdOrderByOrderingAscCodeAsc".equals(m)
                        ? List.copyOf(allProblems) : null);
        UserRepository users = fake(UserRepository.class, (m, a) ->
                "findAll".equals(m) ? List.copyOf(allUsers) : null);
        // Neither fake dependency is used by the overridden method below.
        ContestService contestService = new ContestService(null, null) {
            @Override
            public Contest requireCurrent() {
                return contest;
            }
        };

        service = new LeaderboardService(submissions, problems, users, contestService);
    }

    // --- SRS Gherkin scenario 5, verbatim -------------------------------

    @Test
    @DisplayName("Scenario 5: WA at T+5 and T+10, AC at T+20 -> penalty 60")
    void gherkinScenarioFivePenaltyMath() {
        User alice = user("alice");
        Problem p1 = problem("A", "Two Sum");

        submission(alice, p1, Verdict.WA, 5);
        submission(alice, p1, Verdict.WA, 10);
        submission(alice, p1, Verdict.AC, 20);

        LeaderboardRowDto row = service.current().rows().get(0);

        assertEquals(1, row.solved());
        // 20 (first-AC minute) + 2 x 20 (prior rejects) = 60
        assertEquals(60, row.penaltyMinutes());
    }

    // --- individual rules -----------------------------------------------

    @Test
    @DisplayName("BR-04: a compilation error adds no penalty")
    void compilationErrorsAreFree() {
        User alice = user("alice");
        Problem p1 = problem("A", "Two Sum");

        submission(alice, p1, Verdict.CE, 3);
        submission(alice, p1, Verdict.CE, 4);
        submission(alice, p1, Verdict.AC, 10);

        LeaderboardRowDto row = service.current().rows().get(0);
        assertEquals(10, row.penaltyMinutes(), "CE must not add 20-minute penalties");
        assertEquals(0, row.cells().get(0).failedAttempts());
    }

    @Test
    @DisplayName("TLE/MLE/RE before an AC each cost 20 minutes")
    void otherRejectionsDoCostPenalty() {
        User alice = user("alice");
        Problem p1 = problem("A", "Two Sum");

        submission(alice, p1, Verdict.TLE, 2);
        submission(alice, p1, Verdict.MLE, 4);
        submission(alice, p1, Verdict.RE, 6);
        submission(alice, p1, Verdict.AC, 15);

        assertEquals(15 + 60, service.current().rows().get(0).penaltyMinutes());
    }

    @Test
    @DisplayName("BR-03: only the first AC counts; later ones change nothing")
    void onlyFirstAcceptedCounts() {
        User alice = user("alice");
        Problem p1 = problem("A", "Two Sum");

        submission(alice, p1, Verdict.AC, 10);
        submission(alice, p1, Verdict.WA, 20);   // after solving — must be ignored
        submission(alice, p1, Verdict.AC, 30);

        LeaderboardRowDto row = service.current().rows().get(0);
        assertEquals(1, row.solved());
        assertEquals(10, row.penaltyMinutes());
        assertEquals(0, row.cells().get(0).failedAttempts());
    }

    @Test
    @DisplayName("rejections after solving never add penalty")
    void rejectionsAfterAcAreIgnored() {
        User alice = user("alice");
        Problem p1 = problem("A", "Two Sum");

        submission(alice, p1, Verdict.AC, 5);
        submission(alice, p1, Verdict.WA, 6);

        assertEquals(5, service.current().rows().get(0).penaltyMinutes());
    }

    @Test
    @DisplayName("an unsolved problem contributes no penalty, only a red count")
    void unsolvedProblemsCostNothing() {
        User alice = user("alice");
        Problem p1 = problem("A", "Two Sum");

        submission(alice, p1, Verdict.WA, 5);
        submission(alice, p1, Verdict.WA, 9);

        LeaderboardRowDto row = service.current().rows().get(0);
        assertEquals(0, row.solved());
        assertEquals(0, row.penaltyMinutes());
        assertEquals(2, row.cells().get(0).failedAttempts());
        assertFalse(row.cells().get(0).solved());
    }

    @Test
    @DisplayName("queued submissions are invisible until judged")
    void unjudgedSubmissionsAreIgnored() {
        User alice = user("alice");
        Problem p1 = problem("A", "Two Sum");

        submission(alice, p1, null, 5);          // still PENDING

        assertTrue(service.current().rows().isEmpty()
                || service.current().rows().get(0).solved() == 0);
    }

    // --- ranking (BR-06) --------------------------------------------------

    @Test
    @DisplayName("BR-06: more solved wins, then lower penalty, then earlier last AC")
    void rankingOrder() {
        User alice = user("alice");
        User bob = user("bob");
        User carol = user("carol");
        Problem p1 = problem("A", "Two Sum");
        Problem p2 = problem("B", "Sorting");

        // bob solves both -> rank 1 regardless of penalty
        submission(bob, p1, Verdict.AC, 50);
        submission(bob, p2, Verdict.AC, 80);

        // alice and carol each solve one; alice is cheaper
        submission(alice, p1, Verdict.AC, 10);
        submission(carol, p1, Verdict.WA, 5);
        submission(carol, p1, Verdict.AC, 12);   // 12 + 20 = 32

        LeaderboardDto board = service.current();
        assertEquals(List.of("A", "B"), board.problemCodes());

        assertEquals("bob", board.rows().get(0).username());
        assertEquals(1, board.rows().get(0).rank());
        assertEquals(2, board.rows().get(0).solved());

        assertEquals("alice", board.rows().get(1).username());
        assertEquals(10, board.rows().get(1).penaltyMinutes());

        assertEquals("carol", board.rows().get(2).username());
        assertEquals(32, board.rows().get(2).penaltyMinutes());
    }

    @Test
    @DisplayName("equal solved and equal penalty breaks on earlier last AC")
    void tieBreaksOnLastAcceptedTime() {
        User alice = user("alice");
        User bob = user("bob");
        Problem p1 = problem("A", "Two Sum");
        Problem p2 = problem("B", "Sorting");

        submission(alice, p1, Verdict.AC, 10);
        submission(alice, p2, Verdict.AC, 30);   // penalty 40, last AC 30

        submission(bob, p1, Verdict.AC, 15);
        submission(bob, p2, Verdict.AC, 25);     // penalty 40, last AC 25

        LeaderboardDto board = service.current();
        assertEquals(40, board.rows().get(0).penaltyMinutes());
        assertEquals(40, board.rows().get(1).penaltyMinutes());
        assertEquals("bob", board.rows().get(0).username(), "earlier last AC ranks higher");
    }

    @Test
    @DisplayName("a frozen contest is reported frozen (FR-19)")
    void frozenContestIsFlagged() {
        Contest frozen = new Contest();
        frozen.setTitle("Frozen");
        frozen.setState(ContestState.FROZEN);
        frozen.setStartTime(START);
        setField(frozen, "id", 1L);

        assertTrue(service.forContest(frozen).frozen());
    }

    // --- helpers ----------------------------------------------------------

    private User user(String name) {
        User u = new User();
        u.setUsername(name);
        u.setDisplayName(name);
        u.setRole(Role.STUDENT);
        setField(u, "id", ids.incrementAndGet());
        allUsers.add(u);
        return u;
    }

    private Problem problem(String code, String title) {
        Problem p = new Problem();
        p.setCode(code);
        p.setTitle(title);
        p.setContestId(1L);
        setField(p, "id", ids.incrementAndGet());
        allProblems.add(p);
        return p;
    }

    private void submission(User user, Problem problem, Verdict verdict, int minuteFromStart) {
        Submission s = new Submission();
        s.setUserId(user.getId());
        s.setProblemId(problem.getId());
        s.setContestId(1L);
        s.setLanguage(Language.CPP17);
        s.setSourceCode("...");
        s.setVerdict(verdict);
        s.setQueuedAt(START.plus(minuteFromStart, ChronoUnit.MINUTES));
        setField(s, "id", ids.incrementAndGet());
        allSubmissions.add(s);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> iface, RepoHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
                (proxy, method, args) -> handler.handle(method.getName(), args));
    }

    @FunctionalInterface
    private interface RepoHandler {
        Object handle(String method, Object[] args);
    }

    private static void setField(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
