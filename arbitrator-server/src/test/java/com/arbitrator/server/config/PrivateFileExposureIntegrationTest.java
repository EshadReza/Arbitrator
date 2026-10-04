package com.arbitrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Item 95: real unauthenticated requests must not download repository/config files. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.arbitrator.server.controller.RoleEscalationIntegrationTest#mysqlIsReachable")
class PrivateFileExposureIntegrationTest {
    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void repositoryConfigurationAndBackupPathsAreNotDownloadable() throws Exception {
        // Positive controls distinguish missing-file protection from a dead
        // server or a blanket denial of every request. No login is performed.
        assertEquals(200, get("/admin/index.html").statusCode());
        assertEquals(401, get("/api/contests").statusCode());

        for (String path : List.of(
                "/.git/", "/.git/HEAD", "/.git/config", "/.git/index",
                "/.env", "/.env.local", "/config", "/.svn/", "/.svn/entries",
                "/backup", "/backup.zip", "/debug", "/pom.xml", "/README.md",
                "/application.yml", "/application-local.yml", "/server.properties",
                "/db/migration/V1__baseline.sql",
                "/src/main/resources/application-local.yml",
                "/admin/.git/config", "/admin/.env", "/admin/index.html.bak")) {
            assertNotDownloadable(path);
        }
    }

    @Test
    void encodedPrivatePathsAndTraversalCannotEscapeStaticResources() throws Exception {
        for (String path : List.of(
                "/%2egit/config", "/%2eenv",
                "/admin/%2e%2e/%2e%2e/application.yml",
                "/admin/..%2f..%2f.git/config",
                "/admin/../../.git/config",
                "/admin/%252e%252e/%252e%252e/application-local.yml")) {
            assertNotDownloadable(path);
        }
    }

    private void assertNotDownloadable(String path) throws Exception {
        HttpResponse<Void> response = get(path);
        assertTrue(Set.of(400, 403, 404).contains(response.statusCode()),
                path + " returned " + response.statusCode());
        // Tomcat may reject malformed traversal URLs before application
        // filters run. Those generic 400s still prove the file was not served.
        if (response.statusCode() != 400) {
            assertTrue(response.headers().firstValue("Cache-Control").orElse("")
                    .contains("no-store"), path);
        }
    }

    private HttpResponse<Void> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
