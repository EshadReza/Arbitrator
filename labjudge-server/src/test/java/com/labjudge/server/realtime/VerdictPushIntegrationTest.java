package com.labjudge.server.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Type;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

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
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.labjudge.common.api.ApiPaths;
import com.labjudge.common.api.StompDestinations;
import com.labjudge.common.dto.LoginRequest;
import com.labjudge.common.dto.LoginResponse;
import com.labjudge.common.dto.SubmitAckDto;
import com.labjudge.common.dto.SubmitRequest;
import com.labjudge.common.dto.VerdictEventDto;
import com.labjudge.common.enums.ContestState;
import com.labjudge.common.enums.Language;
import com.labjudge.common.enums.Verdict;
import com.labjudge.server.entity.Contest;
import com.labjudge.server.entity.Problem;
import com.labjudge.server.repo.ContestRepository;
import com.labjudge.server.repo.ProblemRepository;

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

    private final RestTemplate rest = new RestTemplateBuilder().build();

    @BeforeEach
    void ensureContestIsOpen() {
        // The seeded contest may have "ended" if the test DB is old; make the
        // window current so the run is deterministic (BR-02 would reject late).
        Contest contest = contests.findFirstByStateNotOrderByIdDesc(ContestState.DRAFT)
                .orElseThrow(() -> new IllegalStateException("seeder did not create a contest"));
        contest.setState(ContestState.ACTIVE);
        contest.setStartTime(Instant.now().minusSeconds(60));
        contest.setDurationMinutes(180);
        contests.save(contest);
    }

    @Test
    @DisplayName("verdict reaches the submitting client over STOMP within 30s")
    void verdictIsPushedToTheSubmittingClient() throws Exception {
        assumeTrue(commandWorks("g++", "--version"), "g++ not on PATH");

        String token = login("alice", "alice123").token();
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
        SubmitAckDto ack = submit(token, new SubmitRequest(problemId, Language.CPP17, """
                #include <iostream>
                int main(){ long long a,b; std::cin >> a >> b; std::cout << a + b << "\\n"; }
                """));
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

    // ------------------------------------------------------------------

    private LoginResponse login(String user, String password) {
        return rest.postForObject(base() + ApiPaths.AUTH_LOGIN,
                new LoginRequest(user, null, password), LoginResponse.class);
    }

    private SubmitAckDto submit(String token, SubmitRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.postForObject(base() + ApiPaths.SUBMISSIONS,
                new HttpEntity<>(request, headers), SubmitAckDto.class);
    }

    private long firstProblemId() {
        Contest contest = contests.findFirstByStateNotOrderByIdDesc(ContestState.DRAFT)
                .orElseThrow();
        return problems.findByContestIdOrderByOrderingAscCodeAsc(contest.getId()).stream()
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
}
