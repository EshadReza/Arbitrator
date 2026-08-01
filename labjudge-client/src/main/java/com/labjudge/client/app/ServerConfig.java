package com.labjudge.client.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Server address configured at install time via server.properties (§3.3.4).
 * Lookup order: file next to the launch dir (lab install) -> classpath default
 * (development). The scheme key exists so enabling HTTPS later is a config
 * change, not a refactor (decision D4).
 */
public class ServerConfig {

    private final String scheme;
    private final String host;
    private final int port;

    private ServerConfig(Properties p) {
        this.scheme = p.getProperty("server.scheme", "http").trim();
        this.host = p.getProperty("server.host", "127.0.0.1").trim();
        this.port = Integer.parseInt(p.getProperty("server.port", "8080").trim());
    }

    public static ServerConfig load() {
        Properties p = new Properties();
        Path external = Path.of("server.properties");
        try {
            if (Files.exists(external)) {
                try (var in = Files.newInputStream(external)) {
                    p.load(in);
                }
            } else {
                try (InputStream in = ServerConfig.class
                        .getResourceAsStream("/server.properties")) {
                    if (in != null) {
                        p.load(in);
                    }
                }
            }
        } catch (IOException e) {
            // fall through to defaults — the login status dot will show red
        }
        return new ServerConfig(p);
    }

    public String baseUrl() {
        return scheme + "://" + host + ":" + port;
    }

    public String wsUrl() {
        return (scheme.equals("https") ? "wss" : "ws") + "://" + host + ":" + port + "/ws";
    }

    public String hostPort() {
        return host + ":" + port;
    }
}
