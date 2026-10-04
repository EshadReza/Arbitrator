package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

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
import org.springframework.test.context.ActiveProfiles;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Live student attempts to inject judge/score fields and call admin mutations. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class ScoreManipulationAttackIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Autowired private ContestRepository contests;
    @Autowired private ProblemRepository problems;
    @Autowired private SubmissionRepository submissions;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminContestController adminContests;
    @Autowired private ActiveSessionRegistry sessions;

    private Long contestId;
    private Long userId;
    private String username;

    @AfterEach
    void cleanup() {
        if (contestId != null && contests.existsById(contestId)) adminContests.delete(contestId);
        if (username != null) sessions.clear(username);
        if (userId != null) users.findById(userId).ifPresent(users::delete);
    }

    @Test
    void forgedScoreFieldsAndStudentAdminCallsCannotChangeJudgeOrLeaderboard() throws Exception {
        username = "score_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LoginResponse login = rest.postForObject(base() + ApiPaths.AUTH_REGISTER,
                new LoginRequest(username, "Score Attack", "Orbit7!Lake"), LoginResponse.class);
        assertNotNull(login);
        User student = users.findByUsername(username).orElseThrow();
        userId = student.getId();

        Contest contest = new Contest();
        contest.setTitle("score-attack-" + UUID.randomUUID());
        contest.setState(ContestState.ACTIVE);
        contest.setStartTime(Instant.now().minusSeconds(30));
        contest.setDurationMinutes(60);
        contest = contests.save(contest);
        contestId = contest.getId();
        jdbc.update("INSERT INTO contest_access_grants (contest_id, user_id) VALUES (?, ?)",
                contestId, userId);

        Problem problem = new Problem();
        problem.setContestId(contestId);
        problem.setCode("A");
        problem.setTitle("Score integrity");
        problem.setStatementHtml("<p>Compile something</p>");
        problem = problems.save(problem);

        String[] invalidIds = {problem.getId() + ".5", "\"" + problem.getId() + "\"",
                "0", "-1", "9223372036854775808", "null"};
        for (int i = 0; i < invalidIds.length; i++) {
            String invalidId = invalidIds[i];
            String body = "{\"problemId\":" + invalidId
                    + ",\"language\":\"CPP17\",\"sourceCode\":\"int main(){}\"}";
            // Keep the total submission probes below the production per-account rate limit.
            if (i < 2) {
                assertEquals(HttpStatus.BAD_REQUEST, post(login.token(), "/api/submissions", body).getStatusCode(),
                        "submission problemId=" + invalidId);
            }
            assertEquals(HttpStatus.BAD_REQUEST, post(login.token(), "/api/run", body).getStatusCode(),
                    "custom-run problemId=" + invalidId);
        }
        assertTrue(submissions.findByUserIdAndActiveTrueOrderByQueuedAtDesc(userId).isEmpty());

        String invalidSource = "int main( {";
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("problemId", problem.getId());
        forged.put("language", "CPP17");
        forged.put("sourceCode", invalidSource);
        forged.put("userId", 1);
        forged.put("contestId", -999);
        forged.put("verdict", "AC");
        forged.put("score", 100);
        forged.put("marks", 100);
        forged.put("manualPenaltyDelta", -999999);
        forged.put("execTimeMs", 1);
        forged.put("peakMemoryKb", 1);
        forged.put("failedTestIndex", -1);
        forged.put("queuedAt", "2000-01-01T00:00:00Z");
        forged.put("status", "DONE");
        forged.put("active", false);

        Instant before = Instant.now().minusSeconds(1);
        ResponseEntity<String> rejected = post(login.token(), "/api/submissions", json.writeValueAsString(forged));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode(), rejected.getBody());
        assertTrue(submissions.findByUserIdAndActiveTrueOrderByQueuedAtDesc(userId).isEmpty());

        // The rejection is about unexpected fields, not ordinary submissions.
        ResponseEntity<String> accepted = post(login.token(), "/api/submissions",
                json.writeValueAsString(Map.of("problemId", problem.getId(), "language", "CPP17",
                        "sourceCode", invalidSource)));
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode(), accepted.getBody());
        JsonNode ack = json.readTree(accepted.getBody());
        long submissionId = ack.get("submissionId").asLong();

        Submission judged = awaitDone(submissionId);
        assertEquals(userId, judged.getUserId());
        assertEquals(contestId, judged.getContestId());
        assertEquals(problem.getId(), judged.getProblemId());
        assertEquals(invalidSource, judged.getSourceCode());
        assertEquals(Verdict.CE, judged.getVerdict());
        assertNull(judged.getMarks());
        assertEquals(0, judged.getManualPenaltyDelta());
        assertTrue(judged.isActive());
        assertTrue(judged.getQueuedAt().isAfter(before));
        assertNotEquals(1, judged.getExecTimeMs());
        assertNotEquals(1, judged.getPeakMemoryKb());

        // Student JWT, valid object IDs, and loopback request: role gate alone
        // must still reject every privileged score mutation.
        assertForbidden(post(login.token(), "/api/admin/submissions/" + submissionId
                + "/marks?value=100", ""));
        assertForbidden(post(login.token(), "/api/admin/contests/" + contestId
                + "/participants/" + username + "/marks?value=100", ""));
        assertForbidden(post(login.token(), "/api/admin/submissions/" + submissionId
                + "/verdict?verdict=AC", ""));
        assertForbidden(post(login.token(), "/api/admin/contests/" + contestId
                + "/participants/" + username + "/penalty?delta=-999", ""));

        Submission unchanged = submissions.findById(submissionId).orElseThrow();
        assertEquals(Verdict.CE, unchanged.getVerdict());
        assertNull(unchanged.getMarks());
        assertEquals(0, unchanged.getManualPenaltyDelta());

        ResponseEntity<LeaderboardDto> board = rest.exchange(base() + "/api/leaderboard",
                HttpMethod.GET, authorized(login.token(), null), LeaderboardDto.class);
        assertEquals(HttpStatus.OK, board.getStatusCode());
        var row = board.getBody().rows().stream()
                .filter(value -> value.username().equals(username)).findFirst().orElseThrow();
        assertEquals(0, row.solved());
        assertEquals(0, row.penaltyMinutes());
    }

    private Submission awaitDone(long id) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Submission current = submissions.findById(id).orElseThrow();
            if (current.getStatus() == Submission.Status.DONE) return current;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        fail("judge did not finish forged-field submission within 15 seconds");
        return null;
    }

    private ResponseEntity<String> post(String token, String path, String body) {
        return rest.exchange(base() + path, HttpMethod.POST, authorized(token, body), String.class);
    }

    private HttpEntity<?> authorized(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static void assertForbidden(ResponseEntity<?> response) {
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                response.getBody() == null ? "" : response.getBody().toString());
    }

    private String base() { return "http://localhost:" + port; }

    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 500);
            return true;
        } catch (Exception ignored) { return false; }
    }
}
