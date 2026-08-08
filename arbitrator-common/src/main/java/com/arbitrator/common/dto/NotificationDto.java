package com.arbitrator.common.dto;

/**
 * One presence event for the instructor console's notification stack and
 * Notifications tab: a participant either dropped every live connection
 * ("DISCONNECTED") or came back after having been gone ("RECONNECTED",
 * where {@code minutesOffline} is how long they were away). Nothing repeats
 * on a timer — exactly one of each per genuine state transition.
 */
public record NotificationDto(
        long id,
        long contestId,
        String contestTitle,
        String username,
        String displayName,
        String type,
        int minutesOffline,
        long atMs
) {
}
