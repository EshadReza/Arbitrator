package com.arbitrator.common.dto;

/**
 * One problem cell in a contestant's standings row (UIF-13).
 *
 * Rendering (see LeaderboardPanelController): first accepted in the whole
 * contest -> dark green; any other accepted -> light green; attempted but
 * never solved -> pink with the attempt count; untouched -> blank.
 */
public record LeaderboardCellDto(
        String problemCode,
        boolean solved,
        int failedAttempts,      // rejected, excluding CE (BR-04)
        long solvedAtMinutes,    // minutes from contest start; -1 when unsolved
        /**
         * The same instant as {@link #solvedAtMinutes} but in seconds, so the
         * board can show when the problem fell to the second (HH:MM:SS) the
         * way an ICPC scoreboard does. Minutes remain the unit penalty is
         * charged in (FR-18), so both are carried rather than one derived from
         * the other — rounding a displayed clock and a scored penalty the same
         * way is a coincidence, not a rule.
         */
        long solvedAtSeconds,
        /**
         * True on the single cell that solved this problem earliest in the
         * whole contest — the scoreboard's "first blood". Decided server-side
         * on the exact submission instant, because minute (or even second)
         * granularity can tie and the client has no way to break it.
         */
        boolean firstSolve
) {
}
