/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.config;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Security response policy, including requests rejected before Spring Security. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = SecurityHeadersFilter.class.getName() + ".requestId";

    /** Server-generated; request headers cannot choose the diagnostic identifier. */
    public static String requestId(HttpServletRequest request) {
        Object current = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (current instanceof String id) return id;
        String id = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, id);
        return id;
    }

    static final String CSP = "default-src 'self'; "
            + "script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; "
            + "font-src 'self'; "
            + "connect-src 'self'; "
            + "frame-src 'self'; "
            + "object-src 'none'; "
            + "base-uri 'none'; "
            + "form-action 'self'; "
            + "frame-ancestors 'none'; "
            + "worker-src 'none'; "
            + "manifest-src 'none'";
    static final String PERMISSIONS =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=()";
    static final String CACHE_CONTROL = "no-cache, no-store, max-age=0, must-revalidate";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Request-ID", requestId(request));
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", PERMISSIONS);
        // Establish the existing Spring Security cache policy before any
        // outer filter can reject a request (for example oversized JSON).
        response.setHeader("Cache-Control", CACHE_CONTROL);
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        chain.doFilter(request, response);
    }
}
