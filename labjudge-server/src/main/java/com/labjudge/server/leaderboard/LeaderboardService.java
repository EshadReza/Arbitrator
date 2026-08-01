package com.labjudge.server.leaderboard;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.labjudge.common.dto.LeaderboardCellDto;
import com.labjudge.common.dto.LeaderboardDto;
import com.labjudge.common.dto.LeaderboardRowDto;
import com.labjudge.common.enums.ContestState;
import com.labjudge.common.enums.Verdict;
import com.labjudge.server.entity.Contest;
import com.labjudge.server.entity.Problem;
import com.labjudge.server.entity.Submission;
import com.labjudge.server.entity.User;
import com.labjudge.server.repo.ProblemRepository;
import com.labjudge.server.repo.SubmissionRepository;
import com.labjudge.server.repo.UserRepository;
import com.labjudge.server.service.ContestService;

/**
 * ICPC-style standings (FR-17, FR-18, BR-03, BR-04, BR-06).
 *
 * Penalty for each solved problem:
 *     minutes(firstAC - contestStart) + 20 * (rejected attempts before that AC)
 *
 * SPEC NOTE — FR-18 literally says "20 x number of prior WA submissions", but
 * BR-04 separately says compilation errors must not count. If only literal WA
 * counted, BR-04 would be redundant (a CE is not a WA). So the intended rule is
 * the standard ICPC one: every prior rejected attempt counts except CE — that
 * is, WA / TLE / MLE / RE. That is what {@link #PENALTY_PER_REJECT} multiplies.
 * Confirm with the supervisor before acceptance; if they want literal-WA-only,
 * change {@link #countsTowardPenalty} and nothing else.
 */
@Service
public class LeaderboardService {

    /** Minutes added per rejected attempt before the accepted one (FR-18). */
    static final int PENALTY_PER_REJECT = 20;

    private final SubmissionRepository submissions;
    private final ProblemRepository problems;
    private final UserRepository users;
    private final ContestService contestService;

    public LeaderboardService(SubmissionRepository submissions,
                              ProblemRepository problems,
                              UserRepository users,
                              ContestService contestService) {
        this.submissions = submissions;
        this.problems = problems;
        this.users = users;
        this.contestService = contestService;
    }

    @Transactional(readOnly = true)
    public LeaderboardDto current() {
        return forContest(contestService.requireCurrent());
    }

    @Transactional(readOnly = true)
    public LeaderboardDto forContest(Contest contest) {
        List<Problem> contestProblems =
                problems.findByContestIdOrderByOrderingAscCodeAsc(contest.getId());
        List<Submission> all =
                submissions.findByContestIdAndActiveTrueOrderByQueuedAtAsc(contest.getId());

        Map<Long, String> problemCode = new LinkedHashMap<>();
        contestProblems.forEach(p -> problemCode.put(p.getId(), p.getCode()));

        Map<Long, User> userById = new LinkedHashMap<>();
        users.findAll().forEach(u -> userById.put(u.getId(), u));

        Instant start = contest.getStartTime() == null ? Instant.EPOCH : contest.getStartTime();

        // userId -> problemId -> running tally
        Map<Long, Map<Long, Tally>> byUser = new LinkedHashMap<>();
        for (Submission s : all) {
            if (s.getVerdict() == null) {
                continue;                       // still queued or judging
            }
            if (!problemCode.containsKey(s.getProblemId())) {
                continue;                       // problem removed from the contest
            }
            Tally tally = byUser
                    .computeIfAbsent(s.getUserId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(s.getProblemId(), k -> new Tally());
            tally.accept(s, start);
        }

        List<LeaderboardRowDto> rows = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, Tally>> e : byUser.entrySet()) {
            User user = userById.get(e.getKey());
            if (user == null) {
                continue;
            }
            int solved = 0;
            long penalty = 0;
            long lastAcceptedAt = 0;
            List<LeaderboardCellDto> cells = new ArrayList<>();

            for (Map.Entry<Long, String> p : problemCode.entrySet()) {
                Tally t = e.getValue().get(p.getKey());
                if (t == null) {
                    cells.add(new LeaderboardCellDto(p.getValue(), false, 0, -1));
                    continue;
                }
                if (t.solved) {
                    solved++;
                    penalty += t.solvedAtMinutes + (long) PENALTY_PER_REJECT * t.rejectsBeforeAc;
                    lastAcceptedAt = Math.max(lastAcceptedAt, t.solvedAtMinutes);
                }
                cells.add(new LeaderboardCellDto(p.getValue(), t.solved,
                        t.solved ? t.rejectsBeforeAc : t.rejects,
                        t.solved ? t.solvedAtMinutes : -1));
            }
            rows.add(new LeaderboardRowDto(0, user.getUsername(), user.getDisplayName(),
                    solved, penalty, cells));
        }

        // BR-06: most solved, then least penalty, then earliest last accepted.
        Map<String, Long> lastAc = new LinkedHashMap<>();
        rows.forEach(r -> lastAc.put(r.username(), r.cells().stream()
                .filter(LeaderboardCellDto::solved)
                .mapToLong(LeaderboardCellDto::solvedAtMinutes)
                .max().orElse(Long.MAX_VALUE)));

        rows.sort(Comparator
                .comparingInt(LeaderboardRowDto::solved).reversed()
                .thenComparingLong(LeaderboardRowDto::penaltyMinutes)
                .thenComparingLong(r -> lastAc.getOrDefault(r.username(), Long.MAX_VALUE))
                .thenComparing(LeaderboardRowDto::username));

        List<LeaderboardRowDto> ranked = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            LeaderboardRowDto r = rows.get(i);
            ranked.add(new LeaderboardRowDto(i + 1, r.username(), r.displayName(),
                    r.solved(), r.penaltyMinutes(), r.cells()));
        }

        return new LeaderboardDto(
                contest.getId(),
                contest.getState() == ContestState.FROZEN,
                System.currentTimeMillis(),
                List.copyOf(problemCode.values()),
                ranked);
    }

    /** BR-04: CE never adds penalty. Everything else rejected does. */
    static boolean countsTowardPenalty(Verdict verdict) {
        return verdict != Verdict.AC && verdict != Verdict.CE;
    }

    /** Per-user, per-problem running state. */
    private static final class Tally {

        boolean solved;
        long solvedAtMinutes = -1;
        int rejects;            // all rejected attempts (for the unsolved display)
        int rejectsBeforeAc;    // only those before the first AC — what FR-18 charges

        void accept(Submission s, Instant contestStart) {
            if (solved) {
                // BR-03: later submissions never alter a solved problem, even another AC.
                return;
            }
            if (s.getVerdict() == Verdict.AC) {
                solved = true;
                rejectsBeforeAc = rejects;
                // FR-18 charges the SUBMISSION time, not when judging finished —
                // a slow judge must never cost a contestant penalty minutes.
                long minutes = Duration.between(contestStart, s.getQueuedAt()).toMinutes();
                solvedAtMinutes = Math.max(0, minutes);
            } else if (countsTowardPenalty(s.getVerdict())) {
                rejects++;
            }
        }
    }
}
