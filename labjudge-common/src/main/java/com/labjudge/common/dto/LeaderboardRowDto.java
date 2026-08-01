package com.labjudge.common.dto;

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
