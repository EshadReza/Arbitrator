package com.arbitrator.server.leaderboard;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arbitrator.common.dto.LeaderboardCellDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.service.ContestService;

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

        boolean isFrozen = contest.getState() == ContestState.FROZEN;
        Instant freezeTime = contest.getFrozenAt();

        // userId -> problemId -> running tally
        Map<Long, Map<Long, Tally>> byUser = new LinkedHashMap<>();
        for (Submission s : all) {
            if (s.getVerdict() == null) {
                continue;                       // still queued or judging
            }
            if (!problemCode.containsKey(s.getProblemId())) {
                continue;                       // problem removed from the contest
            }
            // FR-19: a freeze is a snapshot of what was KNOWN at the freeze
            // instant, for everybody including the viewer. Filtering only on
            // submission time was not enough: a submission sent before the
            // freeze but judged after it still landed on the board and moved
            // ranks, so the standings visibly changed while frozen. A result
            // counts only if it had already been judged when the freeze fell.
            if (isFrozen && freezeTime != null) {
                if (s.getQueuedAt() != null && s.getQueuedAt().isAfter(freezeTime)) {
                    continue;
                }
                if (s.getJudgedAt() == null || s.getJudgedAt().isAfter(freezeTime)) {
                    continue;
                }
            }
            Tally tally = byUser
                    .computeIfAbsent(s.getUserId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(s.getProblemId(), k -> new Tally());
            tally.accept(s, start);
        }

        Map<Long, Long> firstSolverByProblem = firstSolvers(byUser);

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
                    cells.add(new LeaderboardCellDto(p.getValue(), false, 0, -1, -1, false));
                    continue;
                }
                penalty += t.manualDelta;
                if (t.solved) {
                    solved++;
                    penalty += t.solvedAtMinutes + (long) PENALTY_PER_REJECT * t.rejectsBeforeAc;
                    lastAcceptedAt = Math.max(lastAcceptedAt, t.solvedAtMinutes);
                }
                boolean first = t.solved
                        && e.getKey().equals(firstSolverByProblem.get(p.getKey()));
                cells.add(new LeaderboardCellDto(p.getValue(), t.solved,
                        t.solved ? t.rejectsBeforeAc : t.rejects,
                        t.solved ? t.solvedAtMinutes : -1,
                        t.solved ? t.solvedAtSeconds : -1,
                        first));
            }
            penalty = Math.max(0, penalty);
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

    /**
     * Who took each problem down first — problemId to userId.
     *
     * Decided on the exact accepted instant rather than the displayed minute,
     * because two contestants solving inside the same minute is ordinary and
     * would otherwise both light up as "first". A genuine dead heat (identical
     * instant) falls back to the lower user id purely so the board is stable
     * between refreshes instead of flickering between two equally valid answers.
     *
     * Fed the same post-freeze-filter tallies the rows are built from, so a
     * frozen board never reveals a first solve the rest of it is hiding.
     */
    private static Map<Long, Long> firstSolvers(Map<Long, Map<Long, Tally>> byUser) {
        Map<Long, Long> owner = new LinkedHashMap<>();
        Map<Long, Instant> earliest = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<Long, Tally>> user : byUser.entrySet()) {
            long userId = user.getKey();
            for (Map.Entry<Long, Tally> p : user.getValue().entrySet()) {
                Tally t = p.getValue();
                if (!t.solved || t.acceptedAt == null) {
                    continue;
                }
                long problemId = p.getKey();
                Instant best = earliest.get(problemId);
                boolean wins = best == null
                        || t.acceptedAt.isBefore(best)
                        || (t.acceptedAt.equals(best) && userId < owner.get(problemId));
                if (wins) {
                    earliest.put(problemId, t.acceptedAt);
                    owner.put(problemId, userId);
                }
            }
        }
        return owner;
    }

    /** BR-04: CE never adds penalty. Everything else rejected does. */
    static boolean countsTowardPenalty(Verdict verdict) {
        return verdict != Verdict.AC && verdict != Verdict.CE;
    }

    /** Per-user, per-problem running state. */
    private static final class Tally {

        boolean solved;
        long solvedAtMinutes = -1;
        long solvedAtSeconds = -1;
        /** Exact instant of the accepted submission — only for ordering first solves. */
        Instant acceptedAt;
        int rejects;            // all rejected attempts (for the unsolved display)
        int rejectsBeforeAc;    // only those before the first AC — what FR-18 charges
        int manualDelta;

        void accept(Submission s, Instant contestStart) {
            manualDelta += s.getManualPenaltyDelta();
            if (solved) {
                // BR-03: later submissions never alter a solved problem, even another AC.
                return;
            }
            if (s.getVerdict() == Verdict.AC) {
                solved = true;
                rejectsBeforeAc = rejects;
                acceptedAt = s.getQueuedAt();
                // FR-18 charges the SUBMISSION time, not when judging finished —
                // a slow judge must never cost a contestant penalty minutes.
                Duration elapsed = Duration.between(contestStart, s.getQueuedAt());
                solvedAtMinutes = Math.max(0, elapsed.toMinutes());
                solvedAtSeconds = Math.max(0, elapsed.getSeconds());
            } else if (countsTowardPenalty(s.getVerdict())) {
                rejects++;
            }
        }
    }
}
