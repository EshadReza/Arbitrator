/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * Server address configured at install time or via the login screen.
 * Persists the user's last connected address using Java Preferences.
 */
public class ServerConfig {

    private static final String PREF_HOST_PORT = "arbitrator.server.address";

    private String scheme = "http";
    private String host = "127.0.0.1";
    private int port = 8080;

    private ServerConfig(Properties p) {
        this.scheme = p.getProperty("server.scheme", "http").trim();
        String defaultHp = p.getProperty("server.host", "127.0.0.1").trim() + ":" + p.getProperty("server.port", "8080").trim();
        String saved = Preferences.userNodeForPackage(ServerConfig.class).get(PREF_HOST_PORT, defaultHp);
        parseHostPort(saved);
    }

    private void parseHostPort(String hp) {
        if (hp == null || hp.isBlank()) {
            return;
        }
        hp = hp.trim();
        if (hp.startsWith("http://")) {
            this.scheme = "http";
            hp = hp.substring(7);
        } else if (hp.startsWith("https://")) {
            this.scheme = "https";
            hp = hp.substring(8);
        }
        int colon = hp.indexOf(':');
        if (colon > 0) {
            this.host = hp.substring(0, colon).trim();
            try {
                this.port = Integer.parseInt(hp.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                this.port = 8080;
            }
        } else {
            this.host = hp.trim();
            this.port = 8080;
        }
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
            // fall through to defaults
        }
        return new ServerConfig(p);
    }

    public void updateHostPort(String newHostPort) {
        parseHostPort(newHostPort);
        Preferences.userNodeForPackage(ServerConfig.class).put(PREF_HOST_PORT, hostPort());
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
