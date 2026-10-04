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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import com.arbitrator.server.config.LoopbackAdminFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("mysqlIsReachable")
class HealthEndpointIntegrationTest {
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void localUnauthenticatedLivenessAndComponentReadinessUseExistingPort() throws Exception {
        var live = get("/admin/health/live");
        assertEquals(200, live.statusCode());
        assertEquals("UP", json.readTree(live.body()).path("status").asText());
        assertNoStore(live);

        var ready = get("/admin/health/ready");
        var body = json.readTree(ready.body());
        assertEquals("UP", body.path("web").asText());
        for (String component : new String[] { "database", "queue", "judge" }) {
            assertTrue(body.path(component).asText().equals("UP")
                    || body.path(component).asText().equals("DOWN"));
        }
        boolean allUp = body.path("database").asText().equals("UP")
                && body.path("queue").asText().equals("UP")
                && body.path("judge").asText().equals("UP");
        assertEquals(allUp ? 200 : 503, ready.statusCode());
        assertEquals(allUp ? "READY" : "NOT_READY", body.path("status").asText());
        assertFalse(ready.body().contains("workRoot"));
        assertFalse(ready.body().contains("password"));
        assertNoStore(ready);
    }

    @Test
    void nonLoopbackPeerCannotReadEitherHealthRoute() throws Exception {
        LoopbackAdminFilter filter = new LoopbackAdminFilter();
        for (String path : new String[] { "/admin/health/live", "/admin/health/ready" }) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRemoteAddr("198.51.100.10");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> reached.set(true));
            assertEquals(403, response.getStatus());
            assertFalse(reached.get());
        }
    }

    private static void assertNoStore(HttpResponse<?> response) {
        String cacheControl = response.headers().firstValue("cache-control").orElse("");
        assertTrue(java.util.Arrays.stream(cacheControl.split(","))
                .map(String::trim).anyMatch("no-store"::equals), cacheControl);
        assertFalse(java.util.Arrays.stream(cacheControl.split(","))
                .map(String::trim).anyMatch("public"::equals), cacheControl);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
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
