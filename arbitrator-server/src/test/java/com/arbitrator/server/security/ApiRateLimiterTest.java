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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

class ApiRateLimiterTest {
    @Test
    void exactRecoveryAndDeniedRequestsDoNotExtendWindow() {
        var clock = new TestClock();
        var props = new ApiRateLimitProperties();
        props.getAccount().put("run", 2);
        var limiter = new ApiRateLimiter(props, clock);
        assertEquals(0, limiter.admit(true, "alice", "run"));
        assertEquals(0, limiter.admit(true, "alice", "run"));
        assertEquals(60, limiter.admit(true, "alice", "run"));
        clock.millis += 59999;
        assertEquals(1, limiter.admit(true, "alice", "run"));
        clock.millis++;
        assertEquals(0, limiter.admit(true, "alice", "run"));
    }

    @Test
    void accountsIpsAndCategoriesAreIsolatedButAggregateBudgetCannotBeBypassed() {
        var props = new ApiRateLimitProperties();
        props.getAccount().put("run", 1);
        props.setAccountTotal(2);
        props.getIp().put("registration", 1);
        var limiter = new ApiRateLimiter(props, new TestClock());
        assertEquals(0, limiter.admit(true, "alice", "run"));
        assertTrue(limiter.admit(true, "alice", "run") > 0);
        assertEquals(0, limiter.admit(true, "bob", "run"));
        assertEquals(0, limiter.admit(true, "alice", "read"));
        assertTrue(limiter.admit(true, "alice", "join") > 0);
        assertEquals(0, limiter.admit(false, "ip1", "registration"));
        assertTrue(limiter.admit(false, "ip1", "registration") > 0);
        assertEquals(0, limiter.admit(false, "ip2", "registration"));
    }

    @Test
    void concurrentFloodCannotExceedAtomicBudget() throws Exception {
        var props = new ApiRateLimitProperties();
        props.getAccount().put("submit", 3);
        var limiter = new ApiRateLimiter(props, new TestClock());
        var pool = Executors.newFixedThreadPool(16);
        try {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 16; i++) tasks.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return limiter.admit(true, "alice", "submit");
            }));
            start.countDown();
            int admitted = 0;
            for (var task : tasks) if (task.get(5, TimeUnit.SECONDS) == 0) admitted++;
            assertEquals(3, admitted);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void boundedStateRejectsNewKeysWithoutEvictingActiveBudgetsThenExpires() {
        var props = new ApiRateLimitProperties();
        props.setMaxKeys(2);
        props.getAccount().put("run", 1);
        var clock = new TestClock();
        var limiter = new ApiRateLimiter(props, clock);
        assertEquals(0, limiter.admit(true, "alice", "run"));
        assertTrue(limiter.admit(true, "bob", "run") > 0);
        assertTrue(limiter.admit(true, "alice", "run") > 0);
        clock.millis += 60000;
        assertEquals(0, limiter.admit(true, "bob", "run"));
    }

    @Test
    void varyingIdsMatrixParametersAndReadMethodsDoNotCreateNewCategories() {
        assertEquals("join", ApiRateLimiter.category("POST", "/api/contests/1/join"));
        assertEquals("join", ApiRateLimiter.category("POST", "/api/contests/2;foo=bar/join;foo=bar"));
        assertEquals("run", ApiRateLimiter.category("POST", "/api/run;foo=bar"));
        assertEquals("read", ApiRateLimiter.category("HEAD", "/api/problems/12"));
        assertEquals("read", ApiRateLimiter.category("OPTIONS", "/api/run"));
        assertEquals("write", ApiRateLimiter.category("DELETE", "/api/admin/problems/1"));
    }

    @Test
    void defaultBudgetsAllowRepresentativePollingAndTwentyStudentRegistrations() {
        var limiter = new ApiRateLimiter(new ApiRateLimitProperties(), new TestClock());
        for (int student = 0; student < 20; student++) {
            assertEquals(0, limiter.admit(false, "shared-lab", "registration"));
            for (int request = 0; request < 180; request++) {
                assertEquals(0, limiter.admit(false, "shared-lab", "read"));
                assertEquals(0, limiter.admit(true, "student" + student, "read"));
            }
        }
    }

    @Test
    void ipFilterRejectsBeforeDownstreamBodyWorkAndIgnoresSpoofedHeaders() throws Exception {
        var props = new ApiRateLimitProperties();
        props.getIp().put("registration", 1);
        var filter = new ApiRateLimitFilter(new ApiRateLimiter(props), new ObjectMapper(), false);
        var request = request("POST", "/api/auth/register");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        request = request("POST", "/api/auth/register");
        request.addHeader("X-Forwarded-For", "203.0.113.123");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("blocked requests must not reach body/controller work"));
        assertEquals(429, response.getStatus());
        assertNotNull(response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("Too many requests"));
    }

    @Test
    void accountFilterUsesAuthenticatedIdentityNotTokenOrIpAndLeavesStaticFilesAlone() throws Exception {
        var props = new ApiRateLimitProperties();
        props.getAccount().put("run", 1);
        var filter = new ApiRateLimitFilter(new ApiRateLimiter(props), new ObjectMapper(), true);
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("alice", "token1", java.util.List.of()));
            filter.doFilter(request("POST", "/api/run"), new MockHttpServletResponse(), (req, res) -> {});
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("alice", "token2", java.util.List.of()));
            var req = request("POST", "/api/run");
            req.setRemoteAddr("different-ip");
            var response = new MockHttpServletResponse();
            filter.doFilter(req, response, (r, s) -> fail("token/IP rotation cannot reset account budget"));
            assertEquals(429, response.getStatus());
            filter.doFilter(request("GET", "/admin/index.html"), new MockHttpServletResponse(), (r, s) -> {});
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void invalidConfigurationCannotDisableProtectionSilently() {
        var props = new ApiRateLimitProperties();
        props.getAccount().put("run", 0);
        assertThrows(IllegalArgumentException.class, () -> new ApiRateLimiter(props));
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
    private static class TestClock extends Clock {
        long millis = 1_000_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
