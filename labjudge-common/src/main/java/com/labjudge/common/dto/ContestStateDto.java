package com.labjudge.common.dto;

import com.labjudge.common.enums.ContestState;

/**
 * The server clock is authoritative (FR-06, FMEA-06). The client derives its
 * countdown from (endTimeMs - serverTimeMs) at receipt, never from local time.
 */
public record ContestStateDto(
        long contestId,
        String title,
        ContestState state,
        long serverTimeMs,
        long startTimeMs,
        long endTimeMs
) {
}
