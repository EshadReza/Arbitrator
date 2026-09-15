package com.arbitrator.server.leaderboard;

import java.security.Principal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.AttemptSummaryDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.service.ContestService;
import com.arbitrator.server.service.ContestAccessService;

/**
 * REST view of the standings. The live path is the STOMP broadcast
 * (LeaderboardBroadcaster); this endpoint serves the first paint and the
 * reconnect case, where a client that missed pushes needs current state
 * (UC-05 exception flow).
 */
@RestController
public class LeaderboardController {

    private final LeaderboardService leaderboard;
    private final ContestService contestService;
    private final ContestAccessService contestAccess;
    private final UserRepository users;
    private final ProblemRepository problems;
    private final SubmissionRepository submissions;
    private final TestCaseRepository testCases;

    public LeaderboardController(LeaderboardService leaderboard,
                                 ContestService contestService,
                                 ContestAccessService contestAccess,
                                 UserRepository users,
                                 ProblemRepository problems,
                                 SubmissionRepository submissions,
                                 TestCaseRepository testCases) {
        this.leaderboard = leaderboard;
        this.contestService = contestService;
        this.contestAccess = contestAccess;
        this.users = users;
        this.problems = problems;
        this.submissions = submissions;
        this.testCases = testCases;
    }

    @GetMapping(ApiPaths.LEADERBOARD)
    public LeaderboardDto current(Principal principal) {
        Contest contest = contestService.requireCurrent();
        contestAccess.requireAccess(contest.getId(), principal.getName());
        return leaderboard.forStudents(contest);
    }

    /**
     * Standings box drill-down (FR-17 sibling): every attempt one participant
     * made on one problem, newest first. Public to any contestant, same as
     * the standings board itself — see AttemptSummaryDto for why that's safe
     * (no source code or compiler output in this DTO at all).
     *
     * Respects the same freeze semantics LeaderboardService.forContest()
     * applies to the board's cells: while FROZEN, an attempt only counts if
     * it had already been judged at the freeze instant. Without this, the
     * board could show "unsolved" for a problem while this popup — reachable
     * from that very cell — revealed a fresh AC the freeze exists to hide.
     */
    @GetMapping(ApiPaths.CONTEST_PARTICIPANT_PROBLEM_ATTEMPTS)
    public List<AttemptSummaryDto> attempts(@PathVariable long id,
                                            @PathVariable String username,
                                            @PathVariable String code,
                                            Principal principal) {
        Contest contest = contestAccess.requireReleasedAccess(id, principal.getName());
        User user = users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        Problem problem = problems.findByContestIdOrderByOrderingAscCodeAsc(id).stream()
                .filter(p -> p.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));

        boolean isFrozen = contest.getState() == ContestState.FROZEN;
        Instant freezeTime = contest.getFrozenAt();
        int totalTests = testCases.findByProblemIdOrderByIdxAsc(problem.getId()).size();

        return submissions.findByContestIdAndActiveTrueOrderByQueuedAtAsc(id).stream()
                .filter(s -> s.getUserId().equals(user.getId()))
                .filter(s -> s.getProblemId().equals(problem.getId()))
                .filter(s -> s.getVerdict() != null)   // still judging: nothing to show yet
                .filter(s -> !isFrozen || freezeTime == null || (
                        (s.getQueuedAt() == null || !s.getQueuedAt().isAfter(freezeTime))
                                && s.getJudgedAt() != null && !s.getJudgedAt().isAfter(freezeTime)))
                .sorted(Comparator.comparing(Submission::getQueuedAt).reversed())
                .map(s -> {
                    int passed = s.getVerdict() == Verdict.AC
                            ? totalTests
                            : Math.max(0, s.getFailedTestIndex() - 1);
                    return new AttemptSummaryDto(
                            s.getId(),
                            s.getLanguage(),
                            s.getVerdict(),
                            s.getExecTimeMs(),
                            s.getQueuedAt().toEpochMilli(),
                            s.getFailedTestIndex(),
                            passed,
                            totalTests);
                })
                .toList();
    }
}
