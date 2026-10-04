/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** Live HTTP attack matrix proving that no route opts a hostile browser origin into CORS. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.arbitrator.server.controller.RoleEscalationIntegrationTest#mysqlIsReachable")
class CorsOriginSecurityIntegrationTest {

    private static final String HOSTILE_ORIGIN = "https://evil.example";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;

    @Test
    void publicPrivateAndAdminResponsesDoNotAllowHostileOrigin() {
        for (String path : List.of(
                "/admin/index.html",
                "/api/contests",
                "/api/auth/login",
                "/api/admin/contests")) {
            HttpHeaders headers = new HttpHeaders();
            headers.setOrigin(HOSTILE_ORIGIN);
            ResponseEntity<String> response = rest.exchange(
                    base() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertNoCorsPermission(response, path);
        }
    }

    @Test
    void hostilePreflightsReceiveNoCorsPermission() {
        for (String path : List.of(
                "/api/auth/login",
                "/api/contests",
                "/api/admin/contests")) {
            HttpHeaders headers = new HttpHeaders();
            headers.setOrigin(HOSTILE_ORIGIN);
            headers.setAccessControlRequestMethod(HttpMethod.POST);
            headers.setAccessControlRequestHeaders(List.of(
                    HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
            ResponseEntity<String> response = rest.exchange(
                    base() + path, HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
            assertNoCorsPermission(response, path + " preflight");
        }
    }

    private static void assertNoCorsPermission(ResponseEntity<?> response, String request) {
        assertNull(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                request + " must not grant cross-origin response access");
        assertNull(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS),
                request + " must not grant cross-origin credential access");
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }
}
