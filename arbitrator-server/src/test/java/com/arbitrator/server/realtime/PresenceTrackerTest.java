package com.arbitrator.server.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Explicit sign-out (PresenceTracker.signOut) is the fix for two reported
 * bugs: the instructor's DISCONNECTED notification only ever showing up
 * near a participant's NEXT login instead of at the moment they actually
 * signed out, and that next login wrongly hitting "already logged in
 * elsewhere" even though nobody else was using the account. This only
 * covers the presence half (immediate DISCONNECTED, no grace-period wait,
 * no double-firing once the real WebSocket close follows) — the
 * ActiveSessionRegistry half is exercised live via AuthController.
 */
class PresenceTrackerTest {

    private static final Principal ALICE = () -> "alice";

    private PresenceTracker newTracker() {
        return new PresenceTracker(new ConcurrentTaskScheduler());
    }

    private Message<byte[]> subscribeMessage(String sessionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void signOutRaisesDisconnectedImmediatelyNotAfterAGracePeriod() {
        PresenceTracker presence = newTracker();
        List<String> offlineEvents = new ArrayList<>();
        presence.onWentOffline((contestId, username) -> offlineEvents.add(contestId + ":" + username));

        Message<byte[]> subscribe = subscribeMessage("sess-1", "/topic/contest/7/leaderboard");
        presence.onSubscribe(new SessionSubscribeEvent(this, subscribe, ALICE));
        assertEquals(1, presence.onlineCount(7));
        assertTrue(offlineEvents.isEmpty(), "subscribing must not itself raise a disconnect");

        presence.signOut("alice");

        // No scheduler wait needed — signOut must be synchronous, unlike the
        // 15s-grace-period path a real dropped connection goes through.
        assertEquals(List.of("7:alice"), offlineEvents);
        assertEquals(0, presence.onlineCount(7));
        assertTrue(presence.offlineSince().containsKey("7:alice"));
    }

    @Test
    void signOutIsANoOpWhenTheUserHasNoLiveSession() {
        PresenceTracker presence = newTracker();
        List<String> offlineEvents = new ArrayList<>();
        presence.onWentOffline((contestId, username) -> offlineEvents.add(contestId + ":" + username));

        presence.signOut("nobody-online");

        assertTrue(offlineEvents.isEmpty());
    }

    @Test
    void theRealDisconnectEventThatFollowsSignOutDoesNotDoubleFire() {
        PresenceTracker presence = newTracker();
        List<String> offlineEvents = new ArrayList<>();
        presence.onWentOffline((contestId, username) -> offlineEvents.add(contestId + ":" + username));

        Message<byte[]> subscribe = subscribeMessage("sess-1", "/topic/contest/7/leaderboard");
        presence.onSubscribe(new SessionSubscribeEvent(this, subscribe, ALICE));
        presence.signOut("alice");
        assertEquals(1, offlineEvents.size());

        // MainController's onSignOut calls logout() (-> signOut() above) BEFORE
        // dropConnection() closes the socket, so this is what the resulting
        // SessionDisconnectEvent looks like a moment later — signOut() already
        // removed this session, so there is nothing left to schedule a second
        // grace-period check against.
        presence.onDisconnect(new SessionDisconnectEvent(this, subscribe, "sess-1", CloseStatus.NORMAL, ALICE));

        assertEquals(1, offlineEvents.size(), "the trailing disconnect must not raise a second notification");
        assertFalse(presence.onlineIn(7).contains("alice"));
    }
}
