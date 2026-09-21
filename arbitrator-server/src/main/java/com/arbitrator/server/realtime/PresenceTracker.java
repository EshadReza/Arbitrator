/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.realtime;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Tracks who is connected to which contest, for the instructor's live view
 * (UIF-21 "connected client count") and for the notification feed's
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

    /**
     * Grace period before a disconnect is treated as real (item 7). A
     * heartbeat miss or a few-ms network blip produces a disconnect event
     * immediately followed by a fresh reconnect; deciding "offline" from the
     * disconnect alone fired a DISCONNECTED+RECONNECTED notification pair for
     * every such blip. Waiting this long and rechecking means only a
     * disconnect that's still true after 15s counts as genuine.
     */
    private static final long OFFLINE_GRACE_MS = 15000;

    private final TaskScheduler scheduler;

    public PresenceTracker(@Qualifier("heartbeatScheduler") TaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

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

    /** Fired exactly once per genuine "all sessions gone" transition. */
    private final List<BiConsumer<Long, String>> offlineListeners = new CopyOnWriteArrayList<>();

    /** Fired exactly once per genuine "had been offline, now back" transition. */
    private final List<ReconnectListener> reconnectListeners = new CopyOnWriteArrayList<>();

    public interface ReconnectListener {
        void onReconnect(long contestId, String username, long offlineDurationMs);
    }

    public void onWentOffline(BiConsumer<Long, String> listener) {
        offlineListeners.add(listener);
    }

    public void onReconnected(ReconnectListener listener) {
        reconnectListeners.add(listener);
    }

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
                // Back online — the clock NotificationService was reading for
                // this pair no longer applies. A non-null removal means they
                // really were tracked offline (not just their first-ever
                // subscribe), so it's the one moment worth telling the
                // instructor "reconnected after Nm" about.
                Instant since = offlineSince.remove(key(contestId, username));
                if (since != null) {
                    long offlineDurationMs = Duration.between(since, Instant.now()).toMillis();
                    reconnectListeners.forEach(l -> l.onReconnect(contestId, username, offlineDurationMs));
                }
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
        // Don't decide "offline" from this one event — see OFFLINE_GRACE_MS.
        // Recheck after the grace period instead of trusting the instant a
        // disconnect happened; onSubscribe() reconnecting in the meantime
        // means checkStillOffline() below simply finds another session and
        // no-ops, so a blip shorter than the grace period produces zero
        // notifications instead of one of each.
        long cid = contestId;
        scheduler.schedule(() -> checkStillOffline(cid, username),
                Instant.now().plusMillis(OFFLINE_GRACE_MS));
    }

    /**
     * Explicit sign-out — a deliberate Sign Out click isn't a network blip,
     * so unlike {@link #onDisconnect}, this skips {@link #OFFLINE_GRACE_MS}
     * entirely and raises DISCONNECTED (if this really was their last live
     * session) the instant it's called. Every session tracked under this
     * username is dropped up front, so the real {@link SessionDisconnectEvent}
     * that follows once the client actually closes its socket finds nothing
     * left to remove and is a clean no-op — not a second, redundant
     * notification a grace period later.
     */
    public void signOut(String username) {
        sessionUser.entrySet().stream()
                .filter(e -> username.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(sessionId -> {
                    sessionUser.remove(sessionId);
                    Long contestId = sessionContest.remove(sessionId);
                    if (contestId != null) {
                        checkStillOffline(contestId, username);
                    }
                });
    }

    /** Only start the offline clock if ALL of this user's sessions in this contest are STILL gone. */
    private void checkStillOffline(long contestId, String username) {
        boolean stillHasAnother = sessionContest.entrySet().stream()
                .anyMatch(e -> e.getValue() == contestId
                        && username.equals(sessionUser.get(e.getKey())));
        if (!stillHasAnother) {
            // putIfAbsent, not put: a null return means this pair genuinely
            // just went offline (not already-tracked from some other event),
            // which is the one moment worth telling the instructor about.
            Instant prev = offlineSince.putIfAbsent(key(contestId, username), Instant.now());
            if (prev == null) {
                offlineListeners.forEach(l -> l.accept(contestId, username));
            }
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
     * "contestId:username" -> since when they've had zero live sessions
     * (after surviving the {@link #OFFLINE_GRACE_MS} grace period). A key
     * vanishes the instant that pair reconnects.
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
