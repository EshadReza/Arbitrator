package com.arbitrator.server.config;

import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.arbitrator.server.security.AuditContext;
import com.arbitrator.server.security.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuditRequestFilter extends OncePerRequestFilter {
    private static final Set<String> AUTH = Set.of("/api/auth/login", "/api/auth/register", "/api/auth/logout");
    private final AuditService audit;
    public AuditRequestFilter(AuditService audit) { this.audit = audit; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI();
        boolean auth = AUTH.contains(path);
        boolean tracked = AuditContext.mutation(request) && (auth || path.startsWith("/api/admin/"));
        boolean failed = true;
        try { chain.doFilter(request, response); failed = false; }
        finally {
            if (tracked) {
                AuditContext context = (AuditContext) request.getAttribute(AuditContext.ATTRIBUTE);
                String actor = context == null ? (String) request.getAttribute(AuditContext.AUTH_ACTOR) : context.actor();
                String action = context != null ? context.action() : auth ? "AUTH_" + path.substring(path.lastIndexOf('/') + 1).toUpperCase(java.util.Locale.ROOT) : "ADMIN_REQUEST";
                audit.request(actor, action, request.getRemoteAddr(), failed ? 500 : response.getStatus(),
                        (String) request.getAttribute(AuditContext.AUTH_SUBJECT));
            }
        }
    }
}
