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

/** Validates the Bearer token on every request (NFR-S01). */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Body sent when a token was superseded by a later login elsewhere (item 5). */
    public static final String SESSION_SUPERSEDED = "SESSION_SUPERSEDED";

    private final JwtService jwtService;
    private final ActiveSessionRegistry sessions;

    public JwtAuthFilter(JwtService jwtService, ActiveSessionRegistry sessions) {
        this.jwtService = jwtService;
        this.sessions = sessions;
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
                // sid==null means this token predates single-session tracking
                // (or was minted somewhere that deliberately opts out, e.g.
                // tests) — nothing to compare against, so it authenticates as
                // before. A real login always carries a sid, so once one is
                // present a mismatch means a later login elsewhere replaced it.
                if (sid != null && !sessions.isActive(username, sid)) {
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
                                    + "\"message\":\"Signed out — this account logged in from another device\"}");
                    return;
                }
                String role = claims.get("role", String.class);
                var auth = new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
