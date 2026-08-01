package com.arbitrator.common.dto;

import com.arbitrator.common.enums.Language;

/** Body of POST /api/submissions (FR-09). */
public record SubmitRequest(
        long problemId,
        Language language,
        String sourceCode
) {
}
