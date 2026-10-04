package com.arbitrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.arbitrator.server.repo.UserRepository;

/** A real API request must fail before an unrecognized field can be silently ignored. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class ApiSchemaValidationIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void unknownLoginFieldIsBadRequestButOrdinaryInvalidCredentialsStillReachAuth() throws Exception {
        String username = "schema_" + UUID.randomUUID().toString().replace("-", "");
        String validShape = "{\"username\":\"" + username + "\",\"password\":\"Wrong7!Password\"}";

        assertEquals(400, post("/api/auth/login", validShape.substring(0, validShape.length() - 1)
                + ",\"isAdmin\":true}").statusCode());
        assertEquals(401, post("/api/auth/login", validShape).statusCode());
    }

    @Test
    void unknownRegistrationFieldCannotCreateAccount() throws Exception {
        String username = "schema_" + UUID.randomUUID().toString().replace("-", "");
        String body = "{\"username\":\"" + username + "\",\"displayName\":\"Schema Test\","
                + "\"password\":\"Orbit7!Lake\",\"isAdmin\":true}";

        assertEquals(400, post("/api/auth/register", body).statusCode());
        assertFalse(users.findByUsername(username).isPresent());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
