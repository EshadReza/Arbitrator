/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

/**
 * Outcome of a custom run. Deliberately not a {@link com.arbitrator.common.enums.Verdict}:
 * there is no expected output to compare against, so "correct" is not a
 * question this can answer — it only reports what the program did.
 */
public record CustomRunResultDto(
        boolean compiled,
        String compilerOutput,   // non-empty when compiled == false
        String stdout,
        String stderr,
        long execTimeMs,
        boolean timedOut
) {
}
