package com.labjudge.server.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.labjudge.common.dto.SubmissionHistoryDto;
import com.labjudge.common.dto.SubmitAckDto;
import com.labjudge.common.dto.SubmitRequest;
import com.labjudge.server.entity.Contest;
import com.labjudge.server.entity.Problem;
import com.labjudge.server.entity.Submission;
import com.labjudge.server.entity.User;
import com.labjudge.server.judge.JudgeProperties;
import com.labjudge.server.judge.JudgeQueue;
import com.labjudge.server.repo.ProblemRepository;
import com.labjudge.server.repo.SubmissionRepository;

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
    private final Duration cooldown;

    /** userId -> last accepted submission instant (BR-01). */
    private final Map<Long, Instant> lastSubmit = new ConcurrentHashMap<>();

    public SubmissionService(SubmissionRepository submissions,
                             ProblemRepository problems,
                             ContestService contestService,
                             UserService userService,
                             JudgeQueue queue,
                             JudgeProperties props) {
        this.submissions = submissions;
        this.problems = problems;
        this.contestService = contestService;
        this.userService = userService;
        this.queue = queue;
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

        // BR-02: server clock decides whether the contest is still open
        Contest contest = contestService.requireCurrent();
        contestService.assertAcceptingSubmissions(contest);

        Problem problem = problems.findById(req.problemId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));
        if (!problem.getContestId().equals(contest.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Problem is not part of the current contest");
        }

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
