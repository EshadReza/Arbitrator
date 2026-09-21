/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.arbitrator.common.enums.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Stateless JWT with a private per-process signing key unless explicitly configured.
 * No public/shared fallback key and no mandatory environment setup.
 */
@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(
            @Value("${arbitrator.jwt.secret:}") String secret,
            @Value("${arbitrator.jwt.expiry-hours}") long expiryHours) {
        if (secret == null || secret.isBlank()) {
            this.key = Jwts.SIG.HS256.key().build();
        } else {
            validateSecret(secret);
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        this.expiry = Duration.ofHours(expiryHours);
    }

    /** @param sid the mandatory ActiveSessionRegistry session identifier. */
    public String generate(String username, Role role, String sid) {
        if (sid == null || sid.isBlank()) {
            throw new IllegalArgumentException("JWT session id must not be blank");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                // Kept for UI compatibility only. Authorization always uses the
                // user's current database role in JwtAuthFilter.
                .claim("role", role.name())
                .claim("sid", sid)
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

    private static void validateSecret(String secret) {
        String normalized = secret.toLowerCase(Locale.ROOT);
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES
                || normalized.contains("change-me")
                || normalized.contains("changeme")
                || normalized.contains("your-jwt-secret")
                || normalized.startsWith("dev-only-secret")) {
            throw new IllegalStateException(
                    "ARBITRATOR_JWT_SECRET must be a non-placeholder secret of at least 32 UTF-8 bytes");
        }
    }
}
