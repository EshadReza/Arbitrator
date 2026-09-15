package com.arbitrator.server.service;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.realtime.NotificationService;
import com.arbitrator.server.realtime.PresenceTracker;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtService;

@Service
public class UserService {

    /** Thrown-message marker AuthController's response body carries for a 409. */
    public static final String ALREADY_LOGGED_IN = "ALREADY_LOGGED_IN";
    public static final int MAX_DISPLAY_NAME_LENGTH = 128;
    public static final Pattern STUDENT_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final ActiveSessionRegistry sessions;
    private final NotificationService notifications;
    private final PresenceTracker presence;

    public UserService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       ActiveSessionRegistry sessions, NotificationService notifications,
                       PresenceTracker presence) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.sessions = sessions;
        this.notifications = notifications;
        this.presence = presence;
    }

    /** FR-01: unique username, >= 8 char password, bcrypt storage. */
    public LoginResponse register(LoginRequest req) {
        String username = req.username() == null ? "" : req.username().trim();
        if (!STUDENT_ID_PATTERN.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Student ID must be 1-64 characters using only letters, numbers, dots, underscores or hyphens");
        }
        if (req.password() == null || req.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least 8 characters");
        }
        String displayName = req.displayName() == null || req.displayName().isBlank()
                ? username : req.displayName().trim();
        if (displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_LENGTH
                || displayName.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Display name must be at most 128 characters and cannot contain control characters");
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
        sessions.register(u.getUsername(), sid, true);  // brand new account: nothing to conflict with
        return new LoginResponse(jwt.generate(u.getUsername(), u.getRole(), sid),
                u.getUsername(), u.getDisplayName(), u.getRole());
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
        User u = users.findByUsername(req.username() == null ? "" : req.username().trim())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!encoder.matches(req.password(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        boolean force = req.force() != null && req.force();
        String sid = UUID.randomUUID().toString();
        if (!sessions.register(u.getUsername(), sid, force)) {
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
    public void logout(String username) {
        sessions.clear(username);
        presence.signOut(username);
    }

    public User requireByUsername(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}
