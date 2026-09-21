/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

import java.util.List;

/**
 * Outcome of a problem-package upload (FR-05).
 * On rejection the package is discarded whole — never half-imported — and
 * {@code errors} explains exactly what was wrong (UC-09 exception flow).
 */
public record ProblemPackageResultDto(
        boolean accepted,
        Long problemId,        // null when rejected
        String code,
        String title,
        int testCaseCount,
        List<String> errors    // empty when accepted
) {

    public static ProblemPackageResultDto rejected(List<String> errors) {
        return new ProblemPackageResultDto(false, null, null, null, 0, errors);
    }
}
