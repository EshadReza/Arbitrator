/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

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
