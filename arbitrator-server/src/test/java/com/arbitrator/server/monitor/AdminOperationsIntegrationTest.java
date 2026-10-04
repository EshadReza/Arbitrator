package com.arbitrator.server.monitor;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class AdminOperationsIntegrationTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ActiveSessionRegistry sessions;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    private Long adminId, studentId;
    private String adminName, studentName;

    @AfterEach void cleanup() {
        if (adminName != null) sessions.clear(adminName);
        if (studentName != null) sessions.clear(studentName);
        if (adminId != null) users.deleteById(adminId);
        if (studentId != null) users.deleteById(studentId);
    }

    @Test void onlyAdminCanReadBoundedOperationsSnapshot() throws Exception {
        adminName = "ops_admin_" + UUID.randomUUID().toString().substring(0, 8);
        studentName = "ops_student_" + UUID.randomUUID().toString().substring(0, 8);
        adminId = user(adminName, Role.ADMIN);
        studentId = user(studentName, Role.STUDENT);

        assertEquals(401, get(null).statusCode());
        assertEquals(403, get(token(studentName, Role.STUDENT)).statusCode());

        String adminToken = token(adminName, Role.ADMIN);
        var response = get(adminToken);
        assertEquals(200, response.statusCode());
        var body = json.readTree(response.body());
        assertTrue(body.path("judgeQueueDepth").isInt());
        assertTrue(body.path("recent60Seconds").path("http5xx").isNumber());
        assertTrue(body.path("databasePool").path("available").asBoolean());
        assertTrue(body.path("jvmHeapUsedBytes").asLong() >= 0);
        assertFalse(response.body().contains(adminToken));
        assertFalse(response.body().contains("materialsRoot"));
        assertFalse(response.body().contains("workRoot"));
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

    private HttpResponse<String> get(String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/operations"));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
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
