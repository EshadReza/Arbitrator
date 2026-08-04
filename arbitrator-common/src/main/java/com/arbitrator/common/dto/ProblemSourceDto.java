package com.arbitrator.common.dto;

import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;

/**
 * Best submission source code per problem for a participant.
 */
public record ProblemSourceDto(
        long submissionId,
        String problemCode,
        String problemTitle,
        Language language,
        Verdict verdict,
        long execTimeMs,
        String sourceCode,
        String compilerOutput,
        long submittedAtMs
) {
}
