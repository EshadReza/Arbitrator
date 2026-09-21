/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Real HTTP requests, including HTML content negotiation and reflected binding errors. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.arbitrator.server.controller.RoleEscalationIntegrationTest#mysqlIsReachable")
class ReflectedXssAttackIntegrationTest {
    private static final List<String> PAYLOADS = List.of(
            "<script>alert('xss-probe')</script>",
            "<img src=x onerror=alert('xss-probe')>",
            "\"><svg onload=alert('xss-probe')>",
            "</script><script>alert('xss-probe')</script>");
    private static final List<MediaType> ACCEPTS = List.of(
            MediaType.APPLICATION_JSON, MediaType.TEXT_HTML);

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private ActiveSessionRegistry sessions;
    @Autowired private JwtService jwt;
    @Autowired private ObjectMapper json;
    private User admin;

    @AfterEach
    void cleanup() {
        if (admin != null) {
            sessions.clear(admin.getUsername());
            users.delete(admin);
        }
    }

    @Test
    void queryPayloadsDoNotAlterStaticAdminPage() {
        String baseline = rest.getForObject(base() + "/admin/index.html", String.class);
        assertNotNull(baseline);
        for (String parameter : List.of("search", "error", "message", "redirect")) {
            for (String payload : PAYLOADS) {
                ResponseEntity<String> response = request(HttpMethod.GET,
                        "/admin/index.html?" + parameter + "=" + encode(payload),
                        MediaType.TEXT_HTML, null);
                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertEquals(baseline, response.getBody(), parameter + ": " + payload);
                assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
            }
        }
    }

    @Test
    void rejectedRegistrationAndMissingRoutesCannotEmitPayloadMarkup() throws Exception {
        for (MediaType accept : ACCEPTS) {
            for (String payload : PAYLOADS) {
                HttpHeaders headers = new HttpHeaders();
                headers.setAccept(List.of(accept));
                headers.setContentType(MediaType.APPLICATION_JSON);
                ResponseEntity<String> rejected = rest.exchange(URI.create(base() + "/api/auth/register"),
                        HttpMethod.POST, new HttpEntity<>(json.writeValueAsString(
                                java.util.Map.of("username", payload, "password", "password123")), headers),
                        String.class);
                assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
                assertSafe(rejected);
                ResponseEntity<String> missing = request(HttpMethod.GET,
                        "/xss-probe-missing?message=" + encode(payload), accept, null);
                assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
                assertSafe(missing);
            }
        }
    }

    @Test
    void authenticatedBindingErrorsReflectPayloadOnlyAsData() throws Exception {
        admin = new User();
        admin.setUsername("xss-" + UUID.randomUUID());
        admin.setDisplayName("Disposable XSS test admin");
        admin.setPasswordHash("unused-test-hash");
        admin.setRole(Role.ADMIN);
        admin = users.saveAndFlush(admin);
        String sid = UUID.randomUUID().toString();
        sessions.register(admin.getUsername(), sid, Role.ADMIN, true);
        String token = jwt.generate(admin.getUsername(), Role.ADMIN, sid);
        for (MediaType accept : ACCEPTS) {
            for (String payload : PAYLOADS) {
                ResponseEntity<String> response = request(HttpMethod.POST,
                        "/api/admin/contests?title=unused&durationMinutes=" + encode(payload), accept, token);
                assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                assertSafe(response);
                if (accept.equals(MediaType.APPLICATION_JSON)) {
                    // Spring's integer converter removes whitespace before reporting the value.
                    assertTrue(json.readTree(response.getBody()).path("message").asText().contains("xss-probe"),
                            "Exercise a genuinely reflected error, not just a constant rejection");
                }
            }
        }
    }

    private void assertSafe(ResponseEntity<String> response) throws Exception {
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertNotNull(response.getBody());
        MediaType type = response.getHeaders().getContentType();
        assertNotNull(type);
        if (MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
            assertTrue(json.readTree(response.getBody()).isObject());
        } else {
            assertTrue(MediaType.TEXT_HTML.isCompatibleWith(type), "Unexpected content type: " + type);
            var document = Jsoup.parse(response.getBody());
            assertTrue(document.select("script,img,svg,iframe,object,embed").isEmpty(),
                    "Attack payload became an active HTML element");
            for (var element : document.getAllElements()) {
                for (var attribute : element.attributes()) {
                    assertFalse(attribute.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("on"));
                }
            }
        }
    }

    private ResponseEntity<String> request(HttpMethod method, String path, MediaType accept, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(accept));
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(URI.create(base() + path), method, new HttpEntity<>(headers), String.class);
    }

    private String base() { return "http://127.0.0.1:" + port; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
