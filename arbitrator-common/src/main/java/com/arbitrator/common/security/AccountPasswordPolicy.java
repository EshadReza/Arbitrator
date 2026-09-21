/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.security;

import java.util.Locale;
import java.util.Set;

/** Small offline policy, not a comprehensive breached-password database. */
public final class AccountPasswordPolicy {
    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "password1234", "password12345",
            "password!", "password1!", "p@ssw0rd", "p@ssword", "passw0rd",
            "12345678", "123456789", "1234567890", "0123456789", "87654321",
            "qwertyui", "qwertyuiop", "qwerty123", "qwerty12345", "abcdefgh",
            "abcdefghij", "abcd1234", "abc12345", "iloveyou", "letmein1",
            "welcome1", "welcome123", "admin123", "admin1234", "changeme",
            "changeme123", "test1234", "student123");

    private AccountPasswordPolicy() {}

    /** Returns null when accepted; never modifies the supplied password. */
    public static String validationError(String password) {
        if (password == null || password.length() < 8) {
            return "Password must be at least 8 characters";
        }
        if (password.codePoints().anyMatch(c -> Character.isWhitespace(c)
                || Character.isSpaceChar(c) || Character.isISOControl(c))) {
            return "Password cannot contain spaces, whitespace, or control characters";
        }
        if (COMMON.contains(password.toLowerCase(Locale.ROOT))
                || password.codePoints().distinct().limit(2).count() == 1) {
            return "Password is too common or repetitive; choose a less predictable password";
        }
        return null;
    }
}
