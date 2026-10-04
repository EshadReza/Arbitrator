package com.arbitrator.server.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Request metadata only; never captures a body, query string, cookie or token. */
public record AuditContext(String actor, String action, String peerIp) {
    public static final String ATTRIBUTE = AuditContext.class.getName();
    public static final String AUTH_ACTOR = ATTRIBUTE + ".authActor";
    public static final String AUTH_SUBJECT = ATTRIBUTE + ".authSubject";

    public static AuditContext current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return (AuditContext) attrs.getRequest().getAttribute(ATTRIBUTE);
        }
        return null;
    }

    public static String safeIdentity(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") ? value : null;
    }

    public static boolean mutation(HttpServletRequest request) {
        return switch (request.getMethod()) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }
}
