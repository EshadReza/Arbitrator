/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.Test;

class ActiveSessionRegistryTest {

    @Test
    void inFlightLogoutCannotRevokeAReplacementLogin() {
        ActiveSessionRegistry sessions = new ActiveSessionRegistry();
        List<String> invalidated = new ArrayList<>();
        sessions.onInvalidated((username, sid) -> invalidated.add(sid));
        sessions.register("alice", "old", com.arbitrator.common.enums.Role.STUDENT, false);
        sessions.register("alice", "new", com.arbitrator.common.enums.Role.STUDENT, true);
        assertFalse(sessions.clear("alice", "old"));
        assertTrue(sessions.isActive("alice", "new"));
        assertEquals(List.of("old"), invalidated);
        assertTrue(sessions.clear("alice", "new"));
        assertFalse(sessions.hasActiveSession("alice"));
        assertEquals(List.of("old", "new"), invalidated);
    }

    @Test
    void roleChangeRevokesOnlyTheMatchingSessionAndNotifiesTransports() {
        ActiveSessionRegistry sessions = new ActiveSessionRegistry();
        List<String> invalidated = new ArrayList<>();
        sessions.onInvalidated((username, sid) -> invalidated.add(sid));
        sessions.register("alice", "student", com.arbitrator.common.enums.Role.STUDENT, false);
        assertTrue(sessions.validateRole("alice", "student", com.arbitrator.common.enums.Role.STUDENT));
        assertFalse(sessions.validateRole("alice", "student", com.arbitrator.common.enums.Role.ADMIN));
        assertFalse(sessions.isActive("alice", "student"));
        assertEquals(List.of("student"), invalidated);
        sessions.register("alice", "admin", com.arbitrator.common.enums.Role.ADMIN, false);
        assertFalse(sessions.validateRole("alice", "student", com.arbitrator.common.enums.Role.STUDENT));
        assertTrue(sessions.isActive("alice", "admin"), "Old requests must not revoke a replacement login");
        assertFalse(sessions.validateRole("alice", "admin", null), "Deleted accounts also revoke sessions");
    }

    @Test
    void replacementAndLogoutNotifyTransportsOfTheExactInvalidatedSid() {
        ActiveSessionRegistry sessions = new ActiveSessionRegistry();
        List<String> invalidated = new ArrayList<>();
        sessions.onInvalidated((username, sid) -> invalidated.add(username + ":" + sid));

        assertTrue(sessions.register("alice", "sid-1", com.arbitrator.common.enums.Role.STUDENT, false));
        assertFalse(sessions.register("alice", "sid-rejected", com.arbitrator.common.enums.Role.STUDENT, false));
        assertTrue(invalidated.isEmpty());
        assertTrue(sessions.isActive("alice", "sid-1"));

        assertTrue(sessions.register("alice", "sid-2", com.arbitrator.common.enums.Role.STUDENT, true));
        assertEquals(List.of("alice:sid-1"), invalidated);
        assertTrue(sessions.isActive("alice", "sid-2"));

        sessions.clear("alice");
        assertEquals(List.of("alice:sid-1", "alice:sid-2"), invalidated);
        assertFalse(sessions.hasActiveSession("alice"));
    }

    @Test
    void cleanupListenerFailureCannotUndoSessionReplacement() {
        ActiveSessionRegistry sessions = new ActiveSessionRegistry();
        sessions.onInvalidated((username, sid) -> {
            throw new IllegalStateException("transport already gone");
        });
        sessions.register("alice", "sid-1", com.arbitrator.common.enums.Role.STUDENT, false);

        assertTrue(sessions.register("alice", "sid-2", com.arbitrator.common.enums.Role.STUDENT, true));
        assertTrue(sessions.isActive("alice", "sid-2"));
    }

    @Test
    void cleanupFailureDoesNotLogSessionIdOrExceptionMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(ActiveSessionRegistry.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            ActiveSessionRegistry sessions = new ActiveSessionRegistry();
            sessions.onInvalidated((username, sid) -> {
                throw new IllegalStateException("PRIVATE_EXCEPTION_SENTINEL");
            });
            sessions.register("alice", "PRIVATE_SESSION_SENTINEL", com.arbitrator.common.enums.Role.STUDENT, false);
            assertTrue(sessions.register("alice", "replacement", com.arbitrator.common.enums.Role.STUDENT, true));
            assertTrue(sessions.isActive("alice", "replacement"));
            assertEquals(1, appender.list.size());
            assertFalse(appender.list.get(0).getFormattedMessage().contains("PRIVATE_"));
            assertTrue(appender.list.get(0).getThrowableProxy() == null);
        } finally {
            logger.detachAppender(appender); appender.stop();
        }
    }
}
