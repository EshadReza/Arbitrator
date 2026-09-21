/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import io.jsonwebtoken.Claims;
import com.arbitrator.server.repo.UserRepository;

/**
 * Authenticates the WebSocket handshake (FR-15 prerequisite).
 * The JavaFX client sends "Authorization: Bearer <jwt>" as a handshake header
 * (no browser involved, so headers are fine — no query-param token needed).
 * On success the username is stashed as a session attribute; the custom
 * HandshakeHandler in WebSocketConfig turns it into the session Principal,
 * which is what makes convertAndSendToUser(...) route correctly.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USERNAME = "arbitrator.username";
    public static final String ATTR_SESSION_ID = "arbitrator.sessionId";

    private final JwtService jwtService;
    private final ActiveSessionRegistry sessions;
    private final UserRepository users;

    public JwtHandshakeInterceptor(JwtService jwtService, ActiveSessionRegistry sessions, UserRepository users) {
        this.jwtService = jwtService;
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String header = request.getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Claims claims = jwtService.parse(header.substring(7));
        if (claims == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String username = claims.getSubject();
        String sid = claims.get("sid", String.class);
        if (username == null || sid == null || !sessions.isActive(username, sid)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (!sessions.validateRole(username, sid,
                users.findByUsername(username).map(user -> user.getRole()).orElse(null))) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USERNAME, username);
        attributes.put(ATTR_SESSION_ID, sid);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // nothing to do
    }
}
