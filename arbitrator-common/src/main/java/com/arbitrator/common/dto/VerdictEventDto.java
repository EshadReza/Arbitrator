package com.arbitrator.common.dto;

import com.arbitrator.common.enums.Verdict;

/**
 * Pushed to /user/queue/verdicts within 30 s of submission receipt (FR-15).
 * compilerOutput is non-null only for CE, already truncated to 4096 chars
 * server-side (FR-20). failedTestIndex is 1-based, -1 when not applicable.
 */
public record VerdictEventDto(
        long submissionId,
        long problemId,
        Verdict verdict,
        long execTimeMs,
        long peakMemoryKb,
        String compilerOutput,
        int failedTestIndex
) {
}
