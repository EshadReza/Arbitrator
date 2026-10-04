/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter headers = new SecurityHeadersFilter();

    @Test
    void normalResponseGetsCompletePolicyWithoutHttpHsts() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        headers.doFilter(request("GET", "/admin/index.html"), response,
                (request, result) -> ((MockHttpServletResponse) result).setStatus(200));

        assertPolicy(response);
        assertNull(response.getHeader("Strict-Transport-Security"));
        assertDoesNotThrow(() -> java.util.UUID.fromString(response.getHeader("X-Request-ID")));
    }

    @Test
    void requestIdIsGeneratedByServerInsteadOfTrustingCaller() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/contests");
        request.addHeader("X-Request-ID", "PRIVATE_CALLER_CHOICE");
        MockHttpServletResponse response = new MockHttpServletResponse();
        headers.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {});
        assertNotEquals("PRIVATE_CALLER_CHOICE", response.getHeader("X-Request-ID"));
        assertEquals(SecurityHeadersFilter.requestId(request), response.getHeader("X-Request-ID"));
    }

    @Test
    void nonLoopbackAdminRejectionKeepsCompletePolicy() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/admin/contests");
        request.setRemoteAddr("192.0.2.44");
        MockHttpServletResponse response = new MockHttpServletResponse();
        LoopbackAdminFilter loopback = new LoopbackAdminFilter();

        headers.doFilter(request, response,
                (securedRequest, securedResponse) -> loopback.doFilter(
                        securedRequest, securedResponse,
                        (ignoredRequest, ignoredResponse) -> fail("non-loopback admin request reached its controller")));

        assertEquals(403, response.getStatus());
        assertPolicy(response);
    }

    @Test
    void oversizedJsonRejectionKeepsCompletePolicy() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/login");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[JsonBodyLimitFilter.MAX_JSON_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JsonBodyLimitFilter bodyLimit = new JsonBodyLimitFilter();

        headers.doFilter(request, response,
                (securedRequest, securedResponse) -> bodyLimit.doFilter(
                        securedRequest, securedResponse,
                        (ignoredRequest, ignoredResponse) -> fail("oversized JSON reached its controller")));

        assertEquals(413, response.getStatus());
        assertPolicy(response);
    }

    static void assertPolicy(MockHttpServletResponse response) {
        assertEquals(SecurityHeadersFilter.CACHE_CONTROL, response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertEquals("0", response.getHeader("Expires"));
        assertEquals(SecurityHeadersFilter.CSP, response.getHeader("Content-Security-Policy"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals(SecurityHeadersFilter.PERMISSIONS, response.getHeader("Permissions-Policy"));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
