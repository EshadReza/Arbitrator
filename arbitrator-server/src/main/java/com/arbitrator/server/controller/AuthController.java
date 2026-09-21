/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.server.service.UserService;
import com.arbitrator.server.security.JwtAuthFilter;
import com.arbitrator.server.security.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class AuthController {

    private final UserService userService;
    private final LoginThrottle loginThrottle;

    public AuthController(UserService userService, LoginThrottle loginThrottle) {
        this.userService = userService;
        this.loginThrottle = loginThrottle;
    }

    /** FR-01 EARS: 201 Created on success. */
    @PostMapping(ApiPaths.AUTH_REGISTER)
    public ResponseEntity<LoginResponse> register(@RequestBody LoginRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(req));
    }

    /** FR-02. */
    @PostMapping(ApiPaths.AUTH_LOGIN)
    public LoginResponse login(@RequestBody LoginRequest req, HttpServletRequest request) {
        LoginThrottle.Ticket ticket = loginThrottle.begin(req.username(), request.getRemoteAddr());
        Boolean verified = null;
        try {
            LoginResponse response = userService.login(req, username -> loginThrottle.bindAccount(ticket, username));
            verified = true;
            return response;
        } catch (ResponseStatusException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 400) verified = false;
            else if (status == 409) verified = true;
            throw e;
        } finally {
            loginThrottle.finish(ticket, verified);
        }
    }

    /**
     * FR-02 counterpart — see UserService.logout's javadoc. Sits under
     * /api/auth/** (permitAll at the SecurityConfig layer, same as login —
     * that prefix has to stay open for a token-less client to reach login
     * at all), so an absent/invalid Bearer token reaches this method as a
     * null Principal rather than being rejected upstream; guarded here
     * instead of touching SecurityConfig's path-pattern rules for one route.
     */
    @PostMapping(ApiPaths.AUTH_LOGOUT)
    public void logout(Principal principal, HttpServletRequest request) {
        Object sid = request.getAttribute(JwtAuthFilter.VERIFIED_SESSION_ID);
        if (principal == null || !(sid instanceof String)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
        userService.logout(principal.getName(), (String) sid);
    }

    /** Authenticated explicit UI activity; background reads never extend idle lifetime. */
    @PostMapping("/api/auth/activity")
    public void activity(Principal principal, HttpServletRequest request) {
        if (principal == null || !(request.getAttribute(JwtAuthFilter.VERIFIED_SESSION_ID) instanceof String)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
    }
}
