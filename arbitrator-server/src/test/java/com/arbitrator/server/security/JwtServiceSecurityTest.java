/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.arbitrator.common.enums.Role;

class JwtServiceSecurityTest {

    @Test
    void expiredSignedTokensAreRejected() {
        JwtService jwt = new JwtService("private-test-key-0123456789abcdef0123456789abcdef", 0);
        assertNull(jwt.parse(jwt.generate("student", Role.STUDENT, "expired-session")));
    }

    @Test
    void unconfiguredServerGeneratesAnIndependentPrivateKey() {
        JwtService first = new JwtService("", 12);
        JwtService restarted = new JwtService(null, 12);
        JwtService blank = new JwtService("  ", 12);
        String token = first.generate("student", Role.STUDENT, "session-1");
        assertNotNull(first.parse(token));
        assertNull(restarted.parse(token));
        assertNull(blank.parse(token));
        assertNotNull(blank.parse(blank.generate("student", Role.STUDENT, "session-2")));
    }

    @ParameterizedTest
    @MethodSource("unsafeSecrets")
    void startupRejectsWeakOrPublicSigningSecrets(String secret) {
        assertThrows(IllegalStateException.class, () -> new JwtService(secret, 12));
    }

    static Stream<String> unsafeSecrets() {
        return Stream.of(
                "too-short",
                "dev-only-secret-change-me-0123456789abcdef0123456789abcdef",
                "your-jwt-secret-that-is-long-enough-but-still-public");
    }

    @Test
    void tokenGenerationRequiresAnActiveSessionId() {
        JwtService jwt = new JwtService(
                "private-test-key-0123456789abcdef0123456789abcdef", 12);

        assertThrows(IllegalArgumentException.class,
                () -> jwt.generate("student", Role.STUDENT, null));
        assertThrows(IllegalArgumentException.class,
                () -> jwt.generate("student", Role.STUDENT, "  "));
    }
}
