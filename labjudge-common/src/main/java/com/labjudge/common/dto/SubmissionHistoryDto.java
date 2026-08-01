package com.labjudge.common.dto;

import com.labjudge.common.enums.Language;
import com.labjudge.common.enums.Verdict;

/** One row of the personal submission history table (FR-16, UIF-12). */
public record SubmissionHistoryDto(
        long id,
        String problemCode,
        Language language,
        Verdict verdict,        // null while still queued/judging
        long execTimeMs,
        long peakMemoryKb,
        long submittedAtMs      // epoch millis, server clock
) {
}
