package com.arbitrator.server.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
import com.arbitrator.common.dto.SubmissionTestsDto;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.dto.TestCaseResultDto;
import com.arbitrator.common.enums.CheckerType;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.judge.JudgeProperties;
import com.arbitrator.server.judge.JudgeQueue;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.repo.UserRepository;

/**
 * Owner: Mahir. FR-09 intake: validate -> rate-limit -> PERSIST -> enqueue.
 * Persist-before-queue is deliberate (FMEA-01): a crash between the two loses
 * nothing, because JudgeQueue requeues all non-DONE rows at startup.
 */
@Service
public class SubmissionService {

    private final SubmissionRepository submissions;
    private final ProblemRepository problems;
    private final ContestService contestService;
    private final UserService userService;
    private final JudgeQueue queue;
    private final UserRepository users;
    private final TestCaseRepository testCases;
    private final JdbcTemplate jdbc;
    private final Duration cooldown;

    /** Per-field cap on disclosed test data; a test file can be megabytes. */
    private static final int MAX_TEST_DATA_CHARS = 4096;

    /** userId -> last accepted submission instant (BR-01). */
    private final Map<Long, Instant> lastSubmit = new ConcurrentHashMap<>();

    public SubmissionService(SubmissionRepository submissions,
                             ProblemRepository problems,
                             ContestService contestService,
                             UserService userService,
                             JudgeQueue queue,
                             UserRepository users,
                             TestCaseRepository testCases,
                             JdbcTemplate jdbc,
                             JudgeProperties props) {
        this.submissions = submissions;
        this.problems = problems;
        this.contestService = contestService;
        this.userService = userService;
        this.queue = queue;
        this.users = users;
        this.testCases = testCases;
        this.jdbc = jdbc;
        this.cooldown = Duration.ofSeconds(props.getSubmitCooldownSeconds());
    }

    public SubmitAckDto submit(String username, SubmitRequest req, String workstationIp) {
        if (req.sourceCode() == null || req.sourceCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty source code");
        }
        if (req.language() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Language is required");
        }

        User user = userService.requireByUsername(username);

        // BR-01: at most one submission per 30 s -> 429 (UC-04 exception)
        Instant last = lastSubmit.get(user.getId());
        if (last != null && Duration.between(last, Instant.now()).compareTo(cooldown) < 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait " + cooldown.toSeconds() + " seconds between submissions");
        }

        Problem problem = problems.findById(req.problemId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));

        // The contest is whichever one owns the problem — deriving it here
        // instead of from a global "current contest" means several contests can
        // run at once without submissions landing in the wrong one.
        Contest contest = contestService.require(problem.getContestId());

        // BR-02: the server clock decides whether that contest is still open.
        contestService.assertAcceptingSubmissions(contest);

        Submission sub = new Submission();
        sub.setUserId(user.getId());
        sub.setProblemId(problem.getId());
        sub.setContestId(contest.getId());
        sub.setLanguage(req.language());
        sub.setSourceCode(req.sourceCode());
        sub.setWorkstationIp(workstationIp);          // NFR-C02
        sub.setQueuedAt(Instant.now());
        submissions.save(sub);                        // persist FIRST (FMEA-01)

        lastSubmit.put(user.getId(), Instant.now());
        int position = queue.enqueue(sub.getId());

