/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import java.util.Map;
import java.util.List;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.arbitrator.common.enums.Role;

/**
 * Tracks the one JWT session id ("sid" claim) currently allowed to
 * authenticate as each username — the enforcement side of "same user cannot
 * log in from two instances". Pure in-memory: this is a single-server
 * deployment (D2/D3, no cluster), so there is nothing to share across
 * instances, same reasoning as {@link com.arbitrator.server.realtime.PresenceTracker}'s
 * own maps.
 *
 * A logged-in token is valid only as long as its sid still matches the entry
 * here — a second successful login with {@code force=true} overwrites the
 * entry, which is what silently invalidates the first session's token on its
 * very next request (see {@link JwtAuthFilter}).
 */
@Component
public class ActiveSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveSessionRegistry.class);
    private record Session(String sid, Role role, Instant expiresAt, Instant lastActivity) {}
    private final Clock clock;
    private final Duration absoluteLifetime, adminIdle, studentIdle;
    private final Map<String, Session> activeSessionId = new ConcurrentHashMap<>();
    private final List<SessionInvalidationListener> invalidationListeners =
            new CopyOnWriteArrayList<>();
    private final Object mutationLock = new Object();

    public ActiveSessionRegistry() { this(12, 30, 0); }

    @Autowired
    public ActiveSessionRegistry(@Value("${arbitrator.jwt.expiry-hours:12}") long hours,
            @Value("${arbitrator.session.admin-idle-minutes:30}") long adminMinutes,
            @Value("${arbitrator.session.student-idle-minutes:0}") long studentMinutes) {
        this(Clock.systemUTC(), Duration.ofHours(hours), Duration.ofMinutes(adminMinutes),
                Duration.ofMinutes(studentMinutes));
    }

    ActiveSessionRegistry(Clock clock, Duration absoluteLifetime, Duration adminIdle, Duration studentIdle) {
        if (absoluteLifetime.isZero() || absoluteLifetime.isNegative()
                || adminIdle.isNegative() || studentIdle.isNegative()) {
            throw new IllegalArgumentException("Invalid session timeout configuration");
        }
        this.clock = clock;
        this.absoluteLifetime = absoluteLifetime;
        this.adminIdle = adminIdle;
        this.studentIdle = studentIdle;
    }

    private boolean expired(Session session) {
        Instant now = clock.instant();
        Duration idle = session.role() == Role.ADMIN ? adminIdle : studentIdle;
        return !now.isBefore(session.expiresAt())
                || (!idle.isZero() && !now.isBefore(session.lastActivity().plus(idle)));
    }

    private void expireEntry(String username) {
        Session removed;
        synchronized (mutationLock) {
            Session session = activeSessionId.get(username);
            if (session == null || !expired(session)) return;
            removed = activeSessionId.remove(username);
        }
        notifyInvalidated(username, removed.sid());
    }

    /** No traffic is required for expiry to close an already-open WebSocket. */
    @Scheduled(fixedDelayString = "${arbitrator.session.sweep-millis:15000}")
    public void expireSessions() { activeSessionId.keySet().forEach(this::expireEntry); }

    /** Only explicit activity or authenticated mutations refresh idle time, never validation/polls. */
    public boolean recordActivity(String username, String sid) {
        expireEntry(username);
        synchronized (mutationLock) {
            Session session = activeSessionId.get(username);
            if (session == null || !session.sid().equals(sid) || expired(session)) return false;
            activeSessionId.put(username, new Session(sid, session.role(), session.expiresAt(), clock.instant()));
            return true;
        }
    }

    /**
     * @return true if {@code sid} is now the active session for
     *         {@code username} (either none was active, or {@code force}
     *         overrode the existing one); false if someone else's session is
     *         active and {@code force} was not set — the caller must not
     *         issue a token in that case.
     */
    public boolean register(String username, String sid, Role role, boolean force) {
        java.util.Objects.requireNonNull(role, "login-time role");
        if (sid == null || sid.isBlank()) throw new IllegalArgumentException("Missing session ID");
        Session previous;
        synchronized (mutationLock) {
            previous = activeSessionId.get(username);
            if (previous != null && !force && !expired(previous)) {
                return false;
            }
            Instant now = clock.instant();
            activeSessionId.put(username, new Session(sid, role,
                    now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plus(absoluteLifetime), now));
        }
        if (previous != null && !previous.sid().equals(sid)) {
            notifyInvalidated(username, previous.sid());
        }
        return true;
    }

    /** True when {@code sid} is (still) the active session for {@code username}. */
    public boolean isActive(String username, String sid) {
        expireEntry(username);
        Session session = activeSessionId.get(username);
        return session != null && session.sid().equals(sid);
    }

    /** Atomically revoke only the matching session when its database role changed. */
    public boolean validateRole(String username, String sid, Role currentRole) {
        Session invalidated;
        synchronized (mutationLock) {
            Session session = activeSessionId.get(username);
            if (session == null || !session.sid().equals(sid)) return false;
            if (session.role() == currentRole && !expired(session)) return true;
            invalidated = activeSessionId.remove(username);
        }
        notifyInvalidated(username, invalidated.sid());
        return false;
    }

    /** Credential-verified login can retire a stale-role session without force-login. */
    public void invalidateChangedRole(String username, Role currentRole) {
        Session session = activeSessionId.get(username);
        if (session != null) validateRole(username, session.sid(), currentRole);
    }

    public boolean hasActiveSession(String username) {
        expireEntry(username);
        return activeSessionId.containsKey(username);
    }

    /** Frees the slot so the next login never needs force=true. */
    public boolean clear(String username, String sid) {
        synchronized (mutationLock) {
            Session session = activeSessionId.get(username);
            if (session == null || !session.sid().equals(sid)) return false;
            activeSessionId.remove(username);
        }
        notifyInvalidated(username, sid);
        return true;
    }

    /** Administrative/test cleanup; user logout must use the sid-specific overload. */
    public void clear(String username) {
        Session previous;
        synchronized (mutationLock) {
            previous = activeSessionId.remove(username);
        }
        if (previous != null) {
            notifyInvalidated(username, previous.sid());
        }
    }

    /** Lets transports revoke their already-open sessions at the same instant as REST. */
    public void onInvalidated(SessionInvalidationListener listener) {
        invalidationListeners.add(listener);
    }

    private void notifyInvalidated(String username, String sid) {
        invalidationListeners.forEach(listener -> {
            try {
                listener.invalidated(username, sid);
            } catch (RuntimeException e) {
                // Authentication state has already changed and must not be
                // rolled back merely because transport cleanup had a problem.
                log.warn("An invalidated session's cleanup listener failed ({})", e.getClass().getSimpleName());
            }
        });
    }

    @FunctionalInterface
    public interface SessionInvalidationListener {
        void invalidated(String username, String sid);
    }
}
