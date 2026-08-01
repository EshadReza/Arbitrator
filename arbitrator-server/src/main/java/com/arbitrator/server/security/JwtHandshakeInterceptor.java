package com.arbitrator.server.security;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import io.jsonwebtoken.Claims;

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

    private final JwtService jwtService;

    public JwtHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
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
        attributes.put(ATTR_USERNAME, claims.getSubject());
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
