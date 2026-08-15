package com.arbitrator.server.controller;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;
import com.arbitrator.common.dto.NotificationDto;
import com.arbitrator.common.dto.ParticipantDto;
import com.arbitrator.common.dto.ParticipantSubmissionsDto;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.leaderboard.LeaderboardService;
import com.arbitrator.server.realtime.ContestStatePublisher;
import com.arbitrator.server.realtime.NotificationService;
import com.arbitrator.server.realtime.PresenceTracker;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.service.ContestService;
import com.arbitrator.server.service.SubmissionService;

/**
 * Live monitoring for the instructor (UIF-21): who is connected, how everyone
 * is doing, and the code behind any submission. Loopback + ADMIN JWT (D3).
 */
@RestController
public class AdminMonitorController {

    private final ContestService contestService;
    private final LeaderboardService leaderboard;
    private final PresenceTracker presence;
    private final SubmissionRepository submissions;
    private final ProblemRepository problems;
    private final TestCaseRepository testCases;
    private final UserRepository users;
    private final SubmissionService submissionService;
    private final ContestStatePublisher statePublisher;
    private final NotificationService notificationService;

    public AdminMonitorController(ContestService contestService,
                                  LeaderboardService leaderboard,
                                  PresenceTracker presence,
                                  SubmissionRepository submissions,
                                  ProblemRepository problems,
                                  TestCaseRepository testCases,
                                  UserRepository users,
                                  SubmissionService submissionService,
                                  ContestStatePublisher statePublisher,
                                  NotificationService notificationService) {
        this.contestService = contestService;
        this.leaderboard = leaderboard;
        this.presence = presence;
        this.submissions = submissions;
        this.problems = problems;
        this.testCases = testCases;
        this.users = users;
        this.submissionService = submissionService;
        this.statePublisher = statePublisher;
        this.notificationService = notificationService;
    }

    /** Newest first — the console's toast stack and Notifications tab both poll this. */
    @GetMapping(ApiPaths.ADMIN_NOTIFICATIONS)
    public List<NotificationDto> notifications() {
        return notificationService.current();
    }

