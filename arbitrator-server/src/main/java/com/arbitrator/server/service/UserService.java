package com.arbitrator.server.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.realtime.NotificationService;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtService;

@Service
public class UserService {

    /** Thrown-message marker AuthController's response body carries for a 409. */
    public static final String ALREADY_LOGGED_IN = "ALREADY_LOGGED_IN";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final ActiveSessionRegistry sessions;
    private final NotificationService notifications;

    public UserService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       ActiveSessionRegistry sessions, NotificationService notifications) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.sessions = sessions;
        this.notifications = notifications;
    }

    /** FR-01: unique username, >= 8 char password, bcrypt storage. */
    public LoginResponse register(LoginRequest req) {
        if (req.username() == null || req.username().isBlank()
                || req.password() == null || req.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Username required and password must be at least 8 characters");
        }
        if (users.existsByUsername(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
        User u = new User();
        u.setUsername(req.username().trim());
        u.setDisplayName(req.displayName() == null || req.displayName().isBlank()
                ? req.username().trim() : req.displayName().trim());
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

    public User requireByUsername(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}
