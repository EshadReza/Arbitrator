package com.arbitrator.server.security;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.controller.AdminContestController;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuditLoggingIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AuditService audit;
    @Autowired UserRepository users;
    @Autowired ContestRepository contests;
    @Autowired com.arbitrator.server.repo.ProblemRepository problems;
    @Autowired com.arbitrator.server.repo.SubmissionRepository submissions;
    @Autowired com.arbitrator.server.service.ProblemPackageService packages;
    @Autowired AdminContestController adminContests;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @Autowired ActiveSessionRegistry sessions;
    @Autowired PlatformTransactionManager transactions;
    final HttpClient http = HttpClient.newHttpClient();
    String actor, token;
    Long userId, contestId;

    @BeforeEach void setup() {
        actor = "audit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = new User();
        user.setUsername(actor); user.setDisplayName("Private display");
        user.setPasswordHash(passwords.encode("Orbit7!Lake")); user.setRole(Role.ADMIN);
        userId = users.save(user).getId();
        String sid = UUID.randomUUID().toString();
        sessions.register(actor, sid, Role.ADMIN, true);
        token = jwt.generate(actor, Role.ADMIN, sid);
    }

    @AfterEach void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        if (contestId != null && contests.existsById(contestId)) adminContests.delete(contestId);
        sessions.clear(actor);
        users.deleteById(userId);
        jdbc.update("DELETE FROM audit_events WHERE actor = ? OR details LIKE ?", actor, "%" + actor + "%");
    }

    @Test void liveChangesHaveActorTargetSafeMetadataAndProtectedCursorRead() throws Exception {
        var created = request("POST", "/api/admin/contests?title=PrivateTitle&durationMinutes=60&password=PrivatePassword", "", token);
        assertEquals(200, created.statusCode(), created.body());
        contestId = json.readTree(created.body()).get("id").asLong();
        var changes = jdbc.queryForList("SELECT * FROM audit_events WHERE actor=? AND outcome='COMMITTED_CHANGE'", actor);
        assertEquals(1, changes.size());
        var change = changes.get(0);
        assertEquals("Contest", change.get("target_type"));
        assertEquals(contestId.toString(), change.get("target_id"));
        assertEquals("AdminContestController.create", change.get("action"));
        assertTrue(change.get("peer_ip").toString().contains("127.0.0.1"));
        assertTrue(change.get("details").toString().contains("durationMinutes"));
        String all = jdbc.queryForList("SELECT * FROM audit_events WHERE actor=?", actor).toString();
        for (String secret : new String[]{"PrivateTitle", "PrivatePassword", token, "passwordHash"}) assertFalse(all.contains(secret), secret);
        var page = request("GET", "/api/admin/audit?limit=1", null, token);
        assertEquals(200, page.statusCode());
        var first = json.readTree(page.body());
        assertEquals(1, first.size());
        long cursor = first.get(0).get("id").asLong();
        var next = json.readTree(request("GET", "/api/admin/audit?limit=1&before=" + cursor, null, token).body());
        assertTrue(next.get(0).get("id").asLong() < cursor);
        assertEquals(400, request("GET", "/api/admin/audit?limit=101", null, token).statusCode());
        assertEquals(401, request("GET", "/api/admin/audit", null, null).statusCode());
        assertEquals(204, request("DELETE", "/api/admin/contests/" + contestId, null, token).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor=? AND outcome='COMMITTED_CHANGE'", Integer.class, actor));
        User user = users.findById(userId).orElseThrow(); user.setRole(Role.STUDENT); users.save(user);
        String sid = UUID.randomUUID().toString(); sessions.register(actor, sid, Role.STUDENT, true);
        assertEquals(403, request("GET", "/api/admin/audit", null, jwt.generate(actor, Role.STUDENT, sid)).statusCode());
    }

    @Test void rolledBackUpdatesLeaveNeitherChangeNorAuditAndAuditFailureRollsBack() {
        Contest contest = new Contest(); contest.setTitle("Private title"); contest.setDurationMinutes(60);
        contestId = contests.save(contest).getId();
        context("TEST_UPDATE");
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            Contest current = contests.findById(contestId).orElseThrow(); current.setDurationMinutes(70);
            contests.saveAndFlush(current);
            assertEquals(1, countChanges());
            status.setRollbackOnly();
        });
        assertEquals(0, countChanges());
        assertEquals(60, contests.findById(contestId).orElseThrow().getDurationMinutes());
        context("X".repeat(121)); // DB rejects oversized audit action; entity update must roll back too.
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            Contest current = contests.findById(contestId).orElseThrow(); current.setDurationMinutes(80);
            contests.saveAndFlush(current);
        }));
        assertEquals(60, contests.findById(contestId).orElseThrow().getDurationMinutes());
        assertEquals(0, countChanges());
        context("TEST_UPDATE");
        tx.executeWithoutResult(status -> {
            Contest current = contests.findById(contestId).orElseThrow(); current.setDurationMinutes(90);
            contests.saveAndFlush(current);
        });
        var details = audit.page(Long.MAX_VALUE, 1).get(0).details().toString();
        assertTrue(details.contains("\"durationMinutes\":60"));
        assertTrue(details.contains("\"durationMinutes\":90"));
        assertFalse(details.contains("Private title"));
    }

    @Test void authenticationRecordsOutcomeWithoutPasswordOrToken() throws Exception {
        sessions.clear(actor);
        var failed = request("POST", "/api/auth/login", json.writeValueAsString(Map.of("username", actor, "password", "WrongSecret")), null);
        assertEquals(401, failed.statusCode());
        var success = request("POST", "/api/auth/login", json.writeValueAsString(Map.of("username", actor, "password", "Orbit7!Lake")), null);
        assertEquals(200, success.statusCode(), success.body());
        var rows = jdbc.queryForList("SELECT * FROM audit_events WHERE details LIKE ? ORDER BY id", "%" + actor + "%");
        assertEquals(2, rows.size());
        assertNull(rows.get(0).get("actor"));
        assertEquals("REQUEST_FAILED", rows.get(0).get("outcome"));
        assertEquals(actor, rows.get(1).get("actor"));
        assertEquals("REQUEST_COMPLETED", rows.get(1).get("outcome"));
        assertFalse(rows.toString().contains("WrongSecret")); assertFalse(rows.toString().contains("Orbit7!Lake"));
        assertFalse(rows.toString().contains(json.readTree(success.body()).get("token").asText()));
    }

    @Test void scoreOverridesAndExistingPdfReplacementAreAuditedWithoutContent() throws Exception {
        Contest contest = new Contest(); contest.setTitle("Audit fixtures");
        contestId = contests.save(contest).getId();
        var problem = new com.arbitrator.server.entity.Problem();
        problem.setContestId(contestId); problem.setCode("A"); problem.setTitle("Private problem");
        problem.setStatementHtml("Private statement"); problem.setStatementIsPdf(true);
        problem = problems.save(problem);
        var submission = new com.arbitrator.server.entity.Submission();
        submission.setContestId(contestId); submission.setProblemId(problem.getId()); submission.setUserId(userId);
        submission.setLanguage(com.arbitrator.common.enums.Language.CPP17);
        submission.setSourceCode("Private source"); submission.setCompilerOutput("Private compiler output");
        submission.setVerdict(com.arbitrator.common.enums.Verdict.WA);
        submission.setStatus(com.arbitrator.server.entity.Submission.Status.DONE);
        submission = submissions.save(submission);
        String route = "/api/admin/submissions/" + submission.getId();
        assertEquals(200, request("POST", route + "/marks?value=70", "", token).statusCode());
        assertEquals(200, request("POST", route + "/verdict?verdict=AC", "", token).statusCode());
        assertEquals(200, request("POST", "/api/admin/contests/" + contestId + "/participants/" + actor + "/penalty?delta=5", "", token).statusCode());
        assertEquals(400, request("POST", route + "/marks?value=101", "", token).statusCode());
        assertEquals(3, countChanges());
        var rows = jdbc.queryForList("SELECT details FROM audit_events WHERE actor=? AND outcome='COMMITTED_CHANGE' ORDER BY id", actor);
        var marks = json.readTree(rows.get(0).get("details").toString());
        assertTrue(marks.get("before").get("marks").isNull());
        assertEquals(70, marks.get("after").get("marks").asInt());
        var verdict = json.readTree(rows.get(1).get("details").toString());
        assertEquals("WA", verdict.get("before").get("verdict").asText());
        assertEquals("AC", verdict.get("after").get("verdict").asText());
        assertEquals(5, json.readTree(rows.get(2).get("details").toString()).get("after").get("manualPenaltyDelta").asInt());
        jdbc.update("INSERT INTO problem_statement_pdfs(problem_id,data) VALUES (?,?)", problem.getId(), "%PDF-old".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        context("TEST_PDF_REPLACE");
        packages.replaceStatementPdf(problem.getId(), "%PDF-Private uploaded bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(4, countChanges());
        assertEquals("ProblemStatementPdf", audit.page(Long.MAX_VALUE, 1).get(0).targetType());
        String all = jdbc.queryForList("SELECT * FROM audit_events WHERE actor=?", actor).toString();
        assertFalse(all.contains("Private")); assertFalse(all.contains("%PDF"));
    }

    @Test void penaltyOverflowIsRejectedWithoutChangingStoredAdjustment() throws Exception {
        Contest contest = new Contest(); contest.setTitle("Penalty boundary");
        contestId = contests.save(contest).getId();
        var problem = new com.arbitrator.server.entity.Problem();
        problem.setContestId(contestId); problem.setCode("A"); problem.setTitle("Boundary problem");
        problem.setStatementHtml("Boundary statement");
        problem = problems.save(problem);
        var submission = new com.arbitrator.server.entity.Submission();
        submission.setContestId(contestId); submission.setProblemId(problem.getId()); submission.setUserId(userId);
        submission.setLanguage(com.arbitrator.common.enums.Language.CPP17);
        submission.setSourceCode("int main() { return 0; }");
        submission.setVerdict(com.arbitrator.common.enums.Verdict.WA);
        submission.setStatus(com.arbitrator.server.entity.Submission.Status.DONE);
        long submissionId = submissions.save(submission).getId();

        String route = "/api/admin/contests/" + contestId + "/participants/" + actor + "/penalty?delta=";
        assertEquals(200, request("POST", route + Integer.MAX_VALUE, "", token).statusCode());
        assertEquals(Integer.MAX_VALUE, submissions.findById(submissionId).orElseThrow().getManualPenaltyDelta());
        assertEquals(400, request("POST", route + "1", "", token).statusCode());
        assertEquals(Integer.MAX_VALUE, submissions.findById(submissionId).orElseThrow().getManualPenaltyDelta());
        assertEquals(1, countChanges());
        assertEquals(200, request("POST", route + "-5", "", token).statusCode());
        assertEquals(Integer.MAX_VALUE - 5, submissions.findById(submissionId).orElseThrow().getManualPenaltyDelta());
    }

    @Test void nonLoopbackCannotReadHistoryEvenWithForwardedLoopbackClaim() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/admin/audit");
        request.setRemoteAddr("192.0.2.44"); request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        new com.arbitrator.server.config.LoopbackAdminFilter().doFilter(request, response, (req, res) -> fail("Remote request reached audit API"));
        assertEquals(403, response.getStatus());
    }

    @Test void failureFloodIsBoundedSummarizedAndOldRecordsExpire() {
        AtomicLong clock = new AtomicLong(60_000);
        AuditService isolated = new AuditService(jdbc, json, clock::get);
        long baseline = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM audit_events", Long.class);
        for (int i = 0; i < 205; i++) isolated.request(actor, "TEST_FAILED", "127.0.0.1", 401, null);
        assertEquals(200, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor=?", Integer.class, actor));
        clock.addAndGet(60_000);
        isolated.request(actor, "TEST_FAILED", "127.0.0.1", 401, null);
        assertEquals(201, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor=?", Integer.class, actor));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE id>? AND action='FAILED_REQUESTS_SUPPRESSED' AND details LIKE '%\"count\":5%'", Integer.class, baseline));
        jdbc.update("DELETE FROM audit_events WHERE id>? AND action='FAILED_REQUESTS_SUPPRESSED'", baseline);
        jdbc.update("UPDATE audit_events SET occurred_at=CURRENT_TIMESTAMP - INTERVAL 91 DAY WHERE actor=?", actor);
        isolated.maintain();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor=?", Integer.class, actor));
    }

    void context(String action) {
        var request = new MockHttpServletRequest();
        request.setAttribute(AuditContext.ATTRIBUTE, new AuditContext(actor, action, "127.0.0.1"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    int countChanges() { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor=? AND outcome='COMMITTED_CHANGE'", Integer.class, actor); }
    HttpResponse<String> request(String method, String path, String body, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
