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
import java.util.Collections;
import java.util.List;
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

/** Compare both unthrottled failure paths using real production-cost bcrypt and live HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "arbitrator.auth.login.account-failures=100", "arbitrator.auth.login.ip-failures=1000",
        "arbitrator.auth.login.account-attempts=100", "arbitrator.auth.login.ip-attempts=1000"})
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class UserEnumerationAttackIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private ActiveSessionRegistry sessions;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void wrongPasswordAndUnknownAccountHaveIdenticalFailureFieldsAndComparableMedianTiming() throws Exception {
        String username = "enum_" + UUID.randomUUID().toString().replace("-", "");
        String missing = "absent_" + UUID.randomUUID().toString().replace("-", "");
        LoginResponse registered = rest.postForObject(base() + "/api/auth/register",
                new LoginRequest(username, "Enumeration Test", "Orbit7!Lake"), LoginResponse.class);
        assertNotNull(registered);
        try {
            List<Double> knownTimes = new ArrayList<>(), unknownTimes = new ArrayList<>();
            for (int pair = 0; pair < 8; pair++) {
                // Alternate order to reduce warm-up and scheduling bias; discard two warm-up pairs.
                for (int attempt = 0; attempt < 2; attempt++) {
                    boolean known = (pair + attempt) % 2 == 0;
                    long started = System.nanoTime();
                    var response = login(known ? username : missing);
                    double millis = (System.nanoTime() - started) / 1_000_000.0;
                    assertEquals(401, response.statusCode());
                    var body = json.readTree(response.body());
                    assertEquals(401, body.path("status").asInt());
                    assertEquals("Unauthorized", body.path("error").asText());
                    assertEquals("Invalid credentials", body.path("message").asText());
                    assertEquals("/api/auth/login", body.path("path").asText());
                    assertFalse(response.headers().firstValue("Retry-After").isPresent());
                    if (pair >= 2) (known ? knownTimes : unknownTimes).add(millis);
                }
            }
            Collections.sort(knownTimes);
            Collections.sort(unknownTimes);
            double knownMedian = (knownTimes.get(2) + knownTimes.get(3)) / 2;
            double unknownMedian = (unknownTimes.get(2) + unknownTimes.get(3)) / 2;
            double ratio = unknownMedian / knownMedian;
            System.out.printf("Enumeration attack timing: known median %.1f ms, unknown median %.1f ms, ratio %.2f%n",
                    knownMedian, unknownMedian, ratio);
            // Broad regression check for the old no-bcrypt fast path, not proof of constant-time HTTP.
            assertTrue(ratio > 1.0 / 3 && ratio < 3, "large failure-path timing discrepancy: ratio=" + ratio);
        } finally {
            sessions.clear(username);
            users.findByUsername(username).ifPresent(users::delete);
        }
    }

    private HttpResponse<String> login(String username) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        new LoginRequest(username, null, "Wrong7!Password", null, true)))).build(),
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
