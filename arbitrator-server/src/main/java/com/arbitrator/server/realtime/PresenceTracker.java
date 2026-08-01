package com.arbitrator.server.realtime;

import java.security.Principal;
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
 * (UIF-21 "connected client count").
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
            sessionContest.put(sessionId, Long.parseLong(m.group(1)));
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        sessionUser.remove(sessionId);
        sessionContest.remove(sessionId);
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
}
