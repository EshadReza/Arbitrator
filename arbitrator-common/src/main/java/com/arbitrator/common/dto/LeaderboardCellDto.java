package com.arbitrator.common.dto;

/**
 * One problem cell in a contestant's standings row (UIF-13).
 * Rendering: solved -> green "+N / mm"; attempted only -> red "-N"; empty otherwise.
 */
public record LeaderboardCellDto(
        String problemCode,
        boolean solved,
        int failedAttempts,      // rejected, excluding CE (BR-04)
        long solvedAtMinutes     // minutes from contest start; -1 when unsolved
) {
}
