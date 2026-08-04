package com.arbitrator.common.dto;

import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;

/** One row of the personal submission history table (FR-16, UIF-12). */
public record SubmissionHistoryDto(
        long id,
        String problemCode,
        Language language,
        Verdict verdict,        // null while still queued/judging
        long execTimeMs,
        long peakMemoryKb,
        long submittedAtMs,     // epoch millis, server clock
        int passedTestCount,
        int totalTestCases
) {
}
