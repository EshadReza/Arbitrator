package com.arbitrator.server.controller;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;
import com.arbitrator.common.dto.ParticipantDto;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.leaderboard.LeaderboardService;
import com.arbitrator.server.realtime.PresenceTracker;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
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
    private final UserRepository users;
    private final SubmissionService submissionService;

    public AdminMonitorController(ContestService contestService,
                                  LeaderboardService leaderboard,
                                  PresenceTracker presence,
                                  SubmissionRepository submissions,
                                  ProblemRepository problems,
                                  UserRepository users,
                                  SubmissionService submissionService) {
        this.contestService = contestService;
        this.leaderboard = leaderboard;
        this.presence = presence;
        this.submissions = submissions;
        this.problems = problems;
        this.users = users;
        this.submissionService = submissionService;
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
        users.findAll().forEach(u -> userNames.put(u.getId(), u.getUsername()));

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
            String display = users.findByUsername(username)
                    .map(User::getDisplayName).orElse(username);
            out.add(new ParticipantDto(
                    username,
                    display,
                    online.contains(username),
                    row == null ? 0 : row.rank(),
                    row == null ? 0 : row.solved(),
                    row == null ? 0 : row.penaltyMinutes(),
                    counts.getOrDefault(username, 0),
                    latest.getOrDefault(username, -1L)));
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
        Map<Long, String> codes = new HashMap<>();
        problems.findAll().forEach(p -> codes.put(p.getId(), p.getCode()));
        Map<Long, String> userNames = new HashMap<>();
        users.findAll().forEach(u -> userNames.put(u.getId(), u.getUsername()));

        List<Submission> all =
                new ArrayList<>(submissions.findByContestIdAndActiveTrueOrderByQueuedAtAsc(id));
        java.util.Collections.reverse(all);

        return all.stream()
                .map(s -> new SubmissionHistoryDto(
                        s.getId(),
                        // problem code carries the author so one table shows both
                        codes.getOrDefault(s.getProblemId(), "?") + " · "
                                + userNames.getOrDefault(s.getUserId(), "?"),
                        s.getLanguage(),
                        s.getVerdict(),
                        s.getExecTimeMs(),
                        s.getPeakMemoryKb(),
                        s.getQueuedAt().toEpochMilli()))
                .toList();
    }

    /** Admins may read any submission's code (post-contest review, FR-22). */
    @GetMapping(ApiPaths.ADMIN_SUBMISSION_SOURCE)
    public SubmissionSourceDto source(@PathVariable long id, Principal principal) {
        return submissionService.source(principal.getName(), id, true);
    }

    /** Also expose the raw connected count for the dashboard header. */
    @GetMapping("/api/admin/contests/{id}/online")
    public Map<String, Integer> online(@PathVariable long id) {
        return Map.of("online", presence.onlineCount(id),
                "totalConnected", presence.totalConnected());
    }
}
