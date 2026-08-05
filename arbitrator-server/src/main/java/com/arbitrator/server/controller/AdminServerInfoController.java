package com.arbitrator.server.controller;

import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.server.config.LanAddressResolver;

/**
 * What the instructor has to tell the room: the address students type into
 * their client's server field (§3.3.4, UIF-01).
 *
 * Loopback + ADMIN JWT like every other admin route (decision D3).
 */
@RestController
public class AdminServerInfoController {

    private final LanAddressResolver addresses;
    private final Environment environment;

    public AdminServerInfoController(LanAddressResolver addresses, Environment environment) {
        this.addresses = addresses;
        this.environment = environment;
    }

    /**
     * @param clientAddress exactly what goes in the client's server box —
     *                      "host:port", the same shape ServerConfig parses.
     * @param addresses     every candidate, so the instructor can pick another
     *                      when the guess belongs to the wrong network.
     */
    public record ServerInfoDto(String hostname, int port, String primary,
                                String clientAddress, String adminUrl,
                                List<LanAddressResolver.Address> addresses) {
    }

    @GetMapping(ApiPaths.ADMIN_SERVER_INFO)
    public ServerInfoDto info() {
        int port = port();
        String primary = addresses.primary();
        return new ServerInfoDto(
                addresses.hostname(),
                port,
                primary,
                primary + ":" + port,
                "http://localhost:" + port + ApiPaths.ADMIN_PANEL_PREFIX,
                addresses.all());
    }

    /**
     * local.server.port is the port actually bound, which is what matters when
     * server.port is 0 (the integration tests) or overridden on the command line.
     */
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
