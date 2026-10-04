/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Decision D3 (TBD-06 resolved): the admin surface is DOUBLE-locked —
 * this filter rejects any request to /admin/** or /api/admin/** that does not
 * originate from the loopback interface, and Spring Security independently
 * requires the ADMIN role on /api/admin/**. Runs before the security chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class LoopbackAdminFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        if (uri.startsWith("/admin") || uri.startsWith("/api/admin")) {
            String remote = request.getRemoteAddr();
            boolean loopback = "127.0.0.1".equals(remote)
                    || "0:0:0:0:0:0:0:1".equals(remote)
                    || "::1".equals(remote);
            if (!loopback) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "Admin interface is accessible from the server machine only");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
