package com.arbitrator.server.monitor;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local-only probes on the existing port; LoopbackAdminFilter guards /admin/**. */
@RestController
public class HealthController {
    private final HealthChecks checks;

    public HealthController(HealthChecks checks) {
        this.checks = checks;
    }

    @GetMapping("/admin/health/live")
    public ResponseEntity<Map<String, String>> live() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status", "UP"));
    }

    @GetMapping("/admin/health/ready")
    public ResponseEntity<HealthChecks.Readiness> ready() {
        HealthChecks.Readiness result = checks.readiness();
        return ResponseEntity.status("READY".equals(result.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore()).body(result);
    }
}
