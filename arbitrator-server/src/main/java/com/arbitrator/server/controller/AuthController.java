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

@RestController
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /** FR-01 EARS: 201 Created on success. */
    @PostMapping(ApiPaths.AUTH_REGISTER)
    public ResponseEntity<LoginResponse> register(@RequestBody LoginRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(req));
    }

    /** FR-02. */
    @PostMapping(ApiPaths.AUTH_LOGIN)
    public LoginResponse login(@RequestBody LoginRequest req) {
        return userService.login(req);
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
    public void logout(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
        userService.logout(principal.getName());
    }
}
