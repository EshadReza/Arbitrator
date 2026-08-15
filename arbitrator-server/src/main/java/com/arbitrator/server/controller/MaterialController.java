package com.arbitrator.server.controller;

import java.nio.file.Path;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.MaterialDto;
import com.arbitrator.server.entity.Material;
import com.arbitrator.server.service.ContestService;
import com.arbitrator.server.service.MaterialService;

/**
 * FR-07 sibling, contestant side: read and download a contest's materials.
 * Owner: Mahir (rules.md Rule 1 — controller/material).
 */
@RestController
public class MaterialController {

    private final MaterialService materials;
    private final ContestService contestService;

    public MaterialController(MaterialService materials, ContestService contestService) {
        this.materials = materials;
        this.contestService = contestService;
    }

    /**
     * @param contestId which contest to read; defaults to the current one so a
     *                  single-contest lab needs no parameter (mirrors
     *                  AnnouncementController.list).
     */
    @GetMapping(ApiPaths.MATERIALS)
    public List<MaterialDto> list(
            @RequestParam(value = "contestId", required = false) Long contestId) {
        long target = contestId != null ? contestId : contestService.requireCurrent().getId();
        return materials.forContest(target);
    }

    @GetMapping(ApiPaths.MATERIAL_DOWNLOAD)
    public ResponseEntity<Resource> download(@PathVariable long id) {
        Material m = materials.require(id);
        Path file = materials.fileOf(m);
        Resource body = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(m.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(m.getFilename()).build().toString())
                .contentLength(m.getSizeBytes())
                .body(body);
    }
}