    /** The instructor dismissed one; it never reappears. */
    @DeleteMapping(ApiPaths.ADMIN_NOTIFICATION_BY_ID)
    public void dismissNotification(@PathVariable long id) {
        if (!notificationService.dismiss(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such notification");
        }
    }

    /** The instructor dismissed one user's whole run of entries at once. */
    @DeleteMapping(ApiPaths.ADMIN_NOTIFICATIONS_BY_USER)
    public void dismissUserNotifications(@PathVariable String username) {
        if (!notificationService.dismissAllForUser(username)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No notifications for that user");
        }
    }

    /**
     * Everyone who has taken part, merged with who is connected right now.
     * Someone who submitted then closed the app still appears — offline.
     */
    @GetMapping(ApiPaths.ADMIN_PARTICIPANTS)
    public List<ParticipantDto> participants(@PathVariable long id) {
        Contest contest = contestService.require(id);
        LeaderboardDto board = leaderboard.forContest(contest);
        Set<String> online = presence.onlineIn(id);

        Map<String, LeaderboardRowDto> byUser = new HashMap<>();
        board.rows().forEach(r -> byUser.put(r.username(), r));

        Map<Long, String> userNames = new HashMap<>();
        Map<String, User> byUsername = new HashMap<>();
        users.findAll().forEach(u -> {
            userNames.put(u.getId(), u.getUsername());
            byUsername.put(u.getUsername(), u);
        });

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> latest = new HashMap<>();
        for (Submission s : submissions.findByContestIdAndActiveTrueOrderByQueuedAtAsc(id)) {
            String name = userNames.get(s.getUserId());
            if (name == null) {
                continue;
            }
            counts.merge(name, 1, Integer::sum);
            latest.merge(name, s.getQueuedAt().toEpochMilli(), Math::max);
        }

        // Union of "has a row on the board", "has submitted" and "is connected".
        Set<String> everyone = new java.util.LinkedHashSet<>(byUser.keySet());
        everyone.addAll(counts.keySet());
        everyone.addAll(online);

        List<ParticipantDto> out = new ArrayList<>();
        for (String username : everyone) {
            LeaderboardRowDto row = byUser.get(username);
            User u = byUsername.get(username);
            String display = u == null ? username : u.getDisplayName();
            out.add(new ParticipantDto(
                    username,
                    display,
                    online.contains(username),
                    row == null ? 0 : row.rank(),
                    row == null ? 0 : row.solved(),
                    row == null ? 0 : row.penaltyMinutes(),
                    counts.getOrDefault(username, 0),
                    latest.getOrDefault(username, -1L),
                    u == null ? null : u.getMacAddress(),
                    u == null || u.getMacChangedAt() == null ? -1L : u.getMacChangedAt().toEpochMilli()));
        }
        // Connected first, then by rank; unranked entrants sink to the bottom.
        out.sort((a, b) -> {
            if (a.online() != b.online()) {
                return a.online() ? -1 : 1;
            }
            int ra = a.rank() == 0 ? Integer.MAX_VALUE : a.rank();
            int rb = b.rank() == 0 ? Integer.MAX_VALUE : b.rank();
            return Integer.compare(ra, rb);
        });
        return out;
    }

    /** Every submission in the contest, newest first — the instructor's feed. */
    @GetMapping(ApiPaths.ADMIN_CONTEST_SUBMISSIONS)
    public List<SubmissionHistoryDto> contestSubmissions(@PathVariable long id) {
        Map<Long, Problem> problemMap = new java.util.HashMap<>();
        problems.findAll().forEach(p -> problemMap.put(p.getId(), p));
        Map<Long, String> userNames = new HashMap<>();
        users.findAll().forEach(u -> userNames.put(u.getId(), u.getUsername()));

        List<Submission> all =
                new ArrayList<>(submissions.findByContestIdAndActiveTrueOrderByQueuedAtAsc(id));
        java.util.Collections.reverse(all);

        Map<Long, Integer> testCountMap = new HashMap<>();

        return all.stream()
                .map(s -> {
                    Problem p = problemMap.get(s.getProblemId());
                    int total = testCountMap.computeIfAbsent(s.getProblemId(),
                            pid -> testCases.findByProblemIdOrderByIdxAsc(pid).size());
                    int passed = (s.getVerdict() == com.arbitrator.common.enums.Verdict.AC) ? total : Math.max(0, s.getFailedTestIndex() - 1);
                    String codeStr = (p != null ? p.getCode() : "?") + " · " + userNames.getOrDefault(s.getUserId(), "?");
                    return new SubmissionHistoryDto(
                            s.getId(),
                            s.getContestId(),
                            codeStr,
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

    @PostMapping(ApiPaths.ADMIN_CONTEST_SCHEDULE)
    public ContestStateDto scheduleContest(@PathVariable long id, @RequestParam int minutes) {
        Instant startAt = Instant.now().plusSeconds(minutes * 60L);
        Contest c = contestService.scheduleLobby(id, startAt);
        statePublisher.publish(c);
        return contestService.stateOf(c);
    }

    /** Admins may read any submission's code (post-contest review, FR-22). */
    @GetMapping(ApiPaths.ADMIN_SUBMISSION_SOURCE)
    public SubmissionSourceDto source(@PathVariable long id, Principal principal) {
        return submissionService.source(principal.getName(), id, true);
    }

    /**
     * Tests behind any submission. Never gated by the contest toggle — that
     * switch governs what contestants see, not the instructor grading them.
     */
    @GetMapping(ApiPaths.ADMIN_SUBMISSION_TESTS)
    public com.arbitrator.common.dto.SubmissionTestsDto tests(@PathVariable long id,
                                                              Principal principal) {
        return submissionService.tests(principal.getName(), id, true);
    }

    /** Also expose the raw connected count for the dashboard header. */
    @GetMapping("/api/admin/contests/{id}/online")
    public Map<String, Integer> online(@PathVariable long id) {
        return Map.of("online", presence.onlineCount(id),
                "totalConnected", presence.totalConnected());
    }

    @GetMapping(ApiPaths.ADMIN_GROUPED_SUBMISSIONS)
    public List<ParticipantSubmissionsDto> groupedSubmissions(@PathVariable long id) {
        List<Submission> all = submissions.findByContestIdAndActiveTrueOrderByQueuedAtAsc(id);
        
        Map<Long, User> userMap = new java.util.HashMap<>();
        users.findAll().forEach(u -> userMap.put(u.getId(), u));
        
        Map<Long, Problem> problemMap = new java.util.HashMap<>();
        problems.findAll().forEach(p -> problemMap.put(p.getId(), p));
        Map<Long, Integer> testCountMap = new HashMap<>();
        
        Map<Long, List<Submission>> byUser = new java.util.HashMap<>();
        for (Submission s : all) {
            byUser.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(s);
        }
        
        List<ParticipantSubmissionsDto> result = new ArrayList<>();
        
        for (Map.Entry<Long, List<Submission>> entry : byUser.entrySet()) {
            Long userId = entry.getKey();
            List<Submission> userSubs = entry.getValue();
            
            User user = userMap.get(userId);
            if (user == null) continue;
            
            int total = userSubs.size();
            int acCount = 0;
            String bestVerdict = null;
            
            for (Submission s : userSubs) {
                if (s.getVerdict() == com.arbitrator.common.enums.Verdict.AC) {
                    acCount++;
                }
            }
            if (acCount > 0) {
                bestVerdict = com.arbitrator.common.enums.Verdict.AC.name();
            } else {
                for (int i = userSubs.size() - 1; i >= 0; i--) {
                    if (userSubs.get(i).getVerdict() != null) {
                        bestVerdict = userSubs.get(i).getVerdict().name();
                        break;
                    }
                }
            }
            
            List<SubmissionHistoryDto> history = new ArrayList<>();
            for (int i = userSubs.size() - 1; i >= 0; i--) {
                Submission s = userSubs.get(i);
                Problem p = problemMap.get(s.getProblemId());
                int totalTests = testCountMap.computeIfAbsent(s.getProblemId(),
                        pid -> testCases.findByProblemIdOrderByIdxAsc(pid).size());
                int passedTests = (s.getVerdict() == com.arbitrator.common.enums.Verdict.AC) ? totalTests : Math.max(0, s.getFailedTestIndex() - 1);
                String codeStr = p != null ? "Problem " + p.getCode() + " — " + p.getTitle() : "?";
                history.add(new SubmissionHistoryDto(
                        s.getId(),
                        s.getContestId(),
                        codeStr,
                        s.getLanguage(),
                        s.getVerdict(),
                        s.getExecTimeMs(),
                        s.getPeakMemoryKb(),
                        s.getQueuedAt().toEpochMilli(),
                        passedTests,
                        totalTests
                ));
            }
            
            result.add(new ParticipantSubmissionsDto(
                    user.getUsername(),
                    user.getDisplayName(),
                    total,
                    acCount,
                    bestVerdict,
                    history
            ));
        }
        
        result.sort(java.util.Comparator.comparing(ParticipantSubmissionsDto::username));
        return result;
    }
}
