package com.labjudge.server.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.labjudge.common.api.ApiPaths;
import com.labjudge.common.dto.LoginRequest;
import com.labjudge.common.dto.LoginResponse;
import com.labjudge.server.service.UserService;

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
}
