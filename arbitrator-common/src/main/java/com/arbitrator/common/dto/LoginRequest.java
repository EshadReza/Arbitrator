/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

/** Body of POST /api/auth/login and /api/auth/register (FR-01, FR-02). */
public record LoginRequest(
        String username,
        String displayName,   // used by register only; null on login
        String password,
        /** Best-effort hardware MAC of the client machine; null if undetectable. */
        String macAddress,
        /**
         * Login only: true means "yes, disconnect my other active session and
         * let this one in" — the contestant already saw and confirmed the
         * conflict warning. False/absent is a normal login attempt.
         */
        Boolean force
) {
    /** Convenience for every call site that doesn't need macAddress/force (most of them). */
    public LoginRequest(String username, String displayName, String password) {
        this(username, displayName, password, null, null);
    }
}
