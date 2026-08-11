package com.arbitrator.server.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.arbitrator.common.enums.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Stateless JWT, HS256, 12-hour expiry (FR-02 EARS).
 * Secret comes from ARBITRATOR_JWT_SECRET env var in any real deployment
 * (NFR-S07); the application.yml default exists only for local dev.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(
            @Value("${arbitrator.jwt.secret}") String secret,
            @Value("${arbitrator.jwt.expiry-hours}") long expiryHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = Duration.ofHours(expiryHours);
    }

    public String generate(String username, Role role) {
        return generate(username, role, null);
    }

    /**
     * @param sid session id (ActiveSessionRegistry's key) — null keeps the
     *            token valid for as long as it hasn't expired, with no
     *            single-session enforcement (used only where that doesn't
     *            apply, e.g. tests). Real logins always pass one.
     */
    public String generate(String username, Role role, String sid) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(username)
                .claim("role", role.name());
        if (sid != null) {
            builder.claim("sid", sid);
        }
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    /** @return the parsed claims, or null when invalid/expired. */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
