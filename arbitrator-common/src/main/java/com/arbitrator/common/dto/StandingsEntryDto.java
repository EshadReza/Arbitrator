/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

import java.util.List;

/**
 * One participant's row in the admin standings-with-marks table.
 * Carries the leaderboard data plus the instructor-assigned marks and
 * the "best submission" (accepted with lowest execution time).
 */
public record StandingsEntryDto(
        String username,
        String displayName,
        int solved,
        long penaltyMinutes,
        List<LeaderboardCellDto> cells,
        Integer marks,              // null = not yet assigned
        Long bestSubmissionId,      // null = no AC submission
        String bestVerdict,
        long bestExecTimeMs,
        String bestLanguage
) {}
