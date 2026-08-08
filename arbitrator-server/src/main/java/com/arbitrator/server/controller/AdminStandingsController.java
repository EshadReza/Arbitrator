package com.arbitrator.server.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.VerdictEventDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;
import com.arbitrator.common.dto.ProblemSourceDto;
import com.arbitrator.common.dto.StandingsEntryDto;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.leaderboard.LeaderboardService;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.leaderboard.LeaderboardBroadcaster;
import com.arbitrator.server.realtime.VerdictPublisher;
import com.arbitrator.server.service.ContestService;

@RestController
public class AdminStandingsController {

    private final ContestService contestService;
    private final LeaderboardService leaderboardService;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final VerdictPublisher verdictPublisher;
    private final LeaderboardBroadcaster leaderboardBroadcaster;

    public AdminStandingsController(ContestService contestService,
                                    LeaderboardService leaderboardService,
                                    SubmissionRepository submissionRepository,
                                    UserRepository userRepository,
                                    ProblemRepository problemRepository,
                                    VerdictPublisher verdictPublisher,
                                    LeaderboardBroadcaster leaderboardBroadcaster) {
        this.contestService = contestService;
        this.leaderboardService = leaderboardService;
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.problemRepository = problemRepository;
        this.verdictPublisher = verdictPublisher;
        this.leaderboardBroadcaster = leaderboardBroadcaster;
    }

    @GetMapping(ApiPaths.ADMIN_STANDINGS)
    public List<StandingsEntryDto> getStandings(@PathVariable long id) {
        Contest contest = contestService.require(id);
        LeaderboardDto leaderboard = leaderboardService.forContest(contest);
        List<Submission> activeSubmissions = submissionRepository.findByContestIdAndActiveTrueOrderByQueuedAtAsc(id);
        
        Map<Long, String> userIdToUsername = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        
        Map<String, List<Submission>> subsByUsername = activeSubmissions.stream()
                .filter(s -> userIdToUsername.containsKey(s.getUserId()))
                .collect(Collectors.groupingBy(s -> userIdToUsername.get(s.getUserId())));
        
        List<StandingsEntryDto> result = new ArrayList<>();
        
        for (LeaderboardRowDto row : leaderboard.rows()) {
            List<Submission> userSubs = subsByUsername.getOrDefault(row.username(), List.of());
            Submission best = null;
            Integer currentMarks = null;

            for (Submission sub : userSubs) {
                if (sub.getMarks() != null) {
                    currentMarks = sub.getMarks();
                }
                if (sub.getVerdict() == Verdict.AC) {
                    if (best == null || sub.getExecTimeMs() < best.getExecTimeMs()) {
                        best = sub;
                    }
                }
            }
            
            // If no AC submission, pick the latest submission if any exists
            if (best == null && !userSubs.isEmpty()) {
                best = userSubs.get(userSubs.size() - 1);
            }
            
            if (best != null) {
                result.add(new StandingsEntryDto(
                        row.username(),
                        row.displayName(),
                        row.solved(),
                        row.penaltyMinutes(),
                        row.cells(),
                        currentMarks,
                        best.getId(),
                        best.getVerdict() != null ? best.getVerdict().name() : null,
                        best.getExecTimeMs(),
                        best.getLanguage() != null ? best.getLanguage().name() : null
                ));
            } else {
                result.add(new StandingsEntryDto(
                        row.username(),
                        row.displayName(),
                        row.solved(),
                        row.penaltyMinutes(),
                        row.cells(),
                        null,
                        null,
                        null,
                        0,
                        null
                ));
            }
        }
        
        // Do NOT re-sort here: leaderboard.rows() above is already ordered by
        // BR-06 rank (solved desc, penalty asc, earliest last-AC, username) —
        // re-sorting by username alone destroyed that rank order.
        return result;
    }

