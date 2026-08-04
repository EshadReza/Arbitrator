package com.arbitrator.common.dto;

import java.util.List;

/**
 * All submissions by one participant, grouped for the admin's
 * expand/collapse submissions view.
 */
public record ParticipantSubmissionsDto(
        String username,
        String displayName,
        int totalSubmissions,
        int acceptedCount,
        String bestVerdict,
        List<SubmissionHistoryDto> submissions
) {}
