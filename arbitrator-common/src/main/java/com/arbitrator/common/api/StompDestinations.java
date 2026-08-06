package com.arbitrator.common.api;

/**
 * WebSocket/STOMP endpoint and destinations (FR-06/07/15/17).
 * FROZEN — additions allowed, renames need a contract-change issue.
 */
public final class StompDestinations {

    private StompDestinations() {
    }

    /** Raw WebSocket handshake endpoint. JWT goes in the Authorization header. */
    public static final String WS_ENDPOINT = "/ws";

    /** Broker prefixes. */
    public static final String TOPIC_PREFIX = "/topic";
    public static final String QUEUE_PREFIX = "/queue";
    public static final String USER_PREFIX = "/user";

    /** Per-user verdict push (FR-15). Client subscribes to USER_QUEUE_VERDICTS;
        server sends via convertAndSendToUser(username, QUEUE_VERDICTS, dto). */
    public static final String QUEUE_VERDICTS = "/queue/verdicts";
    public static final String USER_QUEUE_VERDICTS = "/user/queue/verdicts";

    /** Broadcast destinations, parameterised by contest id. */
    public static String contestState(long contestId) {
        return TOPIC_PREFIX + "/contest/" + contestId + "/state";
    }

    public static String contestLeaderboard(long contestId) {
        return TOPIC_PREFIX + "/contest/" + contestId + "/leaderboard";
    }

    public static String contestAnnouncements(long contestId) {
        return TOPIC_PREFIX + "/contest/" + contestId + "/announcements";
    }

    /** Clarification board changed — asked, or answered (FR-07 sibling). */
    public static String contestClarifications(long contestId) {
        return TOPIC_PREFIX + "/contest/" + contestId + "/clarifications";
    }
}
