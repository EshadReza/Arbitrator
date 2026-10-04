/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import java.time.Instant;
import java.text.Normalizer;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.enums.Role;
import com.arbitrator.common.security.AccountPasswordPolicy;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.realtime.NotificationService;
import com.arbitrator.server.realtime.PresenceTracker;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtService;
import com.arbitrator.server.security.PasswordLengthPolicy;

@Service
public class UserService {

    /** Thrown-message marker AuthController's response body carries for a 409. */
    public static final String ALREADY_LOGGED_IN = "ALREADY_LOGGED_IN";
    public static final int MAX_DISPLAY_NAME_LENGTH = 128;
    public static final Pattern STUDENT_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String dummyPasswordHash;
    private final JwtService jwt;
    private final ActiveSessionRegistry sessions;
    private final NotificationService notifications;
    private final PresenceTracker presence;

    public UserService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       ActiveSessionRegistry sessions, NotificationService notifications,
                       PresenceTracker presence) {
        this.users = users;
        this.encoder = encoder;
        // Same configured algorithm/cost, generated once rather than on every failed request.
        this.dummyPasswordHash = encoder.encode(UUID.randomUUID().toString());
        this.jwt = jwt;
        this.sessions = sessions;
        this.notifications = notifications;
        this.presence = presence;
    }

    /** FR-01: unique username, >= 8 char non-common/no-whitespace password, bcrypt storage. */
    public LoginResponse register(LoginRequest req) {
        PasswordLengthPolicy.requireWithinBcryptLimit(req.password());
        String username = req.username() == null ? "" : req.username().trim();
        if (!STUDENT_ID_PATTERN.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Student ID must be 1-64 characters using only letters, numbers, dots, underscores or hyphens");
        }
        String passwordError = AccountPasswordPolicy.validationError(req.password());
        if (passwordError != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, passwordError);
        }
        String displayName = req.displayName() == null || req.displayName().isBlank()
                ? username : req.displayName().trim();
        displayName = Normalizer.normalize(displayName, Normalizer.Form.NFC);
        if (displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_LENGTH
                || displayName.codePoints().anyMatch(UserService::unsafeDisplayNameCodePoint)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Display name must be at most 128 characters and cannot contain control or invisible formatting characters");
        }
        if (users.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
        User u = new User();
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(Role.STUDENT);           // admins are created server-side only
        recordMac(u, req.macAddress());    // first-ever login: record, never a "change"
        users.save(u);
        String sid = UUID.randomUUID().toString();
        sessions.register(u.getUsername(), sid, u.getRole(), true);  // brand new account
        return new LoginResponse(jwt.generate(u.getUsername(), u.getRole(), sid),
                u.getUsername(), u.getDisplayName(), u.getRole());
    }

    private static boolean unsafeDisplayNameCodePoint(int cp) {
        // Preserve visible Unicode and script-shaping ZWJ/ZWNJ; reject only controls
        // that can reorder or hide a student-chosen name in standings/notifications.
        return Character.isISOControl(cp)
                || Character.getType(cp) == Character.SURROGATE
                || cp == 0x2028 || cp == 0x2029
                || cp == 0x00AD || cp == 0x061C || cp == 0x200B
                || cp == 0x200E || cp == 0x200F || cp == 0xFEFF
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2060 && cp <= 0x206F);
    }

    /**
     * FR-02: authenticate and issue a 12-hour JWT.
     *
     * Also enforces single active session (item 5): a second successful login
     * while one is already active is refused with 409 unless the caller
     * already confirmed {@code force=true} — at which point the first
     * session's token stops authenticating on its very next request, see
     * JwtAuthFilter. Also records the reporting client's MAC address and
     * alerts the instructor if it changed from what was on file (item 4).
     */
    public LoginResponse login(LoginRequest req) {
        return login(req, ignored -> {});
    }

    /** Admission callback uses the stored identity, not a collation-equivalent submitted alias. */
    public LoginResponse login(LoginRequest req, Consumer<String> beforePasswordVerification) {
        PasswordLengthPolicy.requireWithinBcryptLimit(req.password());
        if (req.password() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        String submittedUsername = req.username() == null ? "" : req.username().trim();
        User u = users.findByUsername(submittedUsername).orElse(null);
        beforePasswordVerification.accept(u == null ? submittedUsername : u.getUsername());
        boolean passwordMatches = encoder.matches(req.password(), u == null ? dummyPasswordHash : u.getPasswordHash());
        // A dummy-hash match must never authenticate an account that does not exist.
        if (u == null || !passwordMatches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        boolean force = req.force() != null && req.force();
        sessions.invalidateChangedRole(u.getUsername(), u.getRole());
        String sid = UUID.randomUUID().toString();
        if (!sessions.register(u.getUsername(), sid, u.getRole(), force)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ALREADY_LOGGED_IN);
        }

        recordMac(u, req.macAddress());
        users.save(u);
        return new LoginResponse(jwt.generate(u.getUsername(), u.getRole(), sid),
                u.getUsername(), u.getDisplayName(), u.getRole());
    }

    /** Updates the stored MAC and alerts the instructor on a genuine change (item 4). */
    private void recordMac(User u, String macAddress) {
        if (macAddress == null || macAddress.isBlank()) {
            return;    // client couldn't determine one — leave whatever's on file alone
        }
        String previous = u.getMacAddress();
        u.setMacAddress(macAddress);
        if (previous != null && !previous.equalsIgnoreCase(macAddress)) {
            u.setMacChangedAt(Instant.now());
            notifications.raiseMacChanged(u.getUsername());
        }
    }

    /**
     * FR-02 counterpart: explicit sign-out. Releases the single-session slot
     * (item 5) so the very next login as this same user never needs
     * {@code force=true} — without this, ActiveSessionRegistry still held
     * the outgoing session, and every subsequent login wrongly looked like
     * "someone else is already signed in" instead of what actually
     * happened, a clean sign-out. Also tells PresenceTracker directly
     * instead of waiting on the WebSocket close it will still see — a
     * deliberate Sign Out click is not a network blip, so this skips the
     * offline grace period and raises DISCONNECTED right away.
     */
    public void logout(String username, String sid) {
        if (sessions.clear(username, sid) && !sessions.hasActiveSession(username)) {
            presence.signOut(username);
        }
    }

    public User requireByUsername(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}
