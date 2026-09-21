/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

import java.util.List;

/** One contestant's standings row (FR-17, UIF-13). */
public record LeaderboardRowDto(
        int rank,
        String username,
        String displayName,
        int solved,
        long penaltyMinutes,
        List<LeaderboardCellDto> cells
) {
}
