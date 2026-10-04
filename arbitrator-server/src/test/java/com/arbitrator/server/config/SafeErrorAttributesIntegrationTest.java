package com.arbitrator.server.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Real HTTP checks for the framework /error path and existing 4xx message contract. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SafeErrorAttributesIntegrationTest.ProbeController.class)
class SafeErrorAttributesIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newHttpClient();

    @Test void unexpectedFailureHasSafeBodyAndMatchingServerRequestId() throws Exception {
        HttpResponse<String> response = get(500, "application/json");
        assertEquals(500, response.statusCode());
        var body = json.readTree(response.body());
        assertEquals(SafeErrorAttributes.PUBLIC_MESSAGE, body.get("message").asText());
        String id = response.headers().firstValue("X-Request-ID").orElseThrow();
        UUID.fromString(id);
        assertEquals(id, body.get("requestId").asText());
        for (String field : List.of("trace", "exception", "errors", "path")) assertFalse(body.has(field));
        for (String secret : List.of("PRIVATE_INTERNAL_SENTINEL", "/tmp/internal", "SQL", "IllegalStateException"))
            assertFalse(response.body().contains(secret));
    }

    @Test void htmlErrorViewDoesNotRenderInternalDetails() throws Exception {
        HttpResponse<String> response = get(500, "text/html");
        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("PRIVATE_INTERNAL_SENTINEL"));
        assertFalse(response.body().contains("/tmp/internal"));
        assertFalse(response.body().contains("IllegalStateException"));
        UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow());
    }

    @Test void expectedClientErrorsKeepTheirStatusAndGuidance() throws Exception {
        for (int status : List.of(400, 401, 403, 409, 429)) {
            HttpResponse<String> response = get(status, "application/json");
            assertEquals(status, response.statusCode());
            var body = json.readTree(response.body());
            assertEquals("GUIDANCE_" + status, body.get("message").asText());
            assertEquals(status, body.get("status").asInt());
            UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow());
        }
    }

    HttpResponse<String> get(int status, String accept) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/error-probe/" + status))
                .header("Accept", accept).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/auth/error-probe/{status}")
        void fail(@PathVariable int status) {
            if (status == 500) throw new IllegalStateException("PRIVATE_INTERNAL_SENTINEL /tmp/internal SQL");
            throw new ResponseStatusException(HttpStatus.valueOf(status), "GUIDANCE_" + status);
        }
    }
}
