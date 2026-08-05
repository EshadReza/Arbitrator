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
        List<TestCaseResultDto> tests   // empty when visible == false
) {

    public static SubmissionTestsDto hidden(long submissionId, int passed, int total) {
        return new SubmissionTestsDto(submissionId, false, passed, total, List.of());
    }
}
