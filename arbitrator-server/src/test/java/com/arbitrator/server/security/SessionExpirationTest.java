/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import com.arbitrator.common.enums.Role;
import com.arbitrator.server.realtime.AuthenticatedWebSocketSessions;

class SessionExpirationTest {
    private static class Time extends Clock {
        Instant now = Instant.parse("2026-09-16T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }

    private ActiveSessionRegistry registry(Time time) {
        return new ActiveSessionRegistry(time, Duration.ofHours(12), Duration.ofMinutes(30), Duration.ZERO);
    }

    @Test
    void absoluteExpiryClosesOpenSocketWithoutIncomingTrafficAndAllowsFreshLogin() {
        Time time = new Time();
        ActiveSessionRegistry sessions = registry(time);
        sessions.register("student", "old", Role.STUDENT, false);
        AtomicBoolean open = new AtomicBoolean(true);
        Map<String, Object> attributes = Map.of(JwtHandshakeInterceptor.ATTR_USERNAME, "student",
                JwtHandshakeInterceptor.ATTR_SESSION_ID, "old");
        WebSocketSession socket = (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { WebSocketSession.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttributes" -> attributes;
                    case "isOpen" -> open.get();
                    case "close" -> { open.set(false); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AuthenticatedWebSocketSessions sockets = new AuthenticatedWebSocketSessions(sessions);
        assertTrue(sockets.register(socket));
        time.advance(43199);
        assertTrue(sessions.recordActivity("student", "old"));
        time.advance(1);
        sessions.expireSessions();
        assertFalse(open.get());
        assertFalse(sessions.isActive("student", "old"));
        assertTrue(sessions.register("student", "new", Role.STUDENT, false));
        assertTrue(sessions.isActive("student", "new"));
        assertFalse(sessions.isActive("student", "old"));
    }

    @Test
    void pollingAndHeartbeatValidationDoNotResetAdminIdleTime() {
        Time time = new Time();
        ActiveSessionRegistry sessions = registry(time);
        sessions.register("admin", "sid", Role.ADMIN, false);
        time.advance(1799);
        assertTrue(sessions.isActive("admin", "sid"));
        assertTrue(sessions.validateRole("admin", "sid", Role.ADMIN));
        time.advance(1);
        assertFalse(sessions.validateRole("admin", "sid", Role.ADMIN));
        assertFalse(sessions.recordActivity("admin", "sid"), "Expired sessions cannot be revived");
        assertFalse(sessions.hasActiveSession("admin"));
    }

    @Test
    void explicitActivityExtendsIdleButNeverAbsoluteLifetime() {
        Time time = new Time();
        ActiveSessionRegistry sessions = registry(time);
        sessions.register("admin", "sid", Role.ADMIN, false);
        for (int i = 0; i < 24; i++) {
            time.advance(1799);
            assertTrue(sessions.recordActivity("admin", "sid"));
        }
        time.advance(24);
        assertFalse(sessions.isActive("admin", "sid"));
    }

    @Test
    void studentInactivityPolicyIsConfigurableAndDefaultProtectsCodingTime() {
        Time time = new Time();
        ActiveSessionRegistry defaultPolicy = registry(time);
        ActiveSessionRegistry configured = new ActiveSessionRegistry(time, Duration.ofHours(12),
                Duration.ofMinutes(30), Duration.ofMinutes(60));
        defaultPolicy.register("student", "sid", Role.STUDENT, false);
        configured.register("student", "sid", Role.STUDENT, false);
        time.advance(3600);
        assertTrue(defaultPolicy.isActive("student", "sid"));
        assertFalse(configured.isActive("student", "sid"));
    }
}
