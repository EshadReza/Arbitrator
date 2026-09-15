package com.arbitrator.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ActiveSessionRegistryTest {

    @Test
    void replacementAndLogoutNotifyTransportsOfTheExactInvalidatedSid() {
        ActiveSessionRegistry sessions = new ActiveSessionRegistry();
        List<String> invalidated = new ArrayList<>();
        sessions.onInvalidated((username, sid) -> invalidated.add(username + ":" + sid));

        assertTrue(sessions.register("alice", "sid-1", false));
        assertFalse(sessions.register("alice", "sid-rejected", false));
        assertTrue(invalidated.isEmpty());
        assertTrue(sessions.isActive("alice", "sid-1"));

        assertTrue(sessions.register("alice", "sid-2", true));
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
        sessions.register("alice", "sid-1", false);

        assertTrue(sessions.register("alice", "sid-2", true));
        assertTrue(sessions.isActive("alice", "sid-2"));
    }
}
