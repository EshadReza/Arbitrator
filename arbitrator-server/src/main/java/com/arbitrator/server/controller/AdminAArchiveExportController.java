/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.security.AuditService;
import com.arbitrator.server.service.AArchiveExportService;
import com.arbitrator.server.service.ContestService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * "Export for AArchive" (docs/aarchive-export.md). Loopback + ADMIN JWT like
 * every other /api/admin path (D3).
 *
 * The archive is built into a private temp file first and only then sent:
 * a failure halfway through becomes an ordinary error response instead of a
 * truncated ZIP the browser would save as if it were complete.
 */
@RestController
public class AdminAArchiveExportController {

    private final AArchiveExportService exporter;
    private final ContestService contestService;
    private final AuditService audit;

    public AdminAArchiveExportController(AArchiveExportService exporter,
                                         ContestService contestService,
                                         AuditService audit) {
        this.exporter = exporter;
        this.contestService = contestService;
        this.audit = audit;
    }

    @GetMapping(ApiPaths.ADMIN_CONTEST_AARCHIVE_EXPORT)
    public void export(@PathVariable long id, Principal principal,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        Contest contest = contestService.require(id);   // 404 before any work
        // createTempFile is owner-only on POSIX; the archive holds source code and test data.
        Path tmp = Files.createTempFile("aarchive-" + id + "-", ".zip");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                exporter.export(id, out);
            }
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(fileName(contest), StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(Files.size(tmp));
            Files.copy(tmp, response.getOutputStream());
            // A GET is not otherwise audited, but this one hands out everything
            // the contest holds — worth a line in the security history.
            audit.request(principal == null ? null : principal.getName(),
                    "AdminAArchiveExportController.export:" + id, request.getRemoteAddr(), 200, null);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static String fileName(Contest contest) {
        String slug = contest.getTitle() == null ? "" : contest.getTitle().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "contest-" + contest.getId();
        }
        return "aarchive_" + slug + "_" + LocalDate.now(ZoneOffset.UTC) + ".zip";
    }
}
