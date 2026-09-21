/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.arbitrator.common.enums.*;
import com.arbitrator.server.entity.*;
import com.arbitrator.server.repo.*;
import com.arbitrator.server.security.*;

/** Live HTTP races plus controlled real-DB controller transaction interleavings.
 * Characterization tests intentionally reproduce failures, not fixes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "arbitrator.api-rate-limit.ip.registration=100")
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class RaceConditionAttackIntegrationTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired ContestRepository contests;
    @Autowired ProblemRepository problems;
    @Autowired SubmissionRepository submissions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired ActiveSessionRegistry sessions;
    @Autowired AdminContestController adminContests;
    @Autowired AdminProblemController adminProblems;
    @Autowired PlatformTransactionManager transactions;
    final List<String> names = new ArrayList<>();
    final List<Long> contestIds = new ArrayList<>();
    String cloneTitle;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach void cleanup() {
        if (cloneTitle != null) contestIds.addAll(jdbc.queryForList(
                "SELECT id FROM contests WHERE title = ?", Long.class, cloneTitle));
        for (Long id : contestIds) if (contests.existsById(id)) adminContests.delete(id);
        for (String name : names) {
            sessions.clear(name);
            users.findByUsername(name).ifPresent(users::delete);
        }
    }

    @Test void simultaneousRegistrationReturnsCleanConflicts() throws Exception {
        String name = unique("race_reg"); names.add(name);
        List<Integer> statuses = burst(8, ignored -> send("POST", "/api/auth/register", null,
                "{\"username\":\"" + name + "\",\"password\":\"Orbit7!Lake\"}"));
        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(7, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, name));
        System.out.println("Concurrent registration statuses: " + statuses);
    }

    @Test void simultaneousJoinsAreIdempotent() throws Exception {
        Contest contest = contest(); contest.setState(ContestState.LOBBY); contests.save(contest);
        String token = token(Role.STUDENT);
        List<Integer> statuses = burst(6, ignored -> send("POST",
                "/api/contests/" + contest.getId() + "/join", token, "{}"));
        assertTrue(statuses.stream().allMatch(s -> s == 200), statuses.toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM contest_access_grants WHERE contest_id = ?",
                Integer.class, contest.getId()));
        System.out.println("Concurrent join statuses: " + statuses);
    }

    @Test void sameTitleClonesReturnCleanConflicts() throws Exception {
        Contest source = contest(); problem(source.getId());
        cloneTitle = unique("race_clone"); String token = token(Role.ADMIN);
        List<Integer> statuses = burst(6, ignored -> send("POST",
                "/api/admin/contests/" + source.getId() + "/clone", token,
                "{\"title\":\"" + cloneTitle + "\"}"));
        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
        assertEquals(5, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM contests WHERE title = ?", Integer.class, cloneTitle));
        Long clonedId = jdbc.queryForObject("SELECT id FROM contests WHERE title = ?", Long.class, cloneTitle);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM problems WHERE contest_id = ?", Integer.class, clonedId));
        System.out.println("Concurrent clone statuses: " + statuses);
    }

    @Test void disjointProblemEditsRejectStaleWriterInsteadOfLosingSuccessfulChange() throws Exception {
        Problem p = problem(contest().getId());
        CyclicBarrier loaded = new CyclicBarrier(2);
        List<Integer> outcomes = burst(2, index -> {
            try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                // Managed entities retain the same old snapshot on both threads.
                problems.findById(p.getId()).orElseThrow();
                await(loaded);
                adminProblems.update(p.getId(), index == 0
                        ? new AdminProblemController.ProblemEditRequest("Edited title", null, null, null)
                        : new AdminProblemController.ProblemEditRequest(null, null, 2000, null));
            });
            return 200;
            } catch (org.springframework.dao.OptimisticLockingFailureException failure) { return 409; }
        });
        assertEquals(1, outcomes.stream().filter(s -> s == 200).count());
        assertEquals(1, outcomes.stream().filter(s -> s == 409).count());
        Problem result = problems.findById(p.getId()).orElseThrow();
        boolean titleRetained = result.getTitle().equals("Edited title");
        boolean limitRetained = result.getTimeLimitMs() == 2000;
        assertTrue(titleRetained ^ limitRetained, "only the successful edit must be persisted");
        assertEquals(outcomes.get(0) == 200, titleRetained);
        assertEquals(outcomes.get(1) == 200, limitRetained);
        System.out.println("Disjoint edits retained title=" + titleRetained + ", limit=" + limitRetained);
    }

    @Test void staleEditAfterDeletionFailsRatherThanResurrectingProblem() throws Exception {
        Problem p = problem(contest().getId());
        CountDownLatch read = new CountDownLatch(1), deleted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> edit = pool.submit(() -> {
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        problems.findById(p.getId()).orElseThrow(); read.countDown();
                        await(deleted);
                        adminProblems.update(p.getId(), new AdminProblemController.ProblemEditRequest(
                                "Stale edit", null, null, null));
                    });
                    return null;
                } catch (Throwable failure) { return failure; }
            });
            assertTrue(read.await(10, TimeUnit.SECONDS));
            // A committed real repository deletion while the controller editor
            // holds an old managed snapshot; no HTTP scheduling is claimed here.
            problems.deleteById(p.getId()); deleted.countDown();
            Throwable failure = edit.get(15, TimeUnit.SECONDS);
            assertNotNull(failure, "stale write must fail rather than resurrect a deleted row");
            assertFalse(problems.existsById(p.getId()));
            System.out.println("Edit/delete conflict exception: " + failure.getClass().getName());
        } finally { deleted.countDown(); pool.shutdownNow(); }
    }

    @Test void validatedAdmissionMicrosecondsSurviveDatabaseRoundTrip() {
        Contest contest = contest(); Problem problem = problem(contest.getId());
        token(Role.STUDENT);
        User user = users.findByUsername(names.get(names.size() - 1)).orElseThrow();
        java.time.Instant admitted = java.time.Instant.parse("2026-09-17T10:00:59.999999Z");
        Submission submission = new Submission(); submission.setUserId(user.getId());
        submission.setContestId(contest.getId()); submission.setProblemId(problem.getId());
        submission.setLanguage(Language.CPP17); submission.setSourceCode("int main(){}");
        submission.setQueuedAt(admitted); submission = submissions.saveAndFlush(submission);
        java.time.Instant stored = jdbc.queryForObject("SELECT queued_at FROM submissions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), submission.getId());
        assertEquals(admitted, stored);
        assertTrue(stored.isBefore(java.time.Instant.parse("2026-09-17T10:01:00Z")));
    }

    private Contest contest() {
        Contest c = new Contest(); c.setTitle(unique("race_contest"));
        c.setState(ContestState.DRAFT); c.setDurationMinutes(60); c = contests.save(c);
        contestIds.add(c.getId()); return c;
    }
    private Problem problem(long contestId) {
        Problem p = new Problem(); p.setContestId(contestId); p.setCode("A"); p.setTitle("Original");
        p.setStatementHtml("<p>Original</p>"); p.setTimeLimitMs(1000); p.setMemoryLimitKb(65536);
        p.setCheckerType(CheckerType.EXACT); return problems.save(p);
    }
    private String token(Role role) {
        User user = new User(); String name = unique("race_user"); names.add(name);
        user.setUsername(name); user.setDisplayName(name); user.setRole(role);
        user.setPasswordHash(encoder.encode("Orbit7!Lake")); users.save(user);
        String sid = UUID.randomUUID().toString(); sessions.register(name, sid, role, true);
        return jwt.generate(name, role, sid);
    }
    private int send(String method, String path, String token, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
            if (token != null) request.header("Authorization", "Bearer " + token);
            return http.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private List<Integer> burst(int count, IntFunction<Integer> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count); CyclicBarrier start = new CyclicBarrier(count);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) { int index = i;
                futures.add(pool.submit(() -> { await(start); return action.apply(index); })); }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }
    private static String unique(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race gate timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", 3306), 1000); return true; }
        catch (Exception e) { return false; }
    }
}
