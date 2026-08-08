package com.arbitrator.server.realtime;

import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Tracks who is connected to which contest, for the instructor's live view
 * (UIF-21 "connected client count") and for {@link OfflineAlertService}'s
 * "this participant has been gone N minutes" escalation.
 *
 * Contest membership is inferred from the STOMP destination a client
 * subscribes to (/topic/contest/{id}/...), which is the only signal the server
 * has — a WebSocket handshake says who you are, not which contest you opened.
 */
@Component
public class PresenceTracker {

    private static final Pattern CONTEST_TOPIC =
            Pattern.compile("^/topic/contest/(\\d+)/.*$");

    /** sessionId -> username, so a disconnect can be attributed. */
    private final Map<String, String> sessionUser = new ConcurrentHashMap<>();

    /** sessionId -> contestId. */
    private final Map<String, Long> sessionContest = new ConcurrentHashMap<>();

    /**
     * "contestId:username" -> the instant their last live session in that
     * contest disconnected. Absent means currently online (or never seen).
     * Cleared the moment a new session for that same pair subscribes.
     */
    private final Map<String, Instant> offlineSince = new ConcurrentHashMap<>();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        if (destination == null || sessionId == null) {
            return;
        }
        Principal user = event.getUser();
        if (user != null) {
            sessionUser.put(sessionId, user.getName());
        }
        Matcher m = CONTEST_TOPIC.matcher(destination);
        if (m.matches()) {
            long contestId = Long.parseLong(m.group(1));
            sessionContest.put(sessionId, contestId);
            String username = sessionUser.get(sessionId);
            if (username != null) {
                // Back online — the clock OfflineAlertService was reading
                // for this pair no longer applies.
                offlineSince.remove(key(contestId, username));
            }
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String username = sessionUser.remove(sessionId);
        Long contestId = sessionContest.remove(sessionId);
        if (username == null || contestId == null) {
            return;
        }
        // Only start the offline clock once ALL of this user's sessions in
        // this contest are gone — a second browser tab, or a reconnect that
        // opened a fresh session just before the old one timed out, must not
        // mark someone offline while they still have a live connection.
        boolean stillHasAnother = sessionContest.entrySet().stream()
                .anyMatch(e -> e.getValue() == contestId
                        && username.equals(sessionUser.get(e.getKey())));
        if (!stillHasAnother) {
            offlineSince.putIfAbsent(key(contestId, username), Instant.now());
        }
    }

    /** Usernames with at least one live subscription to this contest. */
    public Set<String> onlineIn(long contestId) {
        Set<String> users = ConcurrentHashMap.newKeySet();
        sessionContest.forEach((sessionId, id) -> {
            if (id == contestId) {
                String username = sessionUser.get(sessionId);
                if (username != null) {
                    users.add(username);
                }
            }
        });
        return Collections.unmodifiableSet(users);
    }

    public int onlineCount(long contestId) {
        return onlineIn(contestId).size();
    }

    /** Every connected client, whichever contest they're in. */
    public int totalConnected() {
        return Set.copyOf(sessionUser.values()).size();
    }

    /**
     * "contestId:username" -> since when they've had zero live sessions.
     * Read by {@link OfflineAlertService}'s periodic sweep; a key vanishes
     * the instant that pair reconnects.
     */
    public Map<String, Instant> offlineSince() {
        return Collections.unmodifiableMap(offlineSince);
    }

    /**
     * Drops every offline-tracking entry for a contest — hooked to {@code
     * ContestService.onContestStarted} so a fresh run doesn't inherit
     * "offline for 45 minutes" alerts left over from whoever was in the
     * room the last time this contest ran.
     */
    public void clearContest(long contestId) {
        String prefix = contestId + ":";
        offlineSince.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String key(long contestId, String username) {
        return contestId + ":" + username;
    }
}
