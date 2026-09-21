/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.ContestJoinRequest;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.CustomRunRequest;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Language;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.UserRepository;

/** Full HTTP/STOMP regression coverage for the contest password boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class ContestAccessIntegrationTest {

    private static final String CONTEST_PASSWORD = "open-sesame";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private ContestRepository contests;
    @Autowired private ProblemRepository problems;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SimpMessagingTemplate messaging;
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
    void passwordGrantClosesDirectRestRoutesAndSurvivesRefresh() {
        Fixture f = fixture(ContestState.LOBBY);

        assertStatus(HttpStatus.FORBIDDEN, get(f.studentToken(), "/api/contests/" + f.contestId()));
        assertStatus(HttpStatus.FORBIDDEN, get(f.studentToken(), "/api/problems/" + f.problemId()));
        assertStatus(HttpStatus.FORBIDDEN,
                get(f.studentToken(), "/api/problems/" + f.problemId() + "/statement.pdf"));
        assertStatus(HttpStatus.FORBIDDEN,
                get(f.studentToken(), "/api/materials?contestId=" + f.contestId()));
        assertStatus(HttpStatus.FORBIDDEN,
                get(f.studentToken(), "/api/announcements?contestId=" + f.contestId()));
        assertStatus(HttpStatus.FORBIDDEN,
                get(f.studentToken(), "/api/clarifications?contestId=" + f.contestId()));
        assertStatus(HttpStatus.FORBIDDEN,
                get(f.studentToken(), "/api/contests/" + f.contestId() + "/clarification-privacy"));
        assertStatus(HttpStatus.FORBIDDEN, get(f.studentToken(), "/api/leaderboard"));
        assertStatus(HttpStatus.FORBIDDEN,
                get(f.studentToken(), "/api/contests/" + f.contestId()
                        + "/participants/alice/problems/A/attempts"));
        assertStatus(HttpStatus.FORBIDDEN,
                post(f.studentToken(), "/api/run", new CustomRunRequest(
                        f.problemId(), Language.PYTHON310, "print(input())", "x")));
        assertStatus(HttpStatus.FORBIDDEN,
                post(f.studentToken(), "/api/submissions", new SubmitRequest(
                        f.problemId(), Language.PYTHON310, "print(1)")));

        assertStatus(HttpStatus.FORBIDDEN, join(f.studentToken(), f.contestId(), null));
        assertStatus(HttpStatus.FORBIDDEN, join(f.studentToken(), f.contestId(), "wrong"));
        assertStatus(HttpStatus.OK, join(f.studentToken(), f.contestId(), CONTEST_PASSWORD));

        // Refresh contains no password: the persisted grant is now the proof.
        ResponseEntity<ContestStateDto> refreshed = rest.exchange(
                base() + "/api/contests/" + f.contestId(), HttpMethod.GET,
                authorized(f.studentToken(), null), ContestStateDto.class);
        assertEquals(HttpStatus.OK, refreshed.getStatusCode());
        assertEquals(f.contestId(), refreshed.getBody().contestId());

        // Entry is allowed in the lobby, but problem statements/codes remain hidden.
        assertStatus(HttpStatus.FORBIDDEN, get(f.studentToken(), "/api/problems/" + f.problemId()));
        ResponseEntity<String> problemList = get(f.studentToken(), "/api/problems");
        assertEquals(HttpStatus.OK, problemList.getStatusCode());
        assertEquals("[]", problemList.getBody());
        ResponseEntity<LeaderboardDto> lobbyBoard = rest.exchange(base() + "/api/leaderboard",
                HttpMethod.GET, authorized(f.studentToken(), null), LeaderboardDto.class);
        assertEquals(HttpStatus.OK, lobbyBoard.getStatusCode());
        assertTrue(lobbyBoard.getBody().problemCodes().isEmpty());
        assertTrue(lobbyBoard.getBody().rows().isEmpty());

        Contest active = contests.findById(f.contestId()).orElseThrow();
        active.setState(ContestState.ACTIVE);
        active.setStartTime(Instant.now().minusSeconds(30));
        contests.save(active);

        assertStatus(HttpStatus.OK, get(f.studentToken(), "/api/problems/" + f.problemId()));
        assertStatus(HttpStatus.OK,
                get(f.studentToken(), "/api/materials?contestId=" + f.contestId()));
    }

    @Test
    void adminsBypassStudentGrantsAndLobbyReleaseGate() {
        Fixture f = fixture(ContestState.LOBBY);
        String adminToken = login("admin", "admin123", true).token();

        assertStatus(HttpStatus.OK, get(adminToken, "/api/contests/" + f.contestId()));
        assertStatus(HttpStatus.OK, get(adminToken, "/api/problems/" + f.problemId()));
    }

    @Test
    void contestTopicSubscriptionRequiresTheSameGrant() throws Exception {
        Fixture f = fixture(ContestState.ACTIVE);
        RecordingSessionHandler deniedHandler = new RecordingSessionHandler();
        WebSocketStompClient deniedClient = stompClient();
        StompSession denied = connect(deniedClient, f.studentToken(), deniedHandler);
        denied.subscribe(StompDestinations.contestState(f.contestId()), deniedHandler);
        assertNotNull(deniedHandler.errors.poll(5, TimeUnit.SECONDS),
                "an unadmitted student subscription must receive a STOMP error");
        deniedClient.stop();

        assertStatus(HttpStatus.OK, join(f.studentToken(), f.contestId(), CONTEST_PASSWORD));
        RecordingSessionHandler allowedHandler = new RecordingSessionHandler();
        WebSocketStompClient allowedClient = stompClient();
        StompSession allowed = connect(allowedClient, f.studentToken(), allowedHandler);
        allowed.subscribe(StompDestinations.contestState(f.contestId()), allowedHandler);

        String received = null;
        for (int attempt = 0; attempt < 3 && received == null; attempt++) {
            messaging.convertAndSend(StompDestinations.contestState(f.contestId()), "granted");
            received = allowedHandler.frames.poll(1, TimeUnit.SECONDS);
        }
        assertEquals("granted", received);
        assertTrue(allowed.isConnected());
        assertTrue(allowedHandler.errors.isEmpty());
        allowed.disconnect();
        allowedClient.stop();
    }

    @Test
    void deletingContestCascadesItsAccessGrants() {
        Fixture f = fixture(ContestState.LOBBY);
        assertStatus(HttpStatus.OK, join(f.studentToken(), f.contestId(), CONTEST_PASSWORD));
        assertEquals(1, grantCount(f.contestId()));

        jdbc.update("DELETE FROM problems WHERE contest_id = ?", f.contestId());
        jdbc.update("DELETE FROM contests WHERE id = ?", f.contestId());
        contestIds.remove(f.contestId());
        assertEquals(0, grantCount(f.contestId()));
    }

    private Fixture fixture(ContestState state) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "gate_" + suffix;
        LoginResponse student = rest.postForObject(base() + ApiPaths.AUTH_REGISTER,
                new LoginRequest(username, "Gate Test", "Orbit7!Lake"), LoginResponse.class);
        User user = users.findByUsername(username).orElseThrow();
        userIds.add(user.getId());

        Contest contest = new Contest();
        contest.setTitle("contest-access-test-" + suffix);
        contest.setState(state);
        contest.setDurationMinutes(120);
        contest.setPasswordHash(encoder.encode(CONTEST_PASSWORD));
        if (state.releasesProblems()) {
            contest.setStartTime(Instant.now().minusSeconds(30));
        }
        contests.save(contest);
        contestIds.add(contest.getId());

        Problem problem = new Problem();
        problem.setContestId(contest.getId());
        problem.setCode("A");
        problem.setTitle("Protected statement");
        problem.setStatementHtml("<p>secret</p>");
        problem.setOrdering(1);
        problems.save(problem);
        return new Fixture(student.token(), contest.getId(), problem.getId());
    }

    private LoginResponse login(String username, String password, boolean force) {
        return rest.postForObject(base() + ApiPaths.AUTH_LOGIN,
                new LoginRequest(username, null, password, null, force), LoginResponse.class);
    }

    private ResponseEntity<String> join(String token, long contestId, String password) {
        return post(token, "/api/contests/" + contestId + "/join",
                new ContestJoinRequest(password));
    }

    private ResponseEntity<String> get(String token, String path) {
        return rest.exchange(base() + path, HttpMethod.GET, authorized(token, null), String.class);
    }

    private ResponseEntity<String> post(String token, String path, Object body) {
        return rest.exchange(base() + path, HttpMethod.POST, authorized(token, body), String.class);
    }

    private HttpEntity<?> authorized(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private void assertStatus(HttpStatus expected, ResponseEntity<?> actual) {
        assertEquals(expected, actual.getStatusCode(), actual.getBody() == null ? "" : actual.getBody().toString());
    }

    private int grantCount(long contestId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM contest_access_grants WHERE contest_id = ?",
                Integer.class, contestId);
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

    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private record Fixture(String studentToken, long contestId, long problemId) { }

    private static final class RecordingSessionHandler extends StompSessionHandlerAdapter {
        final LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (headers.getFirst("message") != null) {
                errors.offer(new IllegalStateException(headers.getFirst("message")));
            } else if (payload != null) {
                frames.offer(payload.toString());
            }
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
    }
}
