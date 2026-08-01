package com.arbitrator.common.dto;

/** 202 Accepted response: submission queued (FR-09 EARS). */
public record SubmitAckDto(
        long submissionId,
        int queuePosition   // 1-based ordinal at the moment of receipt (§6.1)
) {
}
