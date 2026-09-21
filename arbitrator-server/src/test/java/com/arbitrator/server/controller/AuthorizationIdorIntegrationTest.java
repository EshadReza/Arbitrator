/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Clarification;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Material;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.TestCase;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ClarificationRepository;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.MaterialRepository;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.repo.UserRepository;

/** Real HTTP/STOMP object-authorization matrix using attacker-controlled IDs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class AuthorizationIdorIntegrationTest {

    private static final String SECRET_SOURCE = "// owner-only-source\nint main() { return 0; }";
    private static final String SECRET_SOURCE_MARKER = "owner-only-source";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private ContestRepository contests;
    @Autowired private ProblemRepository problems;
    @Autowired private SubmissionRepository submissions;
    @Autowired private TestCaseRepository testCases;
    @Autowired private MaterialRepository materials;
    @Autowired private ClarificationRepository clarifications;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminContestController adminContests;

    private final List<Long> contestIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (long contestId : List.copyOf(contestIds)) {
            if (contests.existsById(contestId)) {
                adminContests.delete(contestId);
            }
        }
        for (long userId : userIds) {
            users.findById(userId).ifPresent(users::delete);
        }
    }

    @Test
    void changingSubmissionIdCannotReadAnotherStudentsSourceOrTests() {
        Contest contest = activeContest("submission-owner");
        contest.setShowTestCases(true);
        contests.save(contest);
        Problem problem = problem(contest, "A");
        Student owner = student("owner");
        Student attacker = student("attacker");
        grant(contest, owner.user());
        grant(contest, attacker.user());

        TestCase test = new TestCase();
        test.setProblemId(problem.getId());
        test.setIdx(1);
        test.setInputData("classified-input");
        test.setExpectedOutput("classified-answer");
        testCases.save(test);

        Submission submission = submission(contest, problem, owner.user());
        jdbc.update("""
                INSERT INTO submission_results
                    (submission_id, test_index, verdict, exec_time_ms, peak_memory_kb, actual_output)
                VALUES (?, 1, 'WA', 5, 1024, 'wrong-answer')
                """, submission.getId());

        ResponseEntity<String> stolenSource = get(attacker.token(),
                "/api/submissions/" + submission.getId() + "/source");
        ResponseEntity<String> stolenTests = get(attacker.token(),
                "/api/submissions/" + submission.getId() + "/tests");
        assertStatus(HttpStatus.FORBIDDEN, stolenSource);
        assertStatus(HttpStatus.FORBIDDEN, stolenTests);
        assertFalse(stolenSource.getBody() != null
                && stolenSource.getBody().contains(SECRET_SOURCE_MARKER));
        assertFalse(stolenTests.getBody() != null && stolenTests.getBody().contains("classified-answer"));

        assertBodyContains(get(owner.token(),
                "/api/submissions/" + submission.getId() + "/source"), SECRET_SOURCE_MARKER);
        assertBodyContains(get(owner.token(),
                "/api/submissions/" + submission.getId() + "/tests"), "classified-answer");

        String adminToken = login("admin", "admin123", true).token();
        assertBodyContains(get(adminToken,
                "/api/admin/submissions/" + submission.getId() + "/source"), SECRET_SOURCE_MARKER);
        assertBodyContains(get(adminToken,
                "/api/admin/submissions/" + submission.getId() + "/tests"), "classified-answer");
    }

    @Test
    void objectIdsCannotCrossAContestGrantBoundary() {
        Student student = student("contest-boundary");
        Contest allowed = activeContest("allowed");
        Contest denied = activeContest("denied");
        grant(allowed, student.user());
        Problem deniedProblem = problem(denied, "Z");

        Material material = new Material();
        material.setContestId(denied.getId());
        material.setFilename("secret.txt");
        material.setStoredName(UUID.randomUUID() + "-secret.txt");
        material.setContentType(MediaType.TEXT_PLAIN_VALUE);
        material.setSizeBytes(6);
        materials.save(material);

        assertStatus(HttpStatus.OK, get(student.token(), "/api/contests/" + allowed.getId()));
        assertStatus(HttpStatus.FORBIDDEN, get(student.token(), "/api/contests/" + denied.getId()));
        assertStatus(HttpStatus.FORBIDDEN, get(student.token(),
                "/api/problems/" + deniedProblem.getId()));
        assertStatus(HttpStatus.FORBIDDEN, get(student.token(),
                "/api/problems/" + deniedProblem.getId() + "/statement.pdf"));
        assertStatus(HttpStatus.FORBIDDEN, get(student.token(),
                "/api/materials/" + material.getId() + "/download"));
        assertStatus(HttpStatus.FORBIDDEN, get(student.token(),
                "/api/materials?contestId=" + denied.getId()));
        assertStatus(HttpStatus.FORBIDDEN, get(student.token(),
                "/api/announcements?contestId=" + denied.getId()));
        assertStatus(HttpStatus.FORBIDDEN, get(student.token(),
                "/api/clarifications?contestId=" + denied.getId()));
        assertStatus(HttpStatus.FORBIDDEN, get(student.token(),
                "/api/contests/" + denied.getId() + "/participants/anyone/problems/Z/attempts"));
    }

    @Test
    void privateClarificationsAreVisibleOnlyToTheirOwnerAndAdmins() {
        Contest contest = activeContest("private-clarifications");
        Student alice = student("clarification-alice");
        Student bob = student("clarification-bob");
        grant(contest, alice.user());
        grant(contest, bob.user());

        clarification(contest, alice.user(), "alice-private-question");
        clarification(contest, bob.user(), "bob-private-question");

        ResponseEntity<String> aliceView = get(alice.token(),
                "/api/clarifications?contestId=" + contest.getId());
        assertBodyContains(aliceView, "alice-private-question");
        assertFalse(aliceView.getBody().contains("bob-private-question"));

        ResponseEntity<String> bobView = get(bob.token(),
                "/api/clarifications?contestId=" + contest.getId());
        assertBodyContains(bobView, "bob-private-question");
        assertFalse(bobView.getBody().contains("alice-private-question"));

        String adminToken = login("admin", "admin123", true).token();
        ResponseEntity<String> adminView = get(adminToken,
                "/api/admin/contests/" + contest.getId() + "/clarifications");
        assertBodyContains(adminView, "alice-private-question");
        assertTrue(adminView.getBody().contains("bob-private-question"));
    }

    @Test
    void everyContestTopicRejectsAChangedForeignContestId() throws Exception {
        Student student = student("stomp-idor");
        Contest allowed = activeContest("stomp-allowed");
        Contest denied = activeContest("stomp-denied");
        grant(allowed, student.user());

        List<String> deniedTopics = List.of(
                StompDestinations.contestState(denied.getId()),
                StompDestinations.contestLeaderboard(denied.getId()),
                StompDestinations.contestAnnouncements(denied.getId()),
                StompDestinations.contestClarifications(denied.getId()),
                StompDestinations.contestMaterials(denied.getId()));

        for (String destination : deniedTopics) {
            RecordingSessionHandler handler = new RecordingSessionHandler();
            WebSocketStompClient client = stompClient();
            try {
                StompSession session = connect(client, student.token(), handler);
                session.subscribe(destination, handler);
                assertNotNull(handler.errors.poll(5, TimeUnit.SECONDS),
                        "foreign contest topic must be denied: " + destination);
            } finally {
                client.stop();
            }
        }
    }

    private Contest activeContest(String label) {
        Contest contest = new Contest();
        contest.setTitle(label + '-' + suffix());
        contest.setState(ContestState.ACTIVE);
        contest.setStartTime(Instant.now().minusSeconds(30));
        contest.setDurationMinutes(120);
        contests.save(contest);
        contestIds.add(contest.getId());
        return contest;
    }

    private Problem problem(Contest contest, String code) {
        Problem problem = new Problem();
        problem.setContestId(contest.getId());
        problem.setCode(code);
        problem.setTitle("Protected " + code);
        problem.setStatementHtml("<p>protected-statement</p>");
        problem.setOrdering(1);
        problems.save(problem);
        return problem;
    }

    private Student student(String label) {
        String username = label + '_' + suffix();
        LoginResponse login = rest.postForObject(base() + ApiPaths.AUTH_REGISTER,
                new LoginRequest(username, label, "Orbit7!Lake"), LoginResponse.class);
        User user = users.findByUsername(username).orElseThrow();
        userIds.add(user.getId());
        return new Student(login.token(), user);
    }

    private void grant(Contest contest, User user) {
        jdbc.update("INSERT INTO contest_access_grants (contest_id, user_id) VALUES (?, ?)",
                contest.getId(), user.getId());
    }

    private Submission submission(Contest contest, Problem problem, User owner) {
        Submission submission = new Submission();
        submission.setContestId(contest.getId());
        submission.setProblemId(problem.getId());
        submission.setUserId(owner.getId());
        submission.setLanguage(Language.CPP17);
        submission.setSourceCode(SECRET_SOURCE);
        submission.setStatus(Submission.Status.DONE);
        submission.setVerdict(Verdict.WA);
        submission.setFailedTestIndex(1);
        submission.setJudgedAt(Instant.now());
        return submissions.save(submission);
    }

    private void clarification(Contest contest, User owner, String question) {
        Clarification clarification = new Clarification();
        clarification.setContestId(contest.getId());
        clarification.setUserId(owner.getId());
        clarification.setQuestion(question);
        clarification.setPublic(false);
        clarifications.save(clarification);
    }

    private LoginResponse login(String username, String password, boolean force) {
        return rest.postForObject(base() + ApiPaths.AUTH_LOGIN,
                new LoginRequest(username, null, password, null, force), LoginResponse.class);
    }

    private ResponseEntity<String> get(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);
    }

    private void assertStatus(HttpStatus expected, ResponseEntity<?> actual) {
        assertEquals(expected, actual.getStatusCode(),
                actual.getBody() == null ? "" : actual.getBody().toString());
    }

    private void assertBodyContains(ResponseEntity<String> response, String expected) {
        assertStatus(HttpStatus.OK, response);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains(expected), response.getBody());
    }

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        return client;
    }

    private StompSession connect(WebSocketStompClient client, String token,
                                 RecordingSessionHandler handler) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(token);
        return client.connectAsync("ws://localhost:" + port + StompDestinations.WS_ENDPOINT,
                headers, handler).get(10, TimeUnit.SECONDS);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private record Student(String token, User user) {
    }

    private static final class RecordingSessionHandler extends StompSessionHandlerAdapter {
        final LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleException(StompSession session, StompCommand command,
                                    StompHeaders headers, byte[] payload, Throwable exception) {
            errors.offer(exception);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.offer(exception);
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String message = headers.getFirst("message");
            if (message != null) {
                errors.offer(new IllegalStateException(message));
            }
        }
    }
}
