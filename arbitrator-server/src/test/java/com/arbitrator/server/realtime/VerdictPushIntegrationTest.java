/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Type;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.dto.ContestJoinRequest;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.dto.VerdictEventDto;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;

/**
 * Closes the checkpoint-I2 gate (WORKFLOW_PLAN §6) and retires risk #1 in §9:
 * proves the whole live chain works, not just its pieces —
 *
 *   REST login -> JWT -> STOMP handshake authenticated with that JWT
 *   -> subscribe /user/queue/verdicts -> POST a submission
 *   -> judge runs -> verdict frame arrives on the socket (FR-15, NFR-P02).
 *
 * Requires local MySQL and g++; skipped cleanly when either is absent, so
 * `mvn test` still passes on a machine without them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class VerdictPushIntegrationTest {

    /** Verdict must arrive well inside the 30 s the SRS allows (NFR-P02). */
    private static final int VERDICT_TIMEOUT_SECONDS = 30;

    @LocalServerPort
    private int port;

    @Autowired
    private ContestRepository contests;

    @Autowired
    private ProblemRepository problems;

    @Autowired
    private UserRepository users;

    @Autowired
    private ActiveSessionRegistry activeSessions;

    private final RestTemplate rest = new RestTemplateBuilder().build();
    private final List<String> disposableUsers = new ArrayList<>();

    /** The contest {@link #ensureContestIsOpen()} put live, shared with the tests. */
    private long openContestId;

    @BeforeEach
    void ensureContestIsOpen() {
        // The seeded contest may have "ended" if the test DB is old, and
        // ContestBootReset returns every live contest to DRAFT at startup — so
        // take whatever contest exists rather than filtering by state, then make
        // the window current so the run is deterministic (BR-02 rejects late).
        Contest contest = contests.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("seeder did not create a contest"));
        contest.setState(ContestState.ACTIVE);
        contest.setStartTime(Instant.now().minusSeconds(60));
        contest.setDurationMinutes(180);
        // Contest.endTime() returns endedAt unconditionally when it is set, so
        // if this row was ever end()-ed on a previous run (any test, or manual
        // admin testing against this same DB) it stays permanently "ended" no
        // matter what startTime we set above — submissions then 403 forever.
        contest.setEndedAt(null);
        contest.setPausedAt(null);
        contest.setPausedMillis(0);
        contests.save(contest);
        openContestId = contest.getId();
    }

    @AfterEach
    void removeDisposableUsers() {
        disposableUsers.forEach(username -> {
            activeSessions.clear(username);
            users.findByUsername(username).ifPresent(users::delete);
        });
        disposableUsers.clear();
    }

    @Test
    @DisplayName("verdict reaches the submitting client over STOMP within 30s")
    void verdictIsPushedToTheSubmittingClient() throws Exception {
        // Compiling and running now happens INSIDE the Docker sandbox (S4-B1),
        // not via a host g++ — checking g++ here was stale and let this test
        // run for real (and fail for real reasons, not skip) on a machine
        // with g++ installed but Docker not yet configured. Same dockerReady
        // check SandboxExecutorTest uses.
        assumeTrue(commandWorks("docker", "version")
                        && commandWorks("docker", "image", "inspect", "arbitrator-judge:latest"),
                "docker (with arbitrator-judge image) not available");

        String token = login("alice", "alice123").token();
        join(token, openContestId);
        long problemId = firstProblemId();

        // --- connect exactly as the JavaFX client does: JWT as a handshake header
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());

        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        SynchronousQueue<VerdictEventDto> received = new SynchronousQueue<>();

        StompSession session = stomp.connectAsync(
                "ws://localhost:" + port + StompDestinations.WS_ENDPOINT,
                handshake,
                new StompSessionHandlerAdapter() { })
                .get(10, TimeUnit.SECONDS);

        assertTrue(session.isConnected(), "STOMP session should be connected");

        session.subscribe(StompDestinations.USER_QUEUE_VERDICTS,
                new StompSessionHandlerAdapter() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return VerdictEventDto.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        received.offer((VerdictEventDto) payload);
                    }
                });

        // --- submit a correct solution
        // Unique per run (a nonce comment) — the duplicate-submission guard
        // (a contestant can't submit byte-for-byte identical code for the
        // same problem twice) would otherwise reject this fixed literal on
        // any repeat run against a test DB that isn't wiped between them.
        SubmitAckDto ack = submit(token, new SubmitRequest(problemId, Language.CPP17, """
                // nonce:%s
                #include <iostream>
                int main(){ long long a,b; std::cin >> a >> b; std::cout << a + b << "\\n"; }
                """.formatted(java.util.UUID.randomUUID())));
        assertNotNull(ack, "submission should be accepted");

        // --- the actual assertion: the push arrives, unprompted, on the socket
        VerdictEventDto event = received.poll(VERDICT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertNotNull(event, "no verdict arrived over WebSocket within "
                + VERDICT_TIMEOUT_SECONDS + "s — FR-15 is not satisfied");
        assertEquals(ack.submissionId(), event.submissionId());
        assertEquals(Verdict.AC, event.verdict(),
                "correct solution should be Accepted");

        session.disconnect();
        stomp.stop();
    }

    @Test
    @DisplayName("handshake without a JWT is refused")
    void unauthenticatedHandshakeIsRejected() {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        try {
            stomp.connectAsync("ws://localhost:" + port + StompDestinations.WS_ENDPOINT,
                            new StompSessionHandlerAdapter() { })
                    .get(10, TimeUnit.SECONDS);
            throw new AssertionError(
                    "an unauthenticated WebSocket handshake must be refused (NFR-S01)");
        } catch (Exception expected) {
            // JwtHandshakeInterceptor returned 401 before the socket opened
        } finally {
            stomp.stop();
        }
    }

    @Test
    @DisplayName("forced login closes the old socket and rejects its token")
    void forcedLoginInvalidatesExistingAndFutureWebSockets() throws Exception {
        String username = "ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Orbit7!Lake";
        disposableUsers.add(username);
        LoginResponse first = register(username, password);

        WebSocketStompClient oldClient = stompClient();
        DisconnectHandler oldHandler = new DisconnectHandler();
        WebSocketStompClient replacementClient = stompClient();
        WebSocketStompClient staleReconnectClient = stompClient();
        StompSession replacementSession = null;
        try {
            StompSession oldSession = connect(oldClient, first.token(), oldHandler);
            assertTrue(oldSession.isConnected());

            LoginResponse replacement = login(username, password, true);

            assertTrue(oldHandler.disconnected.await(5, TimeUnit.SECONDS),
                    "the superseded socket must be closed immediately");
            assertFalse(oldSession.isConnected());

            HttpHeaders oldHeaders = new HttpHeaders();
            oldHeaders.setBearerAuth(first.token());
            HttpClientErrorException oldRest = assertThrows(HttpClientErrorException.class,
                    () -> rest.exchange(base() + ApiPaths.CONTESTS, HttpMethod.GET,
                            new HttpEntity<>(oldHeaders), String.class));
            assertEquals(HttpStatus.UNAUTHORIZED, oldRest.getStatusCode());

            assertThrows(Exception.class,
                    () -> connect(staleReconnectClient, first.token(), new StompSessionHandlerAdapter() { }),
                    "a superseded token must not open another WebSocket");

            replacementSession = connect(replacementClient, replacement.token(),
                    new StompSessionHandlerAdapter() { });
            assertTrue(replacementSession.isConnected(),
                    "the replacement session must still be able to connect");
        } finally {
            if (replacementSession != null && replacementSession.isConnected()) {
                replacementSession.disconnect();
            }
            oldClient.stop();
            staleReconnectClient.stop();
            replacementClient.stop();
        }
    }

    @Test
    @DisplayName("logout revokes copied tokens, closes sockets, and permits a fresh login")
    void logoutInvalidatesExistingAndFutureWebSockets() throws Exception {
        String username = "logout_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        disposableUsers.add(username);
        LoginResponse first = register(username, "Orbit7!Lake");
        WebSocketStompClient oldClient = stompClient();
        WebSocketStompClient reconnect = stompClient();
        WebSocketStompClient freshClient = stompClient();
        DisconnectHandler handler = new DisconnectHandler();
        StompSession freshSession = null;
        try {
            StompSession oldSession = connect(oldClient, first.token(), handler);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(first.token());
            assertTrue(rest.exchange(base() + ApiPaths.AUTH_LOGOUT, HttpMethod.POST,
                    new HttpEntity<>(headers), Void.class).getStatusCode().is2xxSuccessful());
            assertTrue(handler.disconnected.await(5, TimeUnit.SECONDS));
            assertFalse(oldSession.isConnected());
            HttpClientErrorException copied = assertThrows(HttpClientErrorException.class,
                    () -> rest.exchange(base() + ApiPaths.CONTESTS, HttpMethod.GET,
                            new HttpEntity<>(headers), String.class));
            assertEquals(HttpStatus.UNAUTHORIZED, copied.getStatusCode());
            assertThrows(Exception.class,
                    () -> connect(reconnect, first.token(), new StompSessionHandlerAdapter() { }));
            LoginResponse fresh = login(username, "Orbit7!Lake");
            assertNotEquals(first.token(), fresh.token());
            freshSession = connect(freshClient, fresh.token(), new StompSessionHandlerAdapter() { });
            assertTrue(freshSession.isConnected());
            assertThrows(HttpClientErrorException.class,
                    () -> rest.exchange(base() + ApiPaths.CONTESTS, HttpMethod.GET,
                            new HttpEntity<>(headers), String.class));
        } finally {
            if (freshSession != null && freshSession.isConnected()) freshSession.disconnect();
            oldClient.stop(); reconnect.stop(); freshClient.stop();
        }
    }

    // ------------------------------------------------------------------

    private LoginResponse login(String user, String password) {
        return rest.postForObject(base() + ApiPaths.AUTH_LOGIN,
                new LoginRequest(user, null, password), LoginResponse.class);
    }

    private LoginResponse login(String user, String password, boolean force) {
        return rest.postForObject(base() + ApiPaths.AUTH_LOGIN,
                new LoginRequest(user, null, password, null, force), LoginResponse.class);
    }

    private LoginResponse register(String user, String password) {
        return rest.postForObject(base() + ApiPaths.AUTH_REGISTER,
                new LoginRequest(user, "WebSocket Probe", password), LoginResponse.class);
    }

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    private StompSession connect(WebSocketStompClient client, String token,
                                 StompSessionHandlerAdapter handler) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(token);
        return client.connectAsync("ws://localhost:" + port + StompDestinations.WS_ENDPOINT,
                headers, handler).get(10, TimeUnit.SECONDS);
    }

    private SubmitAckDto submit(String token, SubmitRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.postForObject(base() + ApiPaths.SUBMISSIONS,
                new HttpEntity<>(request, headers), SubmitAckDto.class);
    }

    private void join(String token, long contestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        rest.postForObject(base() + ApiPaths.CONTESTS + "/" + contestId + "/join",
                new HttpEntity<>(new ContestJoinRequest(null), headers), Object.class);
    }

    private long firstProblemId() {
        // Must be the very contest @BeforeEach opened, not "the newest live
        // one" — with several contests in the DB those are different rows and
        // the submission then lands outside the open window (403).
        return problems.findByContestIdOrderByOrderingAscCodeAsc(openContestId).stream()
                .map(Problem::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("seeder did not create a problem"));
    }

    private String base() {
        return "http://localhost:" + port;
    }

    /** JUnit condition — keeps the suite green on a machine with no database. */
    @SuppressWarnings("unused")
    static boolean mysqlIsReachable() {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("localhost", 3306), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean commandWorks(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class DisconnectHandler extends StompSessionHandlerAdapter {
        private final CountDownLatch disconnected = new CountDownLatch(1);

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            disconnected.countDown();
        }
    }
}
