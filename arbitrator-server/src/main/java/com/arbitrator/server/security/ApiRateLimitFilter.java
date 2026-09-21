/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import java.io.IOException;
import java.util.Map;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** IP mode runs before body parsing; account mode runs after authorization. */
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private final ApiRateLimiter limiter;
    private final ObjectMapper json;
    private final boolean accountScope;
    public ApiRateLimitFilter(ApiRateLimiter limiter, ObjectMapper json, boolean accountScope) {
        this.limiter = limiter;
        this.json = json;
        this.accountScope = accountScope;
    }
    @Override protected String getAlreadyFilteredAttributeName() {
        return getClass().getName() + (accountScope ? ".account" : ".ip");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if (!path.startsWith("/api/")) { chain.doFilter(request, response); return; }
        String identity = request.getRemoteAddr();
        if (accountScope) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
                chain.doFilter(request, response);
                return;
            }
            identity = auth.getName(); // Stored canonical user identity from JwtAuthFilter, not a client sid/header.
        }
        long retry = limiter.admit(accountScope, identity, ApiRateLimiter.category(request.getMethod(), path));
        if (retry == 0) { chain.doFilter(request, response); return; }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retry));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), Map.of("status", 429, "error", "Too Many Requests",
                "message", "Too many requests. Try again in " + retry + " seconds", "path", request.getRequestURI()));
    }
}