    @GetMapping(ApiPaths.ADMIN_PARTICIPANT_CODES)
    public List<ProblemSourceDto> getParticipantCodes(@PathVariable long id, @PathVariable String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        
        List<Submission> userSubs = submissionRepository.findByContestIdAndActiveTrueOrderByQueuedAtAsc(id).stream()
                .filter(s -> s.getUserId().equals(user.getId()))
                .toList();

        Map<Long, Problem> problemMap = problemRepository.findAll().stream()
                .collect(Collectors.toMap(Problem::getId, p -> p));

        Map<Long, List<Submission>> byProblem = userSubs.stream()
                .collect(Collectors.groupingBy(Submission::getProblemId));

        List<ProblemSourceDto> result = new ArrayList<>();
        for (Map.Entry<Long, List<Submission>> entry : byProblem.entrySet()) {
            Problem problem = problemMap.get(entry.getKey());
            String code = problem != null ? problem.getCode() : "?";
            String title = problem != null ? problem.getTitle() : "?";

            // Pick best AC submission or latest submission for this problem
            Submission best = entry.getValue().stream()
                    .filter(s -> s.getVerdict() == Verdict.AC)
                    .min(Comparator.comparingLong(Submission::getExecTimeMs))
                    .orElseGet(() -> entry.getValue().get(entry.getValue().size() - 1));

            result.add(new ProblemSourceDto(
                    best.getId(),
                    code,
                    title,
                    best.getLanguage(),
                    best.getVerdict(),
                    best.getExecTimeMs(),
                    best.getSourceCode(),
                    best.getCompilerOutput(),
                    best.getQueuedAt().toEpochMilli()
            ));
        }

        result.sort(Comparator.comparing(ProblemSourceDto::problemCode));
        return result;
    }

    @PostMapping(ApiPaths.ADMIN_PARTICIPANT_MARKS)
    public ResponseEntity<Void> setParticipantMarks(@PathVariable long id,
                                                    @PathVariable String username,
                                                    @RequestParam int value) {
        if (value < 0 || value > 100) {
            return ResponseEntity.badRequest().build();
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        List<Submission> userSubs = submissionRepository.findByContestIdAndActiveTrueOrderByQueuedAtAsc(id).stream()
                .filter(s -> s.getUserId().equals(user.getId()))
                .toList();

        for (Submission s : userSubs) {
            s.setMarks(value);
            submissionRepository.save(s);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping(ApiPaths.ADMIN_SET_MARKS)
    public ResponseEntity<Void> setMarks(@PathVariable long id, @RequestParam int value) {
        if (value < 0 || value > 100) {
            return ResponseEntity.badRequest().build();
        }
        
        return submissionRepository.findById(id).map(submission -> {
            submission.setMarks(value);
            submissionRepository.save(submission);
            return ResponseEntity.ok().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(ApiPaths.ADMIN_OVERRIDE_VERDICT)
    public ResponseEntity<Void> overrideVerdict(@PathVariable long id, @RequestParam String verdict) {
        try {
            Verdict v = Verdict.valueOf(verdict.toUpperCase());
            return submissionRepository.findById(id).map(s -> {
                s.setVerdict(v);
                submissionRepository.save(s);
                // An override is a verdict like any other, so it travels the
                // same way one does (FR-15). Without this the change lived only
                // in the database: the student's banner, badge and history all
                // kept showing the machine's original answer until they signed
                // out and back in.
                userRepository.findById(s.getUserId()).ifPresent(u ->
                        verdictPublisher.publishVerdict(u.getUsername(), new VerdictEventDto(
                                s.getId(), s.getProblemId(), v,
                                s.getExecTimeMs(), s.getPeakMemoryKb(),
                                s.getCompilerOutput(), s.getFailedTestIndex())));
                leaderboardBroadcaster.broadcastNow();
                return ResponseEntity.ok().<Void>build();
            }).orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping(ApiPaths.ADMIN_ADJUST_PENALTY)
    public ResponseEntity<Void> adjustPenalty(@PathVariable long id,
                                              @PathVariable String username,
                                              @RequestParam int delta) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        List<Submission> userSubs = submissionRepository.findByContestIdAndActiveTrueOrderByQueuedAtAsc(id).stream()
                .filter(s -> s.getUserId().equals(user.getId()))
                .toList();

        if (userSubs.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Submission latest = userSubs.get(userSubs.size() - 1);
        latest.setManualPenaltyDelta(latest.getManualPenaltyDelta() + delta);
        submissionRepository.save(latest);

        return ResponseEntity.ok().build();
    }
}
