package com.arbitrator.common.dto;

/**
 * One escalating "this participant has been offline for N minutes" alert
 * for the instructor console's notification stack. A participant crossing
 * minute 1, then minute 2, then minute 3 without reconnecting produces
 * three of these, newest first — not one alert that silently updates.
 */
public record OfflineAlertDto(
        long id,
        long contestId,
        String contestTitle,
        String username,
        String displayName,
        int minutesOffline,
        long atMs
) {
}
