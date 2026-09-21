/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

import com.arbitrator.common.enums.Language;

/**
 * "Run against my own input" — compiles and executes without creating a
 * submission, so it never reaches the standings, the penalty count or the
 * submission history. Uses the problem only for its time and memory limits.
 */
public record CustomRunRequest(
        long problemId,
        Language language,
        String sourceCode,
        String input
) {
}
