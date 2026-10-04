/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import com.arbitrator.server.repo.UserRepository;

/** Validates the Bearer token on every request (NFR-S01). */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Body sent when a token was superseded by a later login elsewhere (item 5). */
    public static final String SESSION_SUPERSEDED = "SESSION_SUPERSEDED";
    public static final String VERIFIED_SESSION_ID = "arbitrator.verifiedSessionId";

    private final JwtService jwtService;
    private final ActiveSessionRegistry sessions;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwtService, ActiveSessionRegistry sessions,
                         UserRepository users) {
        this.jwtService = jwtService;
        this.sessions = sessions;
        this.users = users;
    }

    /** A health probe must still respond when role lookup cannot reach the database. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/admin/health/live".equals(path) || "/admin/health/ready".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            Claims claims = jwtService.parse(header.substring(7));
            if (claims != null) {
                String username = claims.getSubject();
                String sid = claims.get("sid", String.class);
                // Every accepted token must belong to the currently active
                // login. This deliberately invalidates old sid-less tokens.
                if (username == null || sid == null || !sessions.isActive(username, sid)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    // Writing the body by hand (no Spring message converter in
                    // play here, unlike a ResponseStatusException) means the
                    // container's default response charset — ISO-8859-1, not
                    // UTF-8 — applies unless set explicitly. Left unset, the
                    // em dash below silently corrupts to "?" on the wire.
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"error\":\"" + SESSION_SUPERSEDED + "\","
                                    + "\"message\":\"Your session ended. Please sign in again.\"}");
                    return;
                }
                // Never authorize from the client-carried role claim. A role
                // change (especially an admin demotion) takes effect on the
                // very next request rather than when the JWT expires.
                var currentUser = users.findByUsername(username);
                if (!sessions.validateRole(username, sid,
                        currentUser.map(user -> user.getRole()).orElse(null))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"SESSION_ROLE_CHANGED\","
                            + "\"message\":\"Account permissions changed. Sign in again.\"}");
                    return;
                }
                if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())
                        && !"OPTIONS".equals(request.getMethod()) && !sessions.recordActivity(username, sid)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                currentUser.ifPresent(user -> {
                    request.setAttribute(VERIFIED_SESSION_ID, sid);
                    request.setAttribute(AuditContext.AUTH_ACTOR, username);
                    var auth = new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        chain.doFilter(request, response);
    }
}
