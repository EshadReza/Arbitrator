package com.arbitrator.common.dto;

import com.arbitrator.common.enums.Role;

/** JWT is valid for 12 hours (FR-02 EARS). */
public record LoginResponse(
        String token,
        String username,
        String displayName,
        Role role
) {
}
