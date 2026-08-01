package com.labjudge.common.dto;

import com.labjudge.common.enums.Language;

/** Body of POST /api/submissions (FR-09). */
public record SubmitRequest(
        long problemId,
        Language language,
        String sourceCode
) {
}
