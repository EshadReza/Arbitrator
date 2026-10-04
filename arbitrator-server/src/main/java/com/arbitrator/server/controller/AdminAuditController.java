package com.arbitrator.server.controller;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.arbitrator.server.security.AuditService;

/** Existing ADMIN and loopback guards apply. Cursor pagination avoids unbounded reads. */
@RestController
public class AdminAuditController {
    private final AuditService audit;
    public AdminAuditController(AuditService audit) { this.audit = audit; }
    @GetMapping("/api/admin/audit")
    public List<AuditService.Event> history(@RequestParam(defaultValue = "9223372036854775807") long before,
                                           @RequestParam(defaultValue = "50") int limit) {
        if (before < 1 || limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audit page");
        return audit.page(before, limit);
    }
}
