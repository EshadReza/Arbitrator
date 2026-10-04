package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtService;

/** Wrong verbs must not invoke a route or bypass its role gate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class HttpMethodRestrictionIntegrationTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired ContestRepository contests;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @Autowired ActiveSessionRegistry sessions;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private Long contestId;
    private Long adminId;
    private Long studentId;
    private String adminName;
    private String studentName;

    @AfterEach void cleanup() {
        if (contestId != null && contests.existsById(contestId)) contests.deleteById(contestId);
        if (adminName != null) sessions.clear(adminName);
        if (studentName != null) sessions.clear(studentName);
        if (adminId != null) users.deleteById(adminId);
        if (studentId != null) users.deleteById(studentId);
        if (adminName != null) jdbc.update("DELETE FROM audit_events WHERE actor = ?", adminName);
        if (studentName != null) jdbc.update("DELETE FROM audit_events WHERE actor = ?", studentName);
    }

    @Test void unexpectedMethodsAndOverrideHintsCannotMutateOrEchoCredentials() throws Exception {
        adminName = "method_admin_" + UUID.randomUUID().toString().substring(0, 8);
        studentName = "method_student_" + UUID.randomUUID().toString().substring(0, 8);
        adminId = user(adminName, Role.ADMIN);
        studentId = user(studentName, Role.STUDENT);
        String adminToken = token(adminName, Role.ADMIN);
        String studentToken = token(studentName, Role.STUDENT);
        Contest contest = new Contest();
        contest.setTitle("Method test " + UUID.randomUUID());
        contest.setDurationMinutes(60);
        contestId = contests.save(contest).getId();
        String adminPath = "/api/admin/contests/" + contestId;

        for (String method : new String[]{"POST", "PUT", "PATCH", "TRACE", "TRACK"}) {
            var response = request(method, adminPath, "", adminToken, null);
            assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
                    method + " unexpectedly succeeded: " + response.statusCode());
            assertTrue(contests.existsById(contestId), method + " changed the contest");
            assertFalse(response.body().contains(adminToken), method + " reflected credentials");
        }

        var headerOverride = request("POST", adminPath, "", adminToken, "DELETE");
        assertEquals(405, headerOverride.statusCode());
        assertTrue(contests.existsById(contestId));
        var formOverride = formOverride(adminPath, adminToken);
        assertEquals(405, formOverride.statusCode());
        assertTrue(contests.existsById(contestId));

        assertEquals(403, request("DELETE", adminPath, "", studentToken, null).statusCode());
        assertTrue(contests.existsById(contestId));
        assertEquals(403, request("PUT", "/api/admin/problems/999999999", "{}", studentToken, null).statusCode());

        for (String method : new String[]{"GET", "PUT", "PATCH", "DELETE", "TRACE"}) {
            var response = request(method, "/api/submissions", "", studentToken, null);
            assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
                    method + " unexpectedly reached submission creation: " + response.statusCode());
            assertFalse(response.body().contains(studentToken), method + " reflected credentials");
        }
    }

    private Long user(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwords.encode("Orbit7!Lake"));
        user.setRole(role);
        return users.save(user).getId();
    }

    private String token(String username, Role role) {
        String sid = UUID.randomUUID().toString();
        sessions.register(username, sid, role, true);
        return jwt.generate(username, role, sid);
    }

    private HttpResponse<String> request(String method, String path, String body, String token,
                                         String override) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        if (override != null) builder.header("X-HTTP-Method-Override", override);
        return http.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> formOverride(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_method=DELETE")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 3306), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
