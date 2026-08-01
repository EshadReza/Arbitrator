package com.arbitrator.server.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.JwtService;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public UserService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /** FR-01: unique username, >= 8 char password, bcrypt storage. */
    public LoginResponse register(LoginRequest req) {
        if (req.username() == null || req.username().isBlank()
                || req.password() == null || req.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Username required and password must be at least 8 characters");
        }
        if (users.existsByUsername(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
        User u = new User();
        u.setUsername(req.username().trim());
        u.setDisplayName(req.displayName() == null || req.displayName().isBlank()
                ? req.username().trim() : req.displayName().trim());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(Role.STUDENT);           // admins are created server-side only
        users.save(u);
        return new LoginResponse(jwt.generate(u.getUsername(), u.getRole()),
                u.getUsername(), u.getDisplayName(), u.getRole());
    }

    /** FR-02: authenticate and issue a 12-hour JWT. */
    public LoginResponse login(LoginRequest req) {
        User u = users.findByUsername(req.username() == null ? "" : req.username().trim())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!encoder.matches(req.password(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return new LoginResponse(jwt.generate(u.getUsername(), u.getRole()),
                u.getUsername(), u.getDisplayName(), u.getRole());
    }

    public User requireByUsername(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}
