/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.arbitrator.server.security.ApiRateLimiter;
import com.arbitrator.server.security.ApiRateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiIpRateLimitFilter extends ApiRateLimitFilter {
    public ApiIpRateLimitFilter(ApiRateLimiter limiter, ObjectMapper json) { super(limiter, json, false); }
}
