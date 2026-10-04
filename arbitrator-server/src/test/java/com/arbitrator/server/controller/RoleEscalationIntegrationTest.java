/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.UserRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Live HTTP regression coverage for every known role-escalation path. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class RoleEscalationIntegrationTest {

    private static final String TEST_SECRET =
            "test-only-secret-0123456789abcdef0123456789abcdef0123456789";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private com.arbitrator.server.security.JwtService jwt;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (long userId : userIds) {
            users.findById(userId).ifPresent(users::delete);
        }
    }

    @Test
    void activityEndpointRequiresVerifiedBearerAuthentication() throws Exception {
        // HttpURLConnection's streaming POST implementation cannot expose this 401 response.
        var anonymous = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(base() + "/api/auth/activity"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(401, anonymous.statusCode());
        LoginResponse student = register(unique("activity-auth"));
        tracked(student.username());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(student.token());
        assertEquals(HttpStatus.OK, rest.exchange(base() + "/api/auth/activity", HttpMethod.POST,
                new HttpEntity<>(headers), String.class).getStatusCode());
    }

    @Test
    void registrationCannotInjectAnAdministratorRole() {
        String username = unique("role-injection");
        ResponseEntity<String> rejected = rest.postForEntity(
                base() + ApiPaths.AUTH_REGISTER,
                Map.of(
                        "username", username,
                        "displayName", "Attempted Admin",
                        "password", "Orbit7!Lake",
                        "role", "ADMIN"),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertFalse(users.findByUsername(username).isPresent());

        LoginResponse response = register(username);
        User user = tracked(username);
        assertEquals(Role.STUDENT, response.role());
        assertEquals(Role.STUDENT, user.getRole());
        assertEquals(HttpStatus.FORBIDDEN,
                get(response.token(), ApiPaths.ADMIN_CONTESTS).getStatusCode());
    }

    @Test
    void registrationRejectsUnicodeSpoofingButKeepsMultilingualNames() {
        String deceptiveUser = unique("unicode-spoof");
        var rejected = rest.postForEntity(base() + ApiPaths.AUTH_REGISTER,
                new LoginRequest(deceptiveUser, "Ada\u202Eadmin", "Orbit7!Lake"), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertFalse(users.findByUsername(deceptiveUser).isPresent());

        String normalUser = unique("unicode-normal");
        var accepted = rest.postForEntity(base() + ApiPaths.AUTH_REGISTER,
                new LoginRequest(normalUser, "Cafe\u0301 রাহিম \uD83D\uDE80", "Orbit7!Lake"),
                LoginResponse.class);
        assertEquals(HttpStatus.CREATED, accepted.getStatusCode());
        assertNotNull(accepted.getBody());
        assertEquals("Café রাহিম \uD83D\uDE80", accepted.getBody().displayName());
        assertEquals(accepted.getBody().displayName(), tracked(normalUser).getDisplayName());
    }

    @Test
    void modifiedAndSidlessTokensCannotClaimAdministratorAccess() {
        LoginResponse login = register(unique("forged-role"));
        tracked(login.username());

        assertEquals(HttpStatus.UNAUTHORIZED,
                get(changeRoleWithoutResigning(login.token()), ApiPaths.ADMIN_CONTESTS)
                        .getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get(signedTokenWithoutSession(login.username()), ApiPaths.ADMIN_CONTESTS)
                        .getStatusCode());
    }

    @Test
    void promotionRequiresFreshLoginAndRotatesTheSessionId() {
        LoginResponse student = register(unique("promotion-session"));
        User user = tracked(student.username());
        user.setRole(Role.ADMIN);
        users.saveAndFlush(user);
        assertEquals(HttpStatus.UNAUTHORIZED,
                get(student.token(), ApiPaths.ADMIN_CONTESTS).getStatusCode());
        LoginResponse admin = rest.postForObject(base() + ApiPaths.AUTH_LOGIN,
                new LoginRequest(user.getUsername(), null, "Orbit7!Lake"), LoginResponse.class);
        assertNotNull(admin);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                jwt.parse(student.token()).get("sid"), jwt.parse(admin.token()).get("sid"));
        assertEquals(HttpStatus.OK, get(admin.token(), ApiPaths.ADMIN_CONTESTS).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, get(student.token(), ApiPaths.ADMIN_CONTESTS).getStatusCode());
    }

    @Test
    void databaseDemotionRevokesAdminAccessBeforeTokenExpiry() {
        LoginResponse registration = register(unique("stale-admin"));
        User user = tracked(registration.username());
        user.setRole(Role.ADMIN);
        users.saveAndFlush(user);

        LoginResponse adminLogin = rest.postForObject(
                base() + ApiPaths.AUTH_LOGIN,
                new LoginRequest(user.getUsername(), null, "Orbit7!Lake", null, true),
                LoginResponse.class);
        assertNotNull(adminLogin);
        assertEquals(HttpStatus.OK,
                get(adminLogin.token(), ApiPaths.ADMIN_CONTESTS).getStatusCode());

        user.setRole(Role.STUDENT);
        users.saveAndFlush(user);

        assertEquals(HttpStatus.UNAUTHORIZED,
                get(adminLogin.token(), ApiPaths.ADMIN_CONTESTS).getStatusCode());
        LoginResponse demoted = rest.postForObject(base() + ApiPaths.AUTH_LOGIN,
                new LoginRequest(user.getUsername(), null, "Orbit7!Lake"), LoginResponse.class);
        assertNotNull(demoted);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                jwt.parse(adminLogin.token()).get("sid"), jwt.parse(demoted.token()).get("sid"));
        assertEquals(HttpStatus.FORBIDDEN, get(demoted.token(), ApiPaths.ADMIN_CONTESTS).getStatusCode());
    }

    private LoginResponse register(String username) {
        LoginResponse login = rest.postForObject(
                base() + ApiPaths.AUTH_REGISTER,
                new LoginRequest(username, "Security Test", "Orbit7!Lake"),
                LoginResponse.class);
        assertNotNull(login);
        return login;
    }

    private User tracked(String username) {
        User user = users.findByUsername(username).orElseThrow();
        userIds.add(user.getId());
        return user;
    }

    private ResponseEntity<String> get(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private String signedTokenWithoutSession(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", Role.ADMIN.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static String changeRoleWithoutResigning(String token) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.UTF_8);
        payload = payload.replace("\"role\":\"STUDENT\"", "\"role\":\"ADMIN\"");
        parts[1] = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return String.join(".", parts);
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private static String unique(String prefix) {
        return prefix + '-' + Long.toUnsignedString(System.nanoTime(), 36);
    }

    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 3306), 250);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
