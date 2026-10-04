package com.arbitrator.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.arbitrator.server.security.AuditContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class AuditConfiguration implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (AuditContext.mutation(request) && request.getRequestURI().startsWith("/api/admin/")
                        && handler instanceof HandlerMethod method && request.getUserPrincipal() != null) {
                    request.setAttribute(AuditContext.ATTRIBUTE, new AuditContext(request.getUserPrincipal().getName(),
                            method.getBeanType().getSimpleName() + "." + method.getMethod().getName(), request.getRemoteAddr()));
                }
                return true;
            }
        });
    }
}
