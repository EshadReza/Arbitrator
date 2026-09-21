/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Live requests, real password verification, deliberately isolated throttle settings. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "arbitrator.auth.login.account-failures=3", "arbitrator.auth.login.ip-failures=8",
        "arbitrator.auth.login.base-cooldown-seconds=30"})
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class LoginThrottleAttackIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private ActiveSessionRegistry sessions;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void accountAndIpSprayAreBlockedDespiteForceAndSpoofedHeadersWithoutRevokingExistingSession() throws Exception {
        String username = "throttle_" + UUID.randomUUID().toString().replace("-", "");
        LoginResponse registered = rest.postForObject(base() + "/api/auth/register",
                new LoginRequest(username, "Throttle Test", "Orbit7!Lake"), LoginResponse.class);
        assertNotNull(registered);
        try {
            for (int i = 0; i < 3; i++) {
                assertEquals(401, login(new LoginRequest(username, null, "Wrong7!Password", null, true), i).statusCode());
            }
            var blocked = login(new LoginRequest(" " + username.toUpperCase(java.util.Locale.ROOT) + " ",
                    null, "Orbit7!Lake", null, true), 4);
            assertEquals(429, blocked.statusCode());
            assertTrue(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow()) > 0);
            assertTrue(blocked.body().contains("Too many login attempts"));
            var collationAlias = login(new LoginRequest(username.replace("throttle_", "thròttle_"),
                    null, "Orbit7!Lake", null, true), 5);
            assertEquals(429, collationAlias.statusCode(), "database-equivalent Unicode aliases must share the account penalty");

            // Unknown usernames consume the same IP failure budget; blocked attempts don't reset it.
            for (int i = 0; i < 5; i++) {
                assertEquals(401, login(new LoginRequest("missing_" + i, null, "Wrong7!Password"), i + 10).statusCode());
            }
            var sprayed = login(new LoginRequest("another_missing", null, "Wrong7!Password"), 99);
            assertEquals(429, sprayed.statusCode());
            assertTrue(sprayed.headers().firstValue("Retry-After").isPresent());

            var activity = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/auth/activity"))
                    .header("Authorization", "Bearer " + registered.token())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, activity.statusCode(), "attack penalties must not revoke the victim's existing session");
        } finally {
            sessions.clear(username);
            users.findByUsername(username).ifPresent(users::delete);
        }
    }

    private HttpResponse<String> login(LoginRequest body, int spoofedIp) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "203.0.113." + spoofedIp)
                .header("Forwarded", "for=203.0.113." + spoofedIp)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private String base() { return "http://localhost:" + port; }
    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 500);
            return true;
        } catch (Exception e) { return false; }
    }
}
