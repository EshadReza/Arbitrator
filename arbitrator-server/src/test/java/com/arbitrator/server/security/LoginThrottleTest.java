/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {
    private static LoginThrottle.Settings settings(int accounts, int ips, int accountFailures,
                                                   int ipFailures, int maxKeys) {
        return new LoginThrottle.Settings(accounts, ips, accountFailures, ipFailures, 2, 16, 60, 5, 20, maxKeys);
    }

    @Test
    void accountCooldownSurvivesIpRotationCasingAndBlockedRetriesThenEscalatesAndRecovers() {
        var clock = new TestClock();
        var limiter = new LoginThrottle(clock, settings(100, 100, 2, 100, 100));
        fail(limiter, "Student", "ip1");
        fail(limiter, " student ", "ip2");
        assertEquals("5", limited(limiter, "STUDENT", "ip3").getHeaders().getFirst("Retry-After"));
        clock.advance(4999);
        assertEquals("1", limited(limiter, "student", "ip4").getHeaders().getFirst("Retry-After"));
        clock.advance(1);
        fail(limiter, "student", "ip5");
        assertEquals("10", limited(limiter, "student", "ip6").getHeaders().getFirst("Retry-After"));
        clock.advance(10000);
        fail(limiter, "student", "ip7");
        assertEquals("20", limited(limiter, "student", "ip8").getHeaders().getFirst("Retry-After"));
        clock.advance(20000);
        limiter.finish(limiter.begin("student", "ip9"), true);
        fail(limiter, "student", "ip10");
        limiter.finish(limiter.begin("student", "ip11"), true);
    }

    @Test
    void ipBudgetSurvivesUsernameRotationAndSuccessfulLogin() {
        var limiter = new LoginThrottle(new TestClock(), settings(100, 100, 100, 3, 100));
        fail(limiter, "missing1", "shared");
        limiter.finish(limiter.begin("owned", "shared"), true);
        fail(limiter, "missing2", "shared");
        fail(limiter, "missing3", "shared");
        limited(limiter, "new-account", "shared");
        limiter.finish(limiter.begin("new-account", "other-ip"), true);
    }

    @Test
    void fixedWindowCountsAllAttemptsAndRecoversAtExactBoundary() {
        var clock = new TestClock();
        var limiter = new LoginThrottle(clock, settings(2, 3, 100, 100, 100));
        for (int i = 0; i < 2; i++) limiter.finish(limiter.begin("user", "ip"), true);
        assertEquals("60", limited(limiter, "user", "other-ip").getHeaders().getFirst("Retry-After"));
        clock.advance(60000);
        limiter.finish(limiter.begin("user", "ip"), true);
        limiter.finish(limiter.begin("user2", "ip"), true);
        limiter.finish(limiter.begin("user3", "ip"), true);
        limited(limiter, "user4", "ip");
    }

    @Test
    void concurrentRequestsReserveSlotsAtomicallyAndCompletionIsIdempotent() throws Exception {
        var limiter = new LoginThrottle(new TestClock(), settings(100, 100, 100, 100, 100));
        var executor = Executors.newFixedThreadPool(12);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<LoginThrottle.Ticket>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { return limiter.begin("same-user", "same-ip"); }
                catch (LoginThrottle.Limited e) { return null; }
            }));
            start.countDown();
            var tickets = new ArrayList<LoginThrottle.Ticket>();
            for (var future : futures) {
                var ticket = future.get(5, TimeUnit.SECONDS);
                if (ticket != null) tickets.add(ticket);
            }
            assertEquals(2, tickets.size());
            for (var ticket : tickets) { limiter.finish(ticket, null); limiter.finish(ticket, false); }
            limiter.finish(limiter.begin("same-user", "same-ip"), true);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void ipConcurrencyIsBoundedAcrossAccounts() {
        var limiter = new LoginThrottle(new TestClock(), settings(100, 100, 100, 100, 100));
        var tickets = new ArrayList<LoginThrottle.Ticket>();
        for (int i = 0; i < 16; i++) tickets.add(limiter.begin("user" + i, "same-ip"));
        limited(limiter, "next-user", "same-ip");
        limiter.finish(tickets.get(0), null);
        limiter.finish(limiter.begin("next-user", "same-ip"), true);
    }

    @Test
    void boundedStateDoesNotEvictActivePenaltyAndIdleKeysEventuallyExpire() {
        var clock = new TestClock();
        var limiter = new LoginThrottle(clock, settings(100, 100, 1, 100, 3));
        fail(limiter, "victim", "ip");
        limiter.finish(limiter.begin("second", "ip"), true);
        limited(limiter, "third", "new-ip");
        limited(limiter, "victim", "ip");
        clock.advance(120000);
        limiter.finish(limiter.begin("third", "new-ip"), true);
    }

    @Test
    void databaseCanonicalIdentityPreventsCollationAliasBypass() {
        var limiter = new LoginThrottle(new TestClock(), settings(100, 100, 2, 100, 100));
        fail(limiter, "student", "ip1");
        var alias = limiter.begin("stúdent", "ip2");
        limiter.bindAccount(alias, "student");
        limiter.finish(alias, false);
        var nextAlias = limiter.begin("stùdent", "ip3");
        assertThrows(LoginThrottle.Limited.class, () -> limiter.bindAccount(nextAlias, "student"));
        limiter.finish(nextAlias, null);
        limited(limiter, "student", "ip4");
    }

    @Test
    void invalidConfigurationFailsClosedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> settings(0, 10, 5, 5, 100));
    }

    private static void fail(LoginThrottle limiter, String user, String ip) {
        limiter.finish(limiter.begin(user, ip), false);
    }
    private static LoginThrottle.Limited limited(LoginThrottle limiter, String user, String ip) {
        return assertThrows(LoginThrottle.Limited.class, () -> limiter.begin(user, ip));
    }
    private static class TestClock extends Clock {
        private long millis = 1_000_000;
        void advance(long delta) { millis += delta; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
