/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

import java.util.List;

/**
 * Test results for one submission, plus whether the caller was allowed to see
 * the data at all.
 *
 * {@code visible} is carried rather than answering 403, so the client can say
 * "your instructor has not enabled this" instead of showing an error — a
 * disabled feature is not a failure. Admins always get {@code visible = true}.
 */
public record SubmissionTestsDto(
        long submissionId,
        boolean visible,
        int passedCount,
        int totalTestCases,
        List<TestCaseResultDto> tests,   // empty when visible == false, or checkerSummary != null
        /**
         * Set instead of populating {@code tests} for a checker-graded
         * (CheckerType.CUSTOM) problem: a special judge can accept more than
         * one valid output, so showing "the" expected output next to the
         * participant's would not just leak the checker's test data, it would
         * actively misstate what was being graded. Null for exact-match
         * problems, where the per-test breakdown is exactly what it looks like.
         */
        String checkerSummary
) {

    public static SubmissionTestsDto hidden(long submissionId, int passed, int total) {
        return new SubmissionTestsDto(submissionId, false, passed, total, List.of(), null);
    }
}
