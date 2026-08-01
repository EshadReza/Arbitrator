package com.arbitrator.common.dto;

/** Body of POST /api/auth/login and /api/auth/register (FR-01, FR-02). */
public record LoginRequest(
        String username,
        String displayName,   // used by register only; null on login
        String password
) {
}
