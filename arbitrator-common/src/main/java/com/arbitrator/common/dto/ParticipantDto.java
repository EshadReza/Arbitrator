package com.arbitrator.common.dto;

/**
 * One row of the instructor's participant view: who is in the contest, whether
 * they are connected right now, and how they are doing.
 */
public record ParticipantDto(
        String username,
        String displayName,
        boolean online,          // has a live WebSocket subscribed to this contest
        int rank,                // 0 when they have no judged submissions yet
        int solved,
        long penaltyMinutes,
        int submissionCount,
        long lastSubmissionMs    // -1 when they have never submitted
) {
}
