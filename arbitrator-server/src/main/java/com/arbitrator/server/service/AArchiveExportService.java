/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.LeaderboardCellDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;
import com.arbitrator.common.dto.NotificationDto;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Announcement;
import com.arbitrator.server.entity.Clarification;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Material;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.TestCase;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.judge.JudgeProperties;
import com.arbitrator.server.leaderboard.LeaderboardService;
import com.arbitrator.server.realtime.NotificationService;
import com.arbitrator.server.repo.AnnouncementRepository;
import com.arbitrator.server.repo.ClarificationRepository;
import com.arbitrator.server.repo.MaterialRepository;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.RichTextSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * "Export for AArchive": one ZIP holding every stored fact about a contest, so
 * the online archive can replay it as if it had been run there. The format is
 * a contract with an external system — docs/aarchive-export.md is its
 * specification, and any change here must bump {@link #FORMAT_VERSION} and
 * update that document in the same commit.
 *
 * Structured data goes in {@value #MANIFEST}; bytes that can be large (test
 * data, PDF statements, materials) go in their own ZIP entries, referenced
 * from the manifest by path, size and SHA-256.
 *
 * Deliberately never exported: user password hashes and the contest's join
 * password hash. They are credentials, and an archive is meant to be
 * published.
 *
 * Read-only throughout. Everything is read in one read-only transaction so
 * the rows agree with each other (InnoDB's consistent snapshot); in particular
 * this never calls {@link ContestService#stateOf}, which can start or end a
 * contest as a side effect.
 */
@Service
public class AArchiveExportService {

    public static final String FORMAT = "arbitrator-aarchive";
    public static final int FORMAT_VERSION = 1;
    public static final String MANIFEST = "aarchive.json";

    /**
     * Mirrors LeaderboardService.PENALTY_PER_REJECT (package-private there);
     * AArchiveExportPenaltyTest keeps the two from drifting apart.
     */
    public static final int PENALTY_PER_REJECT_MINUTES = 20;

    /** Mirror the judge's truncation limits so the archive can say what was cut. */
    private static final int COMPILER_OUTPUT_LIMIT_CHARS = 4096;
    private static final int STORED_OUTPUT_LIMIT_CHARS = 4096;

    private final ContestService contestService;
    private final ProblemRepository problems;
    private final TestCaseRepository testCases;
    private final SubmissionRepository submissions;
    private final UserRepository users;
    private final AnnouncementRepository announcements;
    private final ClarificationRepository clarifications;
    private final MaterialRepository materials;
    private final MaterialService materialService;
    private final LeaderboardService leaderboard;
    private final NotificationService notifications;
    private final JudgeProperties judge;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AArchiveExportService(ContestService contestService,
                                 ProblemRepository problems,
                                 TestCaseRepository testCases,
                                 SubmissionRepository submissions,
                                 UserRepository users,
                                 AnnouncementRepository announcements,
                                 ClarificationRepository clarifications,
                                 MaterialRepository materials,
                                 MaterialService materialService,
                                 LeaderboardService leaderboard,
                                 NotificationService notifications,
                                 JudgeProperties judge,
                                 JdbcTemplate jdbc,
                                 ObjectMapper json) {
        this.contestService = contestService;
        this.problems = problems;
        this.testCases = testCases;
        this.submissions = submissions;
        this.users = users;
        this.announcements = announcements;
        this.clarifications = clarifications;
        this.materials = materials;
        this.materialService = materialService;
        this.leaderboard = leaderboard;
        this.notifications = notifications;
        this.judge = judge;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Writes the whole archive to {@code out} and finishes (but does not close) it. */
    @Transactional(readOnly = true)
    public void export(long contestId, OutputStream out) throws IOException {
        Contest contest = contestService.require(contestId);
        Instant generatedAt = Instant.now();
        List<String> warnings = new ArrayList<>();
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        ArchiveWriter files = new ArchiveWriter(zip);

        if (contest.getState() != ContestState.ENDED) {
            warnings.add("Contest was " + contest.getState()
                    + " when exported, not ENDED; its data may still change.");
        }

        // --- problems, statements and test data ---
        List<Problem> contestProblems = problems.findByContestIdOrderByOrderingAscCodeAsc(contestId);
        Map<Long, Problem> problemById = new LinkedHashMap<>();
        contestProblems.forEach(p -> problemById.put(p.getId(), p));
        List<ProblemOut> problemOut = new ArrayList<>();
        for (Problem p : contestProblems) {
            problemOut.add(problem(p, files, warnings));
        }

        // --- submissions and per-test results ---
        List<Submission> allSubmissions = new ArrayList<>(submissions.findByContestId(contestId));
        allSubmissions.sort(Comparator.comparing(Submission::getQueuedAt).thenComparing(Submission::getId));
        Map<Long, List<TestResultOut>> resultsBySubmission = testResults(contestId);

        // --- participants: anyone admitted, anyone who submitted, anyone who asked ---
        List<Clarification> contestClarifications =
                new ArrayList<>(clarifications.findByContestIdOrderByAskedAtDesc(contestId));
        contestClarifications.sort(Comparator.comparing(Clarification::getAskedAt).thenComparing(Clarification::getId));
        Map<Long, Instant> grants = accessGrants(contestId);
        Set<Long> participantIds = new LinkedHashSet<>(grants.keySet());
        allSubmissions.forEach(s -> participantIds.add(s.getUserId()));
        contestClarifications.forEach(c -> participantIds.add(c.getUserId()));
        Map<Long, User> userById = new LinkedHashMap<>();
        users.findAllById(participantIds).forEach(u -> userById.put(u.getId(), u));
        Map<Long, Instant> accountCreated = accountCreatedAt(participantIds);

        Instant start = contest.getStartTime();
        List<SubmissionOut> submissionOut = new ArrayList<>();
        int unjudged = 0;
        for (Submission s : allSubmissions) {
            if (s.getStatus() != Submission.Status.DONE) {
                unjudged++;
            }
            Problem p = problemById.get(s.getProblemId());
            User u = userById.get(s.getUserId());
            submissionOut.add(new SubmissionOut(
                    s.getId(), s.getUserId(), u == null ? null : u.getUsername(),
                    s.getProblemId(), p == null ? null : p.getCode(),
                    s.getLanguage() == null ? null : s.getLanguage().name(),
                    s.getSourceCode(),
                    s.getStatus().name(),
                    s.getVerdict() == null ? null : s.getVerdict().name(),
                    s.getExecTimeMs(), s.getPeakMemoryKb(),
                    s.getCompilerOutput(), s.getFailedTestIndex(),
                    s.getMarks(), s.getManualPenaltyDelta(),
                    s.getWorkstationIp(),
                    iso(s.getQueuedAt()), iso(s.getJudgedAt()),
                    start == null ? null : Duration.between(start, s.getQueuedAt()).toMillis(),
                    s.isActive(),
                    resultsBySubmission.getOrDefault(s.getId(), List.of())));
        }
        if (unjudged > 0) {
            warnings.add(unjudged + " submission(s) had not finished judging when exported.");
        }

        List<Submission> active = allSubmissions.stream().filter(Submission::isActive).toList();
        List<ParticipantOut> participantOut = new ArrayList<>();
        for (Long id : participantIds) {
            User u = userById.get(id);
            if (u == null) {
                continue;
            }
            participantOut.add(new ParticipantOut(
                    u.getId(), u.getUsername(), u.getDisplayName(),
                    u.getRole() == null ? null : u.getRole().name(),
                    iso(accountCreated.get(id)),
                    iso(grants.get(id)),
                    marksOf(active, id),
                    u.getMacAddress(), iso(u.getMacChangedAt())));
        }

        StandingsOut standings = standings(contest, problemById, userById, active, warnings);

        List<AnnouncementOut> announcementOut = new ArrayList<>();
        List<Announcement> contestAnnouncements =
                new ArrayList<>(announcements.findByContestIdOrderByCreatedAtDesc(contestId));
        contestAnnouncements.sort(Comparator.comparing(Announcement::getCreatedAt).thenComparing(Announcement::getId));
        for (Announcement a : contestAnnouncements) {
            // Clean on the way out too, as AnnouncementService.toDto does, so a
            // legacy row cannot carry active content into the archive.
            announcementOut.add(new AnnouncementOut(a.getId(),
                    RichTextSanitizer.sanitize(a.getBody()), iso(a.getCreatedAt())));
        }

        List<ClarificationOut> clarificationOut = new ArrayList<>();
        for (Clarification c : contestClarifications) {
            Problem p = c.getProblemId() == null ? null : problemById.get(c.getProblemId());
            User u = userById.get(c.getUserId());
            clarificationOut.add(new ClarificationOut(
                    c.getId(), c.getProblemId(), p == null ? null : p.getCode(),
                    c.getUserId(), u == null ? null : u.getUsername(),
                    c.getQuestion(), c.getAnswer(),
                    iso(c.getAskedAt()), iso(c.getAnsweredAt()),
                    c.isPublic(), c.isApproved(), c.isPublic() && c.isApproved()));
        }

        List<MaterialOut> materialOut = new ArrayList<>();
        List<Material> contestMaterials = new ArrayList<>(materials.findByContestIdOrderByUploadedAtDesc(contestId));
        contestMaterials.sort(Comparator.comparing(Material::getUploadedAt).thenComparing(Material::getId));
        for (Material m : contestMaterials) {
            materialOut.add(material(m, files, warnings));
        }

        List<NotificationOut> notificationOut = new ArrayList<>();
        for (NotificationDto n : notifications.current()) {
            if (n.contestId() == contestId) {
                notificationOut.add(new NotificationOut(n.id(), n.username(), n.displayName(), n.type(),
                        n.minutesOffline(), iso(Instant.ofEpochMilli(n.atMs()))));
            }
        }

        Archive archive = new Archive(
                FORMAT, FORMAT_VERSION, iso(generatedAt),
                new GeneratorOut("Arbitrator", schemaVersion()),
                contest(contest),
                judgeSettings(),
                problemOut,
                participantOut,
                submissionOut,
                standings,
                announcementOut,
                clarificationOut,
                materialOut,
                auditEvents(contestId, problemById.keySet()),
                notificationOut,
                files.entries,
                warnings);

        // writeValueAsBytes, not writeValue(zip): the latter closes its target.
        byte[] manifest = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(archive);
        zip.putNextEntry(new ZipEntry(MANIFEST));
        zip.write(manifest);
        zip.closeEntry();
        zip.finish();
    }

    // ------------------------------------------------------------------

    private ProblemOut problem(Problem p, ArchiveWriter files, List<String> warnings) throws IOException {
        String dir = "problems/" + p.getId() + "/";
        FileRef pdf = null;
        if (p.isStatementIsPdf()) {
            List<byte[]> data = jdbc.query("SELECT data FROM problem_statement_pdfs WHERE problem_id = ?",
                    (rs, n) -> rs.getBytes(1), p.getId());
            if (data.isEmpty() || data.get(0) == null) {
                warnings.add("Problem " + p.getCode() + " is marked as a PDF statement but its PDF is missing.");
            } else {
                pdf = files.put(dir + "statement.pdf", "STATEMENT_PDF", data.get(0));
            }
        }
        StatementOut statement = new StatementOut(p.isStatementIsPdf() ? "PDF" : "HTML",
                RichTextSanitizer.sanitize(p.getStatementHtml()), pdf);

        List<TestCaseOut> tests = new ArrayList<>();
        int ordinal = 0;
        for (TestCase tc : testCases.findByProblemIdOrderByIdxAsc(p.getId())) {
            ordinal++;
            FileRef in = files.put(dir + "tests/" + tc.getIdx() + ".in", "TEST_INPUT",
                    tc.getInputData().getBytes(StandardCharsets.UTF_8));
            FileRef ans = files.put(dir + "tests/" + tc.getIdx() + ".out", "TEST_EXPECTED_OUTPUT",
                    tc.getExpectedOutput().getBytes(StandardCharsets.UTF_8));
            tests.add(new TestCaseOut(tc.getId(), tc.getIdx(), ordinal, in, ans));
        }

        return new ProblemOut(p.getId(), p.getCode(), p.getTitle(), p.getOrdering(),
                p.getTimeLimitMs(), p.getMemoryLimitKb(),
                p.getCheckerType() == null ? null : p.getCheckerType().name(),
                p.getCheckerSource(), statement, tests);
    }

    private MaterialOut material(Material m, ArchiveWriter files, List<String> warnings) throws IOException {
        FileRef ref = null;
        try {
            Path source = materialService.fileOf(m);
            try (InputStream in = Files.newInputStream(source)) {
                ref = files.put("materials/" + m.getId() + "/" + entryName(m.getFilename()), "MATERIAL", in);
            }
        } catch (ResponseStatusException missing) {
            warnings.add("Material \"" + m.getFilename() + "\" (id " + m.getId() + ") is missing on disk.");
        }
        return new MaterialOut(m.getId(), m.getFilename(), m.getContentType(), m.getSizeBytes(),
                iso(m.getUploadedAt()), ref);
    }

    /**
     * Every per-test row, oldest first. A submission requeued by crash
     * recovery can carry two rows for one test; both are kept, and the
     * specification says the later one (higher id) is authoritative —
     * the same rule SubmissionService.tests applies.
     */
    private Map<Long, List<TestResultOut>> testResults(long contestId) {
        Map<Long, List<TestResultOut>> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT r.id, r.submission_id, r.test_index, r.verdict, r.exec_time_ms,
                       r.peak_memory_kb, r.actual_output
                  FROM submission_results r
                  JOIN submissions s ON s.id = r.submission_id
                 WHERE s.contest_id = ?
                 ORDER BY r.submission_id ASC, r.id ASC
                """, rs -> {
            out.computeIfAbsent(rs.getLong("submission_id"), k -> new ArrayList<>())
                    .add(new TestResultOut(rs.getLong("id"), rs.getInt("test_index"),
                            rs.getString("verdict"), rs.getLong("exec_time_ms"),
                            rs.getLong("peak_memory_kb"), rs.getString("actual_output")));
        }, contestId);
        return out;
    }

    /*
     * users.created_at, contest_access_grants.granted_at and
     * audit_events.occurred_at are filled by a CURRENT_TIMESTAMP column
     * default, i.e. in the MySQL session's time zone, while the JDBC URL
     * declares the server UTC. On a server whose zone is not UTC, reading them
     * through getTimestamp() shifts them by that offset. UNIX_TIMESTAMP() of a
     * TIMESTAMP column is the stored instant whatever either zone says.
     */

    private Map<Long, Instant> accessGrants(long contestId) {
        Map<Long, Instant> out = new LinkedHashMap<>();
        jdbc.query("SELECT user_id, UNIX_TIMESTAMP(granted_at) AS granted FROM contest_access_grants "
                        + "WHERE contest_id = ? ORDER BY granted_at ASC, user_id ASC",
                rs -> {
                    out.put(rs.getLong("user_id"), epoch(rs.getBigDecimal("granted")));
                }, contestId);
        return out;
    }

    private Map<Long, Instant> accountCreatedAt(Set<Long> userIds) {
        Map<Long, Instant> out = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
        jdbc.query("SELECT id, UNIX_TIMESTAMP(created_at) AS created FROM users WHERE id IN ("
                        + placeholders + ")",
                rs -> {
                    out.put(rs.getLong("id"), epoch(rs.getBigDecimal("created")));
                }, userIds.toArray());
        return out;
    }

    /** UNIX_TIMESTAMP() is whole seconds, or DECIMAL seconds for fractional columns. */
    private static Instant epoch(BigDecimal seconds) {
        return seconds == null ? null
                : Instant.EPOCH.plus(seconds.movePointRight(6).longValue(), ChronoUnit.MICROS);
    }

    /** AdminStandingsController's rule: the latest non-null marks on any active submission. */
    private static Integer marksOf(List<Submission> active, long userId) {
        Integer marks = null;
        for (Submission s : active) {
            if (s.getUserId() == userId && s.getMarks() != null) {
                marks = s.getMarks();
            }
        }
        return marks;
    }

    private StandingsOut standings(Contest contest, Map<Long, Problem> problemById, Map<Long, User> userById,
                                   List<Submission> active, List<String> warnings) {
        LeaderboardDto board = leaderboard.forContest(contest);
        if (board.frozen()) {
            warnings.add("Contest was FROZEN when exported: standings are the freeze-time snapshot.");
        }
        Map<String, Long> userIdByName = new LinkedHashMap<>();
        userById.values().forEach(u -> userIdByName.put(u.getUsername(), u.getId()));
        Map<String, Long> problemIdByCode = new LinkedHashMap<>();
        problemById.values().forEach(p -> problemIdByCode.put(p.getCode(), p.getId()));

        List<StandingsRowOut> rows = new ArrayList<>();
        for (LeaderboardRowDto r : board.rows()) {
            Long userId = userIdByName.get(r.username());
            List<Submission> mine = active.stream()
                    .filter(s -> userId != null && s.getUserId() == userId.longValue())
                    .toList();
            List<StandingsCellOut> cells = new ArrayList<>();
            for (LeaderboardCellDto c : r.cells()) {
                Long problemId = problemIdByCode.get(c.problemCode());
                List<Submission> onProblem = mine.stream()
                        .filter(s -> problemId != null && s.getProblemId() == problemId.longValue())
                        .toList();
                Long firstAc = onProblem.stream()
                        .filter(s -> s.getVerdict() == Verdict.AC)
                        .map(Submission::getId)
                        .findFirst().orElse(null);
                cells.add(new StandingsCellOut(problemId, c.problemCode(), c.solved(), c.failedAttempts(),
                        c.solvedAtMinutes(), c.solvedAtSeconds(), c.firstSolve(), c.ceAttempts(),
                        firstAc, best(onProblem)));
            }
            rows.add(new StandingsRowOut(r.rank(), userId, r.username(), r.displayName(),
                    r.solved(), r.penaltyMinutes(),
                    userId == null ? null : marksOf(active, userId),
                    best(mine), cells));
        }
        return new StandingsOut(board.frozen(), iso(Instant.ofEpochMilli(board.lastUpdatedMs())),
                board.problemCodes(), rows);
    }

    /**
     * The submission the instructor console shows as "best" — fastest AC
     * (first on a tie), else the latest attempt. Applied to all of a user's
     * submissions it is AdminStandingsController.getStandings' rule; to one
     * problem's, getParticipantCodes'.
     */
    private static Long best(List<Submission> mine) {
        Submission best = null;
        for (Submission s : mine) {
            if (s.getVerdict() == Verdict.AC && (best == null || s.getExecTimeMs() < best.getExecTimeMs())) {
                best = s;
            }
        }
        if (best == null && !mine.isEmpty()) {
            best = mine.get(mine.size() - 1);
        }
        return best == null ? null : best.getId();
    }

    /**
     * Admin-driven changes that belong to this contest: the contest row itself
     * (state, pause, freeze and duration transitions — the only record of the
     * contest's timeline), plus any row whose audited snapshot names this
     * contest or, for test cases, one of its problems.
     */
    private List<AuditEventOut> auditEvents(long contestId, Set<Long> problemIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, UNIX_TIMESTAMP(occurred_at) AS occurred, actor, action, target_type, target_id, peer_ip,
                       outcome, http_status, details
                  FROM audit_events
                 WHERE (target_type = 'Contest' AND target_id = ?)
                    OR CASE WHEN JSON_VALID(details)
                            THEN JSON_EXTRACT(details, '$.before.contestId') = ?
                              OR JSON_EXTRACT(details, '$.after.contestId') = ?
                            ELSE FALSE END
                """);
        List<Object> args = new ArrayList<>(List.of(String.valueOf(contestId), contestId, contestId));
        if (!problemIds.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(problemIds.size(), "?"));
            sql.append("""
                    OR (target_type = 'TestCase' AND CASE WHEN JSON_VALID(details)
                            THEN JSON_EXTRACT(details, '$.before.problemId') IN (%s)
                              OR JSON_EXTRACT(details, '$.after.problemId') IN (%s)
                            ELSE FALSE END)
                    """.formatted(placeholders, placeholders));
            args.addAll(problemIds);
            args.addAll(problemIds);
        }
        sql.append(" ORDER BY id ASC");
        return jdbc.query(sql.toString(), (rs, n) -> new AuditEventOut(
                rs.getLong("id"),
                iso(epoch(rs.getBigDecimal("occurred"))),
                rs.getString("actor"), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"),
                rs.getString("peer_ip"), rs.getString("outcome"),
                (Integer) rs.getObject("http_status"),
                details(rs.getString("details"))), args.toArray());
    }

    private JsonNode details(String raw) {
        try {
            return json.readTree(raw);
        } catch (IOException e) {
            return TextNode.valueOf(raw);
        }
    }

    private String schemaVersion() {
        try {
            List<String> v = jdbc.queryForList("SELECT version FROM flyway_schema_history "
                    + "WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class);
            return v.isEmpty() ? null : v.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ContestOut contest(Contest c) {
        return new ContestOut(c.getId(), c.getTitle(), c.getState().name(), c.hasPassword(),
                c.getDurationMinutes(),
                iso(c.getScheduledStartAt()), iso(c.getStartTime()),
                iso(c.getPausedAt()), c.getPausedMillis(),
                iso(c.getFrozenAt()), iso(c.getEndedAt()), iso(c.endTime()),
                c.isShowTestCases(), c.isAllowPrivateClarifications());
    }

    private JudgeOut judgeSettings() {
        List<LanguageOut> languages = new ArrayList<>();
        for (Language l : Language.values()) {
            JudgeProperties.LanguageSpec spec = judge.getLanguages().get(l.configKey());
            languages.add(new LanguageOut(l.name(), l.display(), l.configKey(),
                    spec == null ? l.sourceFileName() : spec.getSourceFile(),
                    spec == null ? null : spec.getCompile(),
                    spec == null ? null : spec.getRun()));
        }
        List<VerdictOut> verdicts = new ArrayList<>();
        for (Verdict v : Verdict.values()) {
            verdicts.add(new VerdictOut(v.name(), v.label(), v != Verdict.AC && v != Verdict.CE));
        }
        return new JudgeOut(PENALTY_PER_REJECT_MINUTES, judge.getSubmitCooldownSeconds(),
                judge.getCompileTimeoutMs(), judge.getCheckerTimeLimitMs(), judge.getCheckerMemoryLimitKb(),
                judge.getMaxSourceBytes(), judge.getDockerImage(),
                COMPILER_OUTPUT_LIMIT_CHARS, STORED_OUTPUT_LIMIT_CHARS, languages, verdicts);
    }

    /**
     * MaterialService already strips separators and control characters from
     * display names, but a legacy row may predate that; an entry name must
     * never be able to climb out of its material's directory.
     */
    static String entryName(String filename) {
        if (filename == null || filename.isBlank()
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.equals(".") || filename.equals("..")
                || filename.chars().anyMatch(Character::isISOControl)) {
            return "file";
        }
        return filename;
    }

    private static String iso(Instant i) {
        return i == null ? null : i.toString();
    }

    /** Appends ZIP entries and indexes each one with its size and SHA-256. */
    private static final class ArchiveWriter {

        private final ZipOutputStream zip;
        private final List<FileEntry> entries = new ArrayList<>();

        ArchiveWriter(ZipOutputStream zip) {
            this.zip = zip;
        }

        FileRef put(String path, String kind, byte[] data) throws IOException {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(data);
            zip.closeEntry();
            return index(path, kind, data.length, sha256().digest(data));
        }

        FileRef put(String path, String kind, InputStream in) throws IOException {
            MessageDigest digest = sha256();
            long size = 0;
            byte[] buffer = new byte[64 * 1024];
            zip.putNextEntry(new ZipEntry(path));
            for (int n; (n = in.read(buffer)) > 0; ) {
                zip.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                size += n;
            }
            zip.closeEntry();
            return index(path, kind, size, digest.digest());
        }

        private FileRef index(String path, String kind, long size, byte[] hash) {
            String hex = HexFormat.of().formatHex(hash);
            entries.add(new FileEntry(path, kind, size, hex));
            return new FileRef(path, size, hex);
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }
    }

    // --- the archive's JSON shape; docs/aarchive-export.md documents every field ---

    public record Archive(String format, int formatVersion, String generatedAt, GeneratorOut generator,
                          ContestOut contest, JudgeOut judge, List<ProblemOut> problems,
                          List<ParticipantOut> participants, List<SubmissionOut> submissions,
                          StandingsOut standings, List<AnnouncementOut> announcements,
                          List<ClarificationOut> clarifications, List<MaterialOut> materials,
                          List<AuditEventOut> auditEvents, List<NotificationOut> liveNotifications,
                          List<FileEntry> files, List<String> warnings) {
    }

    public record GeneratorOut(String application, String schemaVersion) {
    }

    public record ContestOut(long id, String title, String state, boolean hasPassword, int durationMinutes,
                             String scheduledStartAt, String startTime, String pausedAt, long pausedMillis,
                             String frozenAt, String endedAt, String effectiveEndTime,
                             boolean showTestCases, boolean allowPrivateClarifications) {
    }

    public record JudgeOut(int penaltyPerRejectMinutes, int submitCooldownSeconds, long compileTimeoutMs,
                           long checkerTimeLimitMs, long checkerMemoryLimitKb, int maxSourceBytes,
                           String dockerImage, int compilerOutputLimitChars, int storedOutputLimitChars,
                           List<LanguageOut> languages, List<VerdictOut> verdicts) {
    }

    public record LanguageOut(String id, String displayName, String configKey, String sourceFile,
                              String compileCommand, String runCommand) {
    }

    public record VerdictOut(String code, String label, boolean countsAsRejectedAttempt) {
    }

    public record ProblemOut(long id, String code, String title, int ordering, int timeLimitMs,
                             int memoryLimitKb, String checkerType, String checkerSource,
                             StatementOut statement, List<TestCaseOut> testCases) {
    }

    public record StatementOut(String format, String html, FileRef pdf) {
    }

    public record TestCaseOut(long id, int idx, int ordinal, FileRef input, FileRef expectedOutput) {
    }

    public record FileRef(String path, long sizeBytes, String sha256) {
    }

    public record FileEntry(String path, String kind, long sizeBytes, String sha256) {
    }

    public record ParticipantOut(long userId, String username, String displayName, String role,
                                 String accountCreatedAt, String accessGrantedAt, Integer marks,
                                 String macAddress, String macChangedAt) {
    }

    public record SubmissionOut(long id, long userId, String username, long problemId, String problemCode,
                                String language, String sourceCode, String status, String verdict,
                                long execTimeMs, long peakMemoryKb, String compilerOutput, int failedTestIndex,
                                Integer marks, int manualPenaltyDelta, String workstationIp,
                                String submittedAt, String judgedAt, Long elapsedMillisFromStart,
                                boolean active, List<TestResultOut> testResults) {
    }

    public record TestResultOut(long id, int testIndex, String verdict, long execTimeMs, long peakMemoryKb,
                                String actualOutput) {
    }

    public record StandingsOut(boolean frozen, String computedAt, List<String> problemCodes,
                               List<StandingsRowOut> rows) {
    }

    public record StandingsRowOut(int rank, Long userId, String username, String displayName, int solved,
                                  long penaltyMinutes, Integer marks, Long bestSubmissionId,
                                  List<StandingsCellOut> cells) {
    }

    public record StandingsCellOut(Long problemId, String problemCode, boolean solved, int failedAttempts,
                                   long solvedAtMinutes, long solvedAtSeconds, boolean firstSolve,
                                   int ceAttempts, Long firstAcceptedSubmissionId, Long bestSubmissionId) {
    }

    public record AnnouncementOut(long id, String bodyHtml, String createdAt) {
    }

    public record ClarificationOut(long id, Long problemId, String problemCode, long userId, String username,
                                   String question, String answer, String askedAt, String answeredAt,
                                   boolean publicRequested, boolean approved, boolean visibleToAll) {
    }

    public record MaterialOut(long id, String filename, String contentType, long sizeBytes, String uploadedAt,
                              FileRef file) {
    }

    public record AuditEventOut(long id, String occurredAt, String actor, String action, String targetType,
                                String targetId, String peerIp, String outcome, Integer httpStatus,
                                JsonNode details) {
    }

    public record NotificationOut(long id, String username, String displayName, String type,
                                  int minutesOffline, String at) {
    }
}
