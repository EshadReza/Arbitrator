/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JsonBodyLimitFilterTest {

    private final JsonBodyLimitFilter filter = new JsonBodyLimitFilter();

    @Test
    void oversizedJsonIsRejectedBeforeTheFilterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/submissions");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[JsonBodyLimitFilter.MAX_JSON_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilterInternal(request, response, (req, res) -> called.set(true));

        assertEquals(413, response.getStatus());
        assertFalse(called.get());
    }

    @Test
    void acceptedJsonCanStillBeReadDownstream() throws Exception {
        byte[] body = "{\"value\":42}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean bodyMatched = new AtomicBoolean();

        filter.doFilterInternal(request, response, (req, res) -> {
            try {
                bodyMatched.set(java.util.Arrays.equals(body, req.getInputStream().readAllBytes()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertTrue(bodyMatched.get());
    }
}
