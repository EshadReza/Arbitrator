package com.arbitrator.server.monitor;

import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Counts statuses without retaining request details or changing responses. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class OperationalRequestMetricsFilter extends OncePerRequestFilter {
    private final OperationalMetrics metrics;

    public OperationalRequestMetricsFilter(OperationalMetrics metrics) {
        this.metrics = metrics;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            metrics.recordHttpStatus(status, request.getRequestURI());
        }
    }
}
