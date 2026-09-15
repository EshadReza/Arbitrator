package com.arbitrator.server.realtime;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtHandshakeInterceptor;

/**
 * Connects the JWT session registry to already-open WebSockets.
 *
 * A handshake check alone prevents a superseded token from reconnecting, but
 * it cannot revoke a socket that subscribed before the replacement login.
 * Tracking the raw sessions by JWT sid lets the same registry mutation close
 * those sockets immediately. The second active check after insertion closes
 * the race where replacement happens between the HTTP handshake and transport
 * registration.
 */
@Component
public class AuthenticatedWebSocketSessions {

    public static final String INVALIDATED_REASON = "SESSION_SUPERSEDED";
    private static final CloseStatus INVALIDATED =
            new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), INVALIDATED_REASON);

    private final ActiveSessionRegistry activeSessions;
    private final ConcurrentHashMap<String, Set<WebSocketSession>> socketsBySid =
            new ConcurrentHashMap<>();

    public AuthenticatedWebSocketSessions(ActiveSessionRegistry activeSessions) {
        this.activeSessions = activeSessions;
        activeSessions.onInvalidated((username, sid) -> invalidate(sid));
    }

    /** @return false when the connection lost the handshake/replacement race. */
    public boolean register(WebSocketSession socket) {
        String username = attribute(socket, JwtHandshakeInterceptor.ATTR_USERNAME);
        String sid = attribute(socket, JwtHandshakeInterceptor.ATTR_SESSION_ID);
        if (username == null || sid == null || !activeSessions.isActive(username, sid)) {
            closeQuietly(socket);
            return false;
        }

        socketsBySid.computeIfAbsent(sid, ignored -> ConcurrentHashMap.newKeySet()).add(socket);
        if (!activeSessions.isActive(username, sid)) {
            unregister(socket);
            closeQuietly(socket);
            return false;
        }
        return true;
    }

    public void unregister(WebSocketSession socket) {
        String sid = attribute(socket, JwtHandshakeInterceptor.ATTR_SESSION_ID);
        if (sid == null) return;
        socketsBySid.computeIfPresent(sid, (ignored, sockets) -> {
            sockets.remove(socket);
            return sockets.isEmpty() ? null : sockets;
        });
    }

    private void invalidate(String sid) {
        Set<WebSocketSession> sockets = socketsBySid.remove(sid);
        if (sockets != null) {
            sockets.forEach(this::closeQuietly);
        }
    }

    private void closeQuietly(WebSocketSession socket) {
        if (!socket.isOpen()) return;
        try {
            socket.close(INVALIDATED);
        } catch (IOException | RuntimeException ignored) {
            // The registry entry is already gone; a concurrently failed
            // transport needs no further cleanup beyond afterConnectionClosed.
        }
    }

    private static String attribute(WebSocketSession socket, String name) {
        Object value = socket.getAttributes().get(name);
        return value instanceof String text ? text : null;
    }
}
