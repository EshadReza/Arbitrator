/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reject, never truncate, input beyond bcrypt's effective UTF-8 boundary. */
public final class PasswordLengthPolicy {
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordLengthPolicy() {}

    public static void requireWithinBcryptLimit(String password) {
        if (password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at most 72 UTF-8 bytes (Unicode characters may use multiple bytes)");
        }
    }
}
