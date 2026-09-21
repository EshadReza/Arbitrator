/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded process-local fixed windows, with atomic aggregate/category admission. */
@Component
public class ApiRateLimiter {
    private final Clock clock;
    private final long windowMillis;
    private final int maxKeys, accountTotal, ipTotal;
    private final Map<String, Integer> account, ip;
    private final Map<String, Window> windows = new HashMap<>();
    private long nextCleanup;
    private static class Window {
        final long start;
        int count;
        Window(long start) { this.start = start; }
    }
    @Autowired public ApiRateLimiter(ApiRateLimitProperties props) { this(props, Clock.systemUTC()); }
    ApiRateLimiter(ApiRateLimitProperties props, Clock clock) {
        this.clock = clock;
        if (props.getWindowSeconds() < 1 || props.getWindowSeconds() > 86400 || props.getMaxKeys() < 2
                || props.getAccountTotal() < 1 || props.getIpTotal() < 1) {
            throw new IllegalArgumentException("Invalid API rate-limit configuration");
        }
        account = Map.copyOf(props.getAccount());
        ip = Map.copyOf(props.getIp());
        if (!account.containsKey("read") || !account.containsKey("write")
                || !ip.containsKey("read") || !ip.containsKey("write")
                || account.values().stream().anyMatch(n -> n < 1) || ip.values().stream().anyMatch(n -> n < 1)) {
            throw new IllegalArgumentException("API category limits must be positive and include read/write");
        }
        windowMillis = props.getWindowSeconds() * 1000L;
        maxKeys = props.getMaxKeys();
        accountTotal = props.getAccountTotal();
        ipTotal = props.getIpTotal();
    }

    /** Returns retry seconds, or zero if admitted; blocked calls never move the window. */
    public synchronized long admit(boolean accountScope, String identity, String category) {
        long now = clock.millis();
        if (now >= nextCleanup) {
            windows.values().removeIf(w -> now - w.start >= windowMillis);
            nextCleanup = now + 1000;
        }
        String prefix = (accountScope ? "account:" : "ip:") + identity + ":";
        String totalKey = prefix + "total", categoryKey = prefix + category;
        Map<String, Integer> limits = accountScope ? account : ip;
        int totalLimit = accountScope ? accountTotal : ipTotal;
        int categoryLimit = limits.getOrDefault(category, limits.get("write"));
        int missing = (windows.containsKey(totalKey) ? 0 : 1) + (windows.containsKey(categoryKey) ? 0 : 1);
        if (windows.size() + missing > maxKeys) return 5;
        Window total = window(totalKey, now), specific = window(categoryKey, now);
        long wait = 0;
        if (total.count >= totalLimit) wait = Math.max(wait, total.start + windowMillis - now);
        if (specific.count >= categoryLimit) wait = Math.max(wait, specific.start + windowMillis - now);
        if (wait > 0) return (wait + 999) / 1000;
        total.count++;
        specific.count++;
        return 0;
    }
    private Window window(String key, long now) {
        Window result = windows.get(key);
        if (result == null || now - result.start >= windowMillis) {
            result = new Window(now);
            windows.put(key, result);
        }
        return result;
    }

    public static String category(String method, String servletPath) {
        String path = servletPath.replaceAll(";[^/]*", "");
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) return "read";
        if (method.equals("POST")) {
            if (path.equals("/api/auth/register")) return "registration";
            if (path.equals("/api/auth/login")) return "login";
            if (path.equals("/api/run")) return "run";
            if (path.equals("/api/submissions")) return "submit";
            if (path.equals("/api/clarifications")) return "clarification";
            if (path.matches("/api/contests/[^/]+/join")) return "join";
        }
        return "write";
    }
}
