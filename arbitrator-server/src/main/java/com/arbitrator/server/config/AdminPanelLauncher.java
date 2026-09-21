/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.config;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.arbitrator.common.api.ApiPaths;

/**
 * Opens the instructor console in the default browser once the server is up.
 *
 * The console is the only way to run a contest, and it is reachable from the
 * server machine alone (LoopbackAdminFilter, decision D3) — so on the machine
 * that just booted the server, it is always the next thing wanted. Starting it
 * by hand meant remembering both the URL and that the panel exists at all.
 *
 * Three rules this must obey, because it runs on every boot:
 *   1. never delay or fail startup — it runs on a daemon thread after
 *      ApplicationReadyEvent, and every failure is logged, not thrown;
 *   2. always print the URL, so a failed launch costs nothing;
 *   3. stay switchable off (arbitrator.admin.auto-open) for a headless lab
 *      server, where there is no browser and no one sitting at the console.
 *
 * Deliberately not java.awt.Desktop: Spring Boot sets java.awt.headless=true
 * for server applications, so Desktop.browse() throws HeadlessException even on
 * a machine with a perfectly good display. The OS opener is a plain subprocess
 * and does not care.
 */
@Component
public class AdminPanelLauncher {

    private static final Logger log = LoggerFactory.getLogger(AdminPanelLauncher.class);

    private final Environment environment;
    private final LanAddressResolver addresses;
    private final boolean enabled;

    public AdminPanelLauncher(Environment environment,
                              LanAddressResolver addresses,
                              @Value("${arbitrator.admin.auto-open:true}") boolean enabled) {
        this.environment = environment;
        this.addresses = addresses;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openAdminPanel() {
        int port = port();
        // localhost, never the LAN address: the admin surface is loopback-only.
        String adminUrl = "http://localhost:" + port + ApiPaths.ADMIN_PANEL_PREFIX;

        log.info("Instructor console: {}", adminUrl);
        log.info("Students connect to: {}:{}", addresses.primary(), port);

        if (!enabled) {
            return;
        }
        Thread opener = new Thread(() -> open(adminUrl), "admin-panel-opener");
        opener.setDaemon(true);
        opener.start();
    }

    private void open(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (os.contains("mac")) {
            command = List.of("open", url);
        } else if (os.contains("win")) {
            command = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else {
            command = List.of("xdg-open", url);
        }
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // No browser, no DISPLAY, no xdg-open — expected on a headless lab
            // server. The URL is already in the log above; that is enough.
            log.info("Could not open a browser automatically ({}). Open {} yourself, "
                    + "or set arbitrator.admin.auto-open=false to stop trying.",
                    e.getMessage(), url);
        }
    }

    private int port() {
        String bound = environment.getProperty("local.server.port");
        String configured = environment.getProperty("server.port", "8080");
        try {
            return Integer.parseInt(bound != null ? bound : configured);
        } catch (NumberFormatException e) {
            return 8080;
        }
    }
}
