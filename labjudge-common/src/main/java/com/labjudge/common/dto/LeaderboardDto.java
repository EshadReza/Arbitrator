package com.labjudge.common.dto;

import java.util.List;

/**
 * Full standings, pushed every 30 s while a contest runs (FR-17) and also
 * fetchable over REST. When {@code frozen} is true the rows are the snapshot
 * taken at freeze time — judging continues underneath (FR-19).
 */
public record LeaderboardDto(
        long contestId,
        boolean frozen,
        long lastUpdatedMs,
        List<String> problemCodes,   // column order, matches each row's cells
        List<LeaderboardRowDto> rows
) {
}
