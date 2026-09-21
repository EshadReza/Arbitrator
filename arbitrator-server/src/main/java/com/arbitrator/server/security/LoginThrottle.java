/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Single-process admission control: reserve before bcrypt, never sleep request threads. */
@Component
public class LoginThrottle {
    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);
    private final Clock clock;
    private final Settings settings;
    private final Map<String, State> states = new HashMap<>();
    private long nextCleanup;

    public record Settings(int accountAttempts, int ipAttempts, int accountFailures, int ipFailures,
                           int accountConcurrent, int ipConcurrent, long windowSeconds,
                           long baseCooldownSeconds, long maxCooldownSeconds, int maxKeys) {
        public Settings {
            if (accountAttempts < 1 || ipAttempts < 1 || accountFailures < 1 || ipFailures < 1
                    || accountConcurrent < 1 || ipConcurrent < 1 || windowSeconds < 1
                    || baseCooldownSeconds < 1 || maxCooldownSeconds < baseCooldownSeconds
                    || maxKeys < 2 || windowSeconds > 86400 || maxCooldownSeconds > 86400) {
                throw new IllegalArgumentException("Invalid login throttle configuration");
            }
        }
    }

    @Autowired
    public LoginThrottle(@Value("${arbitrator.auth.login.account-attempts:20}") int accountAttempts,
                         @Value("${arbitrator.auth.login.ip-attempts:120}") int ipAttempts,
                         @Value("${arbitrator.auth.login.account-failures:5}") int accountFailures,
                         @Value("${arbitrator.auth.login.ip-failures:20}") int ipFailures,
                         @Value("${arbitrator.auth.login.account-concurrent:2}") int accountConcurrent,
                         @Value("${arbitrator.auth.login.ip-concurrent:16}") int ipConcurrent,
                         @Value("${arbitrator.auth.login.window-seconds:60}") long windowSeconds,
                         @Value("${arbitrator.auth.login.base-cooldown-seconds:5}") long baseCooldown,
                         @Value("${arbitrator.auth.login.max-cooldown-seconds:300}") long maxCooldown,
                         @Value("${arbitrator.auth.login.max-keys:10000}") int maxKeys) {
        this(Clock.systemUTC(), new Settings(accountAttempts, ipAttempts, accountFailures, ipFailures,
                accountConcurrent, ipConcurrent, windowSeconds, baseCooldown, maxCooldown, maxKeys));
    }

    LoginThrottle(Clock clock, Settings settings) {
        this.clock = clock;
        this.settings = settings;
    }

    private static final class State {
        long windowStart, lastSeen, blockedUntil;
        int attempts, failures, inFlight;
        State(long now) { windowStart = lastSeen = now; }
    }

    public static final class Ticket {
        private State account;
        private final State ip;
        private boolean completed;
        private Ticket(State account, State ip) { this.account = account; this.ip = ip; }
    }

    /** Bind DB-resolved identity before bcrypt: MySQL collation aliases share the real account budget. */
    public synchronized void bindAccount(Ticket ticket, String canonicalUsername) {
        String key = "account:" + accountDigest(canonicalUsername);
        State canonical = states.get(key);
        if (canonical == ticket.account) return;
        long now = clock.millis();
        if (canonical == null) {
            if (states.size() >= settings.maxKeys()) throw new Limited(5);
            canonical = new State(now);
            states.put(key, canonical);
        }
        resetWindow(canonical, now);
        long wait = waitMillis(canonical, now, settings.accountAttempts(), settings.accountConcurrent());
        if (wait > 0) throw new Limited((wait + 999) / 1000);
        ticket.account.inFlight--;
        ticket.account = canonical;
        canonical.attempts++;
        canonical.inFlight++;
        canonical.lastSeen = now;
    }

    public synchronized Ticket begin(String username, String remoteAddress) {
        long now = clock.millis();
        // Expire idle entries without evicting active penalties/reservations to make room for attackers.
        long retention = Math.max(settings.windowSeconds(), settings.maxCooldownSeconds()) * 1000 + 60000;
        if (now >= nextCleanup) {
            states.values().removeIf(s -> s.inFlight == 0 && now >= s.blockedUntil
                    && now - s.lastSeen >= retention);
            nextCleanup = now + 1000;
        }
        String accountKey = "account:" + accountDigest(username);
        String ipKey = "ip:" + remoteAddress;
        int required = (states.containsKey(accountKey) ? 0 : 1) + (states.containsKey(ipKey) ? 0 : 1);
        if (states.size() + required > settings.maxKeys()) throw new Limited(5);
        State account = states.computeIfAbsent(accountKey, ignored -> new State(now));
        State ip = states.computeIfAbsent(ipKey, ignored -> new State(now));
        resetWindow(account, now);
        resetWindow(ip, now);
        long wait = Math.max(waitMillis(account, now, settings.accountAttempts(), settings.accountConcurrent()),
                waitMillis(ip, now, settings.ipAttempts(), settings.ipConcurrent()));
        if (wait > 0) throw new Limited((wait + 999) / 1000);
        account.attempts++;
        ip.attempts++;
        account.inFlight++;
        ip.inFlight++;
        account.lastSeen = ip.lastSeen = now;
        return new Ticket(account, ip);
    }

    private static String accountDigest(String username) {
        try {
            String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void resetWindow(State state, long now) {
        if (now - state.windowStart >= settings.windowSeconds() * 1000) {
            state.windowStart = now;
            state.attempts = 0;
        }
    }

    private long waitMillis(State state, long now, int attempts, int concurrent) {
        long wait = Math.max(0, state.blockedUntil - now);
        if (state.attempts >= attempts) wait = Math.max(wait, state.windowStart + settings.windowSeconds() * 1000 - now);
        if (state.inFlight >= concurrent) wait = Math.max(wait, 1000);
        return wait;
    }

    /** true = verified credentials (including session-conflict 409); null = server failure. */
    public synchronized void finish(Ticket ticket, Boolean verified) {
        if (ticket.completed) return;
        ticket.completed = true;
        ticket.account.inFlight--;
        ticket.ip.inFlight--;
        long now = clock.millis();
        ticket.account.lastSeen = ticket.ip.lastSeen = now;
        if (Boolean.TRUE.equals(verified)) {
            ticket.account.failures = 0;
            // Never clear the shared IP's failure/attempt budget by logging in to an attacker-owned account.
        } else if (Boolean.FALSE.equals(verified)) {
            penalize(ticket.account, settings.accountFailures(), now, "account");
            penalize(ticket.ip, settings.ipFailures(), now, "IP");
        }
    }

    private void penalize(State state, int threshold, long now, String scope) {
        state.failures = Math.min(state.failures + 1, threshold + 30);
        if (state.failures < threshold) return;
        long delay = Math.min(settings.maxCooldownSeconds(),
                settings.baseCooldownSeconds() * (1L << Math.min(20, state.failures - threshold)));
        state.blockedUntil = Math.max(state.blockedUntil, now + delay * 1000);
        // No submitted identifiers, passwords, tokens, or attacker-controlled headers are logged.
        log.warn("Suspicious login failure burst: {} cooldown applied for {} seconds", scope, delay);
    }

    public static final class Limited extends ResponseStatusException {
        private final long seconds;
        Limited(long seconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts. Try again in " + seconds + " seconds");
            this.seconds = seconds;
        }
        @Override public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            return headers;
        }
    }
}
