package com.arbitrator.common.dto;

/**
 * One row of the left-panel problem list (SRS §4.2).
 * solved / failedAttempts drive the status badge:
 * green check when solved, red count when attempts > 0, none otherwise.
 */
public record ProblemSummaryDto(
        long id,
        String code,          // "A", "B", "C"...
        String title,
        boolean solved,
        int failedAttempts
) {
}
