/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** Live policy coverage for documents, assets, APIs, errors and early rejection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.arbitrator.server.controller.RoleEscalationIntegrationTest#mysqlIsReachable")
class SecurityHeadersIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;

    @Test
    void normalStaticApiAndErrorResponsesCarryPolicy() {
        for (String path : List.of(
                "/admin/index.html",
                "/admin/logo.png",
                "/api/contests",
                "/missing-security-header-probe")) {
            ResponseEntity<byte[]> response = rest.exchange(
                    base() + path, HttpMethod.GET, null, byte[].class);
            assertPolicy(response.getHeaders(), path);
        }
    }

    @Test
    void oversizedJsonEarlyRejectionCarriesPolicy() throws Exception {
        byte[] oversized = new byte[JsonBodyLimitFilter.MAX_JSON_BYTES + 1];
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + "/api/auth/login"))
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(oversized))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(413, response.statusCode());
        assertEarlyCachePolicy(response);
        assertEquals(SecurityHeadersFilter.CSP,
                response.headers().firstValue("Content-Security-Policy").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null));
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(null));
        assertEquals(SecurityHeadersFilter.PERMISSIONS,
                response.headers().firstValue("Permissions-Policy").orElse(null));
        UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertFalse(response.headers().firstValue("Strict-Transport-Security").isPresent());
    }

    @Test
    void oversizedStructuredJsonIsRejectedWithAndWithoutContentLength() throws Exception {
        byte[] oversized = new byte[JsonBodyLimitFilter.MAX_JSON_BYTES + 1];
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<Void> declaredLength = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/auth/login"))
                        .header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(oversized))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(413, declaredLength.statusCode());
        assertEarlyCachePolicy(declaredLength);

        HttpResponse<Void> chunked = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/auth/login"))
                        .header(HttpHeaders.CONTENT_TYPE, "application/vnd.arbitrator+json")
                        .POST(HttpRequest.BodyPublishers.ofInputStream(
                                () -> new ByteArrayInputStream(oversized)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(413, chunked.statusCode());
        assertEarlyCachePolicy(chunked);
    }

    private static void assertEarlyCachePolicy(HttpResponse<?> response) {
        assertEquals(SecurityHeadersFilter.CACHE_CONTROL,
                response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElse(null));
        assertEquals("0", response.headers().firstValue("Expires").orElse(null));
    }

    private static void assertPolicy(HttpHeaders headers, String path) {
        String cacheControl = headers.getCacheControl();
        assertNotNull(cacheControl, path);
        assertTrue(cacheControl.contains("no-store"), path + ": " + cacheControl);
        assertFalse(cacheControl.contains("public"), path + ": " + cacheControl);
        assertEquals(SecurityHeadersFilter.CSP, headers.getFirst("Content-Security-Policy"), path);
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"), path);
        assertEquals("DENY", headers.getFirst("X-Frame-Options"), path);
        assertEquals("no-referrer", headers.getFirst("Referrer-Policy"), path);
        assertEquals(SecurityHeadersFilter.PERMISSIONS, headers.getFirst("Permissions-Policy"), path);
        UUID.fromString(headers.getFirst("X-Request-ID"));
        assertNull(headers.getFirst("Strict-Transport-Security"), path);
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }
}
