/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

import com.arbitrator.common.enums.ContestState;

/**
 * One row in the contest picker (student) or the admin contest list.
 * Times are epoch millis on the server clock, which is authoritative (FR-06).
 */
public record ContestSummaryDto(
        long id,
        String title,
        ContestState state,
        long startTimeMs,      // -1 when never started
        long endTimeMs,        // -1 when never started; already pause-adjusted
        int durationMinutes,
        int problemCount,
        boolean joinable,
        boolean passwordProtected   // never the password/hash itself — just whether one is needed
) {
}