        return new SubmitAckDto(sub.getId(), position);
    }

    /**
     * UIF-12: the source behind one submission.
     *
     * LRR-02 forbids exposing one student's code to another during a contest,
     * so a non-admin caller may only read their own. The check is here rather
     * than in the controller because it is a rule about the data, not the route.
     */
    public SubmissionSourceDto source(String username, long submissionId, boolean admin) {
        Submission s = submissions.findById(submissionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such submission"));
        User caller = userService.requireByUsername(username);
        if (!admin && !s.getUserId().equals(caller.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only view your own submissions");
        }
        String code = problems.findById(s.getProblemId())
                .map(Problem::getCode).orElse("?");
        String owner = users.findById(s.getUserId())
                .map(User::getUsername).orElse("?");
        return new SubmissionSourceDto(s.getId(), code, owner, s.getLanguage(),
                s.getVerdict(), s.getSourceCode(), s.getCompilerOutput(),
                s.getQueuedAt().toEpochMilli());
    }

    /**
     * The test cases this submission actually reached, when the instructor has
     * allowed it for that contest.
     *
     * Judging is fail-fast (BR-05), so submission_results holds exactly the
     * tests that ran: every one passed, plus the single one that failed. No
     * filtering by verdict is needed and none is done — reading the table back
     * cannot leak a test the submission never got to.
     *
     * A student may only read their own (LRR-02); an admin may read any, and is
     * never gated by the contest toggle, which exists to control what
     * *contestants* see.
     */
    public SubmissionTestsDto tests(String username, long submissionId, boolean admin) {
        Submission s = submissions.findById(submissionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such submission"));
        User caller = userService.requireByUsername(username);
        if (!admin && !s.getUserId().equals(caller.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only view your own submissions");
        }

        int total = testCases.findByProblemIdOrderByIdxAsc(s.getProblemId()).size();
        int passed = s.getVerdict() == Verdict.AC ? total : Math.max(0, s.getFailedTestIndex() - 1);

        if (!admin && !contestService.require(s.getContestId()).isShowTestCases()) {
            return SubmissionTestsDto.hidden(submissionId, passed, total);
        }

        // A checker-graded problem may accept more than one valid output, so
        // "the" expected output shown next to the participant's is not just a
        // data leak, it's actively misleading. A contestant gets a one-line
        // pass/fail summary instead; the instructor still sees the raw tests
        // below, unchanged, to debug the checker itself (same "always visible
        // to admins" rule the contest toggle above already follows).
        if (!admin) {
            Problem problem = problems.findById(s.getProblemId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));
            if (problem.getCheckerType() == CheckerType.CUSTOM) {
                String summary = s.getVerdict() == Verdict.AC
                        ? "All outputs match."
                        : "Participant's output does not match the correct output.";
                return new SubmissionTestsDto(submissionId, true, passed, total, List.of(), summary);
            }
        }

        // Keyed by test index so a submission judged twice — crash recovery
        // requeues anything not DONE — yields one row per test, the latest.
        Map<Integer, TestCaseResultDto> byIndex = new LinkedHashMap<>();
        jdbc.query("""
                SELECT r.test_index, r.verdict, r.exec_time_ms, r.peak_memory_kb,
                       r.actual_output, t.input_data, t.expected_output
                  FROM submission_results r
                  JOIN test_cases t ON t.problem_id = ? AND t.idx = r.test_index
                 WHERE r.submission_id = ?
                 ORDER BY r.id ASC
                """, rs -> {
            String input = rs.getString("input_data");
            String expected = rs.getString("expected_output");
            String actual = rs.getString("actual_output");
            boolean truncated = length(input) > MAX_TEST_DATA_CHARS
                    || length(expected) > MAX_TEST_DATA_CHARS
                    || length(actual) > MAX_TEST_DATA_CHARS;
            int index = rs.getInt("test_index");
            byIndex.put(index, new TestCaseResultDto(
                    index,
                    parseVerdict(rs.getString("verdict")),
                    rs.getLong("exec_time_ms"),
                    rs.getLong("peak_memory_kb"),
                    clip(input), clip(expected),
                    // Null, not "", for a run judged before V56: the view must
                    // say "not recorded" rather than claim it printed nothing.
                    actual == null ? null : clip(actual),
                    truncated));
        }, s.getProblemId(), submissionId);

        return new SubmissionTestsDto(submissionId, true, passed, total,
                List.copyOf(byIndex.values()), null);
    }

    private static Verdict parseVerdict(String name) {
        try {
            return name == null ? null : Verdict.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;      // a verdict this build no longer knows about
        }
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    /** A test file can be megabytes; nobody reads that in a panel. */
    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_TEST_DATA_CHARS ? s : s.substring(0, MAX_TEST_DATA_CHARS);
    }

    /** FR-16: personal history, newest first. */
    public List<SubmissionHistoryDto> history(String username) {
        User user = userService.requireByUsername(username);
        Map<Long, Problem> problemMap = problems.findAll().stream()
                .collect(Collectors.toMap(Problem::getId, p -> p));
        Map<Long, Integer> totalTestMap = new ConcurrentHashMap<>();

        return submissions.findByUserIdAndActiveTrueOrderByQueuedAtDesc(user.getId()).stream()
                .map(s -> {
                    Problem p = problemMap.get(s.getProblemId());
                    int total = totalTestMap.computeIfAbsent(s.getProblemId(),
                            id -> testCases.findByProblemIdOrderByIdxAsc(id).size());
                    int passed = (s.getVerdict() == com.arbitrator.common.enums.Verdict.AC) ? total : Math.max(0, s.getFailedTestIndex() - 1);
                    return new SubmissionHistoryDto(
                            s.getId(),
                            p != null ? p.getCode() : "?",
                            s.getLanguage(),
                            s.getVerdict(),
                            s.getExecTimeMs(),
                            s.getPeakMemoryKb(),
                            s.getQueuedAt().toEpochMilli(),
                            passed,
                            total);
                })
                .toList();
    }
}
