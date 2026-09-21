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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "arbitrator.api-rate-limit.account.run=2", "arbitrator.api-rate-limit.account.join=2",
        "arbitrator.api-rate-limit.account.clarification=2", "arbitrator.api-rate-limit.account.submit=2",
        "arbitrator.api-rate-limit.account.read=20", "arbitrator.api-rate-limit.ip.registration=3"})
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class ApiRateLimitAttackIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private ActiveSessionRegistry sessions;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void categoryFloodsAreBlockedDespiteIdTokenAndHeaderChangesWhileOtherAccountsAndPollingWork() throws Exception {
        List<String> names = new ArrayList<>();
        try {
            LoginResponse first = register(names), other = register(names);
            for (String path : List.of("/api/run", "/api/submissions", "/api/clarifications", "/api/contests/987654321/join")) {
                for (int i = 0; i < 2; i++) assertNotEquals(429, send("POST", path, first.token(), "{}", i).statusCode());
                String variedPath = path.contains("/join") ? "/api/contests/987654322/join" : path;
                var rejected = send("POST", variedPath, first.token(), "{}", 100);
                assertEquals(429, rejected.statusCode(), path);
                assertNotNull(rejected.headers().firstValue("Retry-After").orElse(null));
                assertTrue(rejected.body().contains("Too many requests"));
                assertNotEquals(429, send("POST", path, other.token(), "{}", 200).statusCode());
            }
            var relogin = send("POST", "/api/auth/login", null,
                    json.writeValueAsString(new LoginRequest(first.username(), null, "Orbit7!Lake", null, true)), 1);
            assertEquals(200, relogin.statusCode());
            LoginResponse fresh = json.readValue(relogin.body(), LoginResponse.class);
            assertEquals(429, send("POST", "/api/run", fresh.token(), "{}", 201).statusCode());
            for (int i = 0; i < 20; i++) assertEquals(200, send("GET", "/api/contests", fresh.token(), null, i).statusCode());
            assertEquals(429, send("GET", "/api/problems", fresh.token(), null, 100).statusCode());
            assertEquals(200, send("GET", "/api/contests", other.token(), null, 100).statusCode());
            // Two registrations above + one invalid attempt exhaust the shared IP's registration budget.
            assertEquals(400, send("POST", "/api/auth/register", null,
                    json.writeValueAsString(new LoginRequest("bad/name", "Bad", "Orbit7!Lake")), 300).statusCode());
            assertEquals(429, send("POST", "/api/auth/register", null, "{}", 301).statusCode());
            assertEquals(200, send("GET", "/admin/index.html", null, null, 302).statusCode());
        } finally {
            for (String name : names) { sessions.clear(name); users.findByUsername(name).ifPresent(users::delete); }
        }
    }
    private LoginResponse register(List<String> names) throws Exception {
        String name = "rate_" + UUID.randomUUID().toString().replace("-", "");
        var response = send("POST", "/api/auth/register", null,
                json.writeValueAsString(new LoginRequest(name, "Rate Test", "Orbit7!Lake")), names.size());
        assertEquals(201, response.statusCode(), response.body());
        names.add(name);
        return json.readValue(response.body(), LoginResponse.class);
    }
    private HttpResponse<String> send(String method, String path, String token, String body, int spoofed) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Forwarded-For", "203.0.113." + spoofed);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("localhost", 3306), 500); return true; }
        catch (Exception e) { return false; }
    }
}
