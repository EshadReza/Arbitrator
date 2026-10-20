/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.arbitrator.common.enums.CheckerType;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Role;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Announcement;
import com.arbitrator.server.entity.Clarification;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.TestCase;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.AnnouncementRepository;
import com.arbitrator.server.repo.ClarificationRepository;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtService;
import com.arbitrator.server.service.AArchiveExportService;
import com.arbitrator.server.service.MaterialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** End-to-end "Export for AArchive" against real MySQL: contents, integrity, secrets and access. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class AArchiveExportIntegrationTest {

    private static final Path MATERIALS_ROOT;

    static {
        try {
            MATERIALS_ROOT = Files.createTempDirectory("aarchive-materials-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void materialsRoot(DynamicPropertyRegistry registry) {
        registry.add("arbitrator.materials.root", MATERIALS_ROOT::toString);
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContestRepository contests;
    @Autowired private ProblemRepository problems;
    @Autowired private TestCaseRepository testCases;
    @Autowired private SubmissionRepository submissions;
    @Autowired private UserRepository users;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private ClarificationRepository clarifications;
    @Autowired private MaterialService materials;
    @Autowired private AdminContestController adminContests;
    @Autowired private ActiveSessionRegistry sessions;
    @Autowired private JwtService jwt;

    private Long contestId;
    private User admin;
    private User student;

    @AfterEach
    void cleanup() {
        if (contestId != null && contests.existsById(contestId)) adminContests.delete(contestId);
        if (contestId != null) {
            jdbc.update("DELETE FROM audit_events WHERE actor = 'aarchive-test'");
        }
        for (User u : new User[] {admin, student}) {
            if (u != null) {
                sessions.clear(u.getUsername());
                users.findById(u.getId()).ifPresent(users::delete);
            }
        }
    }

    @Test
    void archiveHoldsEveryStoredFactWithIntactFilesAndNoCredentials() throws Exception {
        admin = user("aa-admin-", Role.ADMIN);
        student = user("aa-student-", Role.STUDENT);
        String adminToken = token(admin);

        // Whole seconds: contests.start_time is a DATETIME, and MySQL rounds fractions.
        Instant start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(3600);
        Contest contest = new Contest();
        contest.setTitle("AArchive export " + UUID.randomUUID());
        contest.setState(ContestState.ENDED);
        contest.setStartTime(start);
        contest.setDurationMinutes(90);
        contest.setPausedMillis(120_000);
        contest.setEndedAt(start.plusSeconds(5400));
        contest.setPasswordHash("$2a$12$contestPasswordHashMustNeverBeExported000000000000000");
        contest.setShowTestCases(true);
        contest = contests.save(contest);
        contestId = contest.getId();
        // As ContestAccessService.join does: granted_at comes from the column default.
        jdbc.update("INSERT INTO contest_access_grants (contest_id, user_id) VALUES (?, ?)",
                contestId, student.getId());

        Problem html = problem("A", "Sum", 0, "<p>Add <b>two</b> numbers</p><script>alert(1)</script>");
        html.setCheckerType(CheckerType.CUSTOM);
        html.setCheckerSource("int main(){return 0;}");
        html = problems.save(html);
        TestCase t1 = testCase(html, 1, "1 2\n", "3\n");
        testCase(html, 2, "ünïcödé 5 5\n", "10\n");

        Problem pdf = problem("B", "Paper", 1, "<p>See PDF</p>");
        pdf.setStatementIsPdf(true);
        pdf = problems.save(pdf);
        byte[] pdfBytes = "%PDF-1.4 fake statement".getBytes(StandardCharsets.US_ASCII);
        jdbc.update("INSERT INTO problem_statement_pdfs (problem_id, data) VALUES (?, ?)", pdf.getId(), pdfBytes);

        // A WA at minute 10 then an AC at minute 30: penalty 30 + 20 = 50.
        Submission wa = submission(html, start.plusSeconds(600), Verdict.WA, 2);
        Submission ac = submission(html, start.plusSeconds(1800), Verdict.AC, -1);
        ac.setMarks(87);
        ac.setManualPenaltyDelta(-5);
        submissions.save(ac);
        jdbc.update("INSERT INTO submission_results (submission_id, test_index, verdict, exec_time_ms, "
                + "peak_memory_kb, actual_output) VALUES (?, ?, 'AC', 12, 3000, '3\n'), (?, ?, 'WA', 11, 2900, '9\n')",
                wa.getId(), 1, wa.getId(), 2);

        Announcement a = new Announcement();
        a.setContestId(contestId);
        a.setBody("<p>Clarified limits</p><img src=x onerror=alert(1)>");
        announcements.save(a);

        Clarification c = new Clarification();
        c.setContestId(contestId);
        c.setProblemId(html.getId());
        c.setUserId(student.getId());
        c.setQuestion("Can numbers be negative?");
        c.setAnswer("No.");
        c.setAnsweredAt(Instant.now());
        c.setPublic(false);
        clarifications.save(c);

        byte[] slides = "slide bytes".getBytes(StandardCharsets.UTF_8);
        materials.upload(contestId, new MockMultipartFile("file", "Week 1 slides.pdf", "application/pdf", slides));

        // Audit rows: three belong to this contest, one to another.
        audit("Contest", contestId, "{\"before\":{\"state\":\"ACTIVE\"},\"after\":{\"state\":\"PAUSED\"}}");
        audit("Announcement", 1, "{\"before\":{\"contestId\":" + contestId + "},\"after\":null}");
        audit("TestCase", t1.getId(), "{\"before\":null,\"after\":{\"problemId\":" + html.getId() + "}}");
        audit("Announcement", 2, "{\"before\":null,\"after\":{\"contestId\":" + (contestId + 999_999) + "}}");

        // --- export ---
        ResponseEntity<byte[]> response = get(adminToken);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("aarchive_"));

        Map<String, byte[]> zip = unzip(response.getBody());
        JsonNode root = json.readTree(zip.get(AArchiveExportService.MANIFEST));
        String manifestText = new String(zip.get(AArchiveExportService.MANIFEST), StandardCharsets.UTF_8);

        assertEquals(AArchiveExportService.FORMAT, root.path("format").asText());
        assertEquals(AArchiveExportService.FORMAT_VERSION, root.path("formatVersion").asInt());
        assertEquals(contestId.longValue(), root.path("contest").path("id").asLong());
        assertEquals("ENDED", root.path("contest").path("state").asText());
        assertTrue(root.path("contest").path("hasPassword").asBoolean());
        assertEquals(120_000, root.path("contest").path("pausedMillis").asLong());
        assertEquals(0, root.path("warnings").size(), root.path("warnings").toString());

        // Credentials never leave the server.
        assertFalse(manifestText.contains("$2a$"), "no bcrypt hash may be exported");
        assertFalse(manifestText.contains("passwordHash"));
        assertFalse(manifestText.contains(student.getPasswordHash()));

        // Problems, statements (sanitized), checker, and test data as files.
        JsonNode pa = root.path("problems").get(0);
        assertEquals("A", pa.path("code").asText());
        assertEquals("CUSTOM", pa.path("checkerType").asText());
        assertEquals("int main(){return 0;}", pa.path("checkerSource").asText());
        assertEquals("HTML", pa.path("statement").path("format").asText());
        assertFalse(pa.path("statement").path("html").asText().contains("<script"));
        assertEquals(2, pa.path("testCases").size());
        JsonNode firstTest = pa.path("testCases").get(0);
        assertEquals(t1.getId().longValue(), firstTest.path("id").asLong());
        assertEquals(1, firstTest.path("ordinal").asInt());
        assertEquals("1 2\n", text(zip, firstTest.path("input").path("path").asText()));
        assertEquals("ünïcödé 5 5\n", text(zip, pa.path("testCases").get(1).path("input").path("path").asText()));

        JsonNode pb = root.path("problems").get(1);
        assertEquals("PDF", pb.path("statement").path("format").asText());
        assertArrayEquals(pdfBytes, zip.get(pb.path("statement").path("pdf").path("path").asText()));

        // Every indexed file exists with the stated size and hash.
        for (JsonNode f : root.path("files")) {
            byte[] data = zip.get(f.path("path").asText());
            assertNotNull(data, f.path("path").asText());
            assertEquals(f.path("sizeBytes").asLong(), data.length);
            assertEquals(f.path("sha256").asText(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        }
        assertEquals(zip.size() - 1, root.path("files").size(), "every non-manifest entry is indexed");

        // Submissions in submission order, with per-test rows and contest-relative time.
        JsonNode subs = root.path("submissions");
        assertEquals(2, subs.size());
        assertEquals(wa.getId().longValue(), subs.get(0).path("id").asLong());
        assertEquals(start, Instant.parse(root.path("contest").path("startTime").asText()));
        assertEquals(600_000, subs.get(0).path("elapsedMillisFromStart").asLong());
        assertEquals(2, subs.get(0).path("testResults").size());
        assertEquals("9\n", subs.get(0).path("testResults").get(1).path("actualOutput").asText());
        assertEquals("int main(){}", subs.get(1).path("sourceCode").asText());
        assertEquals("127.0.0.1", subs.get(1).path("workstationIp").asText());

        // Standings match the leaderboard's ICPC rule, plus the console's extras.
        JsonNode row = root.path("standings").path("rows").get(0);
        assertEquals(1, row.path("rank").asInt());
        assertEquals(student.getUsername(), row.path("username").asText());
        assertEquals(1, row.path("solved").asInt());
        assertEquals(45, row.path("penaltyMinutes").asLong(), "30 + 20 for the WA - 5 manual");
        assertEquals(87, row.path("marks").asInt());
        assertEquals(ac.getId().longValue(), row.path("bestSubmissionId").asLong());
        JsonNode cell = row.path("cells").get(0);
        assertTrue(cell.path("solved").asBoolean());
        assertTrue(cell.path("firstSolve").asBoolean());
        assertEquals(ac.getId().longValue(), cell.path("firstAcceptedSubmissionId").asLong());

        JsonNode participant = root.path("participants").get(0);
        assertEquals(student.getUsername(), participant.path("username").asText());
        assertEquals(87, participant.path("marks").asInt());
        // Column-default timestamps are true instants even when MySQL's zone is not UTC.
        assertNearNow(participant.path("accessGrantedAt").asText());
        assertNearNow(participant.path("accountCreatedAt").asText());

        assertFalse(root.path("announcements").get(0).path("bodyHtml").asText().contains("onerror"));
        JsonNode clar = root.path("clarifications").get(0);
        assertEquals(student.getUsername(), clar.path("username").asText());
        assertFalse(clar.path("publicRequested").asBoolean());
        assertFalse(clar.path("visibleToAll").asBoolean());

        JsonNode material = root.path("materials").get(0);
        assertEquals("Week 1 slides.pdf", material.path("filename").asText());
        assertArrayEquals(slides, zip.get(material.path("file").path("path").asText()));

        JsonNode auditEvents = root.path("auditEvents");
        assertEquals(3, auditEvents.size(), auditEvents.toString());
        assertEquals("PAUSED", auditEvents.get(0).path("details").path("after").path("state").asText());
        assertNearNow(auditEvents.get(0).path("occurredAt").asText());

        assertEquals(3, root.path("judge").path("languages").size());
        assertEquals(20, root.path("judge").path("penaltyPerRejectMinutes").asInt());

        // The export itself is recorded in the security history.
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = ?",
                Integer.class, "AdminAArchiveExportController.export:" + contestId));
        jdbc.update("DELETE FROM audit_events WHERE action = ?", "AdminAArchiveExportController.export:" + contestId);
    }

    @Test
    void studentsCannotExportAndUnknownContestsAre404() {
        admin = user("aa-admin-", Role.ADMIN);
        student = user("aa-student-", Role.STUDENT);
        Contest contest = new Contest();
        contest.setTitle("AArchive guard " + UUID.randomUUID());
        contestId = contests.save(contest).getId();

        assertEquals(HttpStatus.FORBIDDEN, get(token(student)).getStatusCode());
        contestId = contestId + 1_000_000;
        ResponseEntity<byte[]> missing = get(token(admin));
        contestId = contestId - 1_000_000;
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    // ------------------------------------------------------------------

    private ResponseEntity<byte[]> get(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + "/api/admin/contests/" + contestId + "/export/aarchive",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private void audit(String type, long id, String details) {
        jdbc.update("INSERT INTO audit_events (actor, action, target_type, target_id, outcome, details) "
                + "VALUES ('aarchive-test', 'test', ?, ?, 'COMMITTED_CHANGE', ?)", type, String.valueOf(id), details);
    }

    private User user(String prefix, Role role) {
        User u = new User();
        u.setUsername(prefix + UUID.randomUUID().toString().substring(0, 8));
        u.setDisplayName("Disposable " + role);
        u.setPasswordHash("$2a$12$userPasswordHashMustNeverBeExported0000000000000000000");
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private String token(User u) {
        String sid = UUID.randomUUID().toString();
        sessions.register(u.getUsername(), sid, u.getRole(), true);
        return jwt.generate(u.getUsername(), u.getRole(), sid);
    }

    private Problem problem(String code, String title, int ordering, String statement) {
        Problem p = new Problem();
        p.setContestId(contestId);
        p.setCode(code);
        p.setTitle(title);
        p.setOrdering(ordering);
        p.setStatementHtml(statement);
        return problems.save(p);
    }

    private TestCase testCase(Problem p, int idx, String in, String out) {
        TestCase tc = new TestCase();
        tc.setProblemId(p.getId());
        tc.setIdx(idx);
        tc.setInputData(in);
        tc.setExpectedOutput(out);
        return testCases.save(tc);
    }

    private Submission submission(Problem p, Instant at, Verdict verdict, int failedTest) {
        Submission s = new Submission();
        s.setUserId(student.getId());
        s.setProblemId(p.getId());
        s.setContestId(contestId);
        s.setLanguage(Language.CPP17);
        s.setSourceCode("int main(){}");
        s.setStatus(Submission.Status.DONE);
        s.setVerdict(verdict);
        s.setExecTimeMs(12);
        s.setPeakMemoryKb(3000);
        s.setFailedTestIndex(failedTest);
        s.setWorkstationIp("127.0.0.1");
        s.setQueuedAt(at);
        s.setJudgedAt(at.plusSeconds(2));
        return submissions.save(s);
    }

    private static void assertNearNow(String iso) {
        long skew = Math.abs(java.time.Duration.between(Instant.parse(iso), Instant.now()).toSeconds());
        assertTrue(skew < 300, iso + " is " + skew + "s away from now");
    }

    private static Map<String, byte[]> unzip(byte[] body) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    private static String text(Map<String, byte[]> zip, String path) {
        return new String(zip.get(path), StandardCharsets.UTF_8);
    }

    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
