/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

import com.arbitrator.common.enums.Verdict;

/**
 * One test case a submission actually reached, with its data.
 *
 * Judging is fail-fast (BR-05), so the tests a submission reached are exactly
 * the ones it passed plus the single one that failed it — which is precisely
 * what may be shown. Tests beyond that point were never run and are never
 * disclosed, so the rest of the hidden set stays hidden.
 *
 * {@code input} and {@code expectedOutput} are truncated server-side; a test
 * file can be megabytes and nobody reads that in a panel.
 */
public record TestCaseResultDto(
        int index,               // 1-based, matches the judging order
        Verdict verdict,
        long execTimeMs,
        long peakMemoryKb,
        String input,
        String expectedOutput,
        /** What the contestant's program printed. Null for runs judged before
            V56 added the column — show it as unavailable, not as empty output. */
        String actualOutput,
        boolean truncated
) {
}
