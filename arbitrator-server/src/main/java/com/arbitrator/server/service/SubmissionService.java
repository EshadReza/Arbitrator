package com.arbitrator.server.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.judge.JudgeProperties;
import com.arbitrator.server.judge.JudgeQueue;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
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
    private final Duration cooldown;

    /** userId -> last accepted submission instant (BR-01). */
    private final Map<Long, Instant> lastSubmit = new ConcurrentHashMap<>();

    public SubmissionService(SubmissionRepository submissions,
                             ProblemRepository problems,
                             ContestService contestService,
                             UserService userService,
                             JudgeQueue queue,
                             UserRepository users,
                             JudgeProperties props) {
        this.submissions = submissions;
        this.problems = problems;
        this.contestService = contestService;
        this.userService = userService;
        this.queue = queue;
        this.users = users;
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

    /** FR-16: personal history, newest first. */
    public List<SubmissionHistoryDto> history(String username) {
        User user = userService.requireByUsername(username);
        Map<Long, String> codes = problems.findAll().stream()
                .collect(Collectors.toMap(Problem::getId, Problem::getCode));
        return submissions.findByUserIdAndActiveTrueOrderByQueuedAtDesc(user.getId()).stream()
                .map(s -> new SubmissionHistoryDto(
                        s.getId(),
                        codes.getOrDefault(s.getProblemId(), "?"),
                        s.getLanguage(),
                        s.getVerdict(),
                        s.getExecTimeMs(),
                        s.getPeakMemoryKb(),
                        s.getQueuedAt().toEpochMilli()))
                .toList();
    }
}
