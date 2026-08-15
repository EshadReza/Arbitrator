package com.arbitrator.server.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.MaterialDto;
import com.arbitrator.server.entity.Material;
import com.arbitrator.server.repo.MaterialRepository;

/**
 * FR-07 sibling: downloadable instructor materials — slides, PDFs, any file.
 *
 * Owner: Mahir (rules.md Rule 1 — service/material, alongside announcement).
 *
 * Bytes live on disk under {@code arbitrator.materials.root}, not as a DB
 * blob like the couple of problem-statement PDFs (V59) — materials are
 * expected to be larger and more numerous, and keeping them out of
 * {@code mysqldump} keeps backups/restores fast (per-team decision, see the
 * "materials" feature discussion). The directory is created on startup, same
 * as the judge's work root (SandboxExecutor) — nobody deploying this needs to
 * remember to `mkdir` it first.
 */
@Service
public class MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);

    /** Generous enough for lecture slides/video without being unbounded. */
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;

    private final MaterialRepository materials;
    private final ContestService contestService;
    private final SimpMessagingTemplate template;
    private final Path root;

    public MaterialService(MaterialRepository materials,
                           ContestService contestService,
                           SimpMessagingTemplate template,
                           @Value("${arbitrator.materials.root:./arbitrator-data/materials}") String root) {
        this.materials = materials;
        this.contestService = contestService;
        this.template = template;
        this.root = Path.of(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create materials directory: " + this.root, e);
        }
    }

    public MaterialDto upload(long contestId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a file first");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Materials are limited to " + (MAX_FILE_BYTES / (1024 * 1024)) + "MB");
        }
        contestService.require(contestId);   // 404 rather than an orphan file on disk

        String original = sanitizeFilename(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + extensionOf(original);
        Path target = root.resolve(storedName);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save the file", e);
        }

        Material m = new Material();
        m.setContestId(contestId);
        m.setFilename(original);
        m.setStoredName(storedName);
        m.setContentType(contentTypeOr(file.getContentType()));
        m.setSizeBytes(file.getSize());
        m.setUploadedAt(Instant.now());
        materials.save(m);

        broadcast(contestId);
        return toDto(m);
    }

    public List<MaterialDto> forContest(long contestId) {
        return materials.findByContestIdOrderByUploadedAtDesc(contestId).stream()
                .map(MaterialService::toDto)
                .toList();
    }

    public Material require(long id) {
        return materials.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such material"));
    }

    /** The file behind a catalog row — resolved and existence-checked. */
    public Path fileOf(Material m) {
        Path p = root.resolve(m.getStoredName());
        if (!Files.exists(p)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The file is missing on disk — re-upload it");
        }
        return p;
    }

    public void delete(long id) {
        Material m = require(id);
        try {
            Files.deleteIfExists(root.resolve(m.getStoredName()));
        } catch (IOException e) {
            // The catalog row is still removed below — a stray orphan file on
            // disk is a cheap failure mode, a delete the instructor can't get
            // rid of is not.
            log.warn("Could not delete material file {} for id {}", m.getStoredName(), id, e);
        }
        materials.delete(m);
        broadcast(m.getContestId());
    }

    /** Tells the contest its material list moved — client re-reads it (FR-07 sibling). */
    private void broadcast(long contestId) {
        try {
            template.convertAndSend(StompDestinations.contestMaterials(contestId),
                    Map.of("contestId", contestId, "changedAtMs", System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("Material broadcast failed for contest {}", contestId, e);
        }
    }

    /**
     * Strips any directory component a browser might send (Chrome on some
     * platforms still includes one) and characters that would break a
     * Content-Disposition header — the sanitized name is what both the
     * catalog and the download response show, not what ends up on disk
     * (that's {@code storedName}, a UUID).
     */
    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "material";
        }
        String base = Path.of(name).getFileName().toString();
        return base.replaceAll("[\\r\\n\"]", "_");
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private static String contentTypeOr(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        try {
            MediaType.parseMediaType(contentType);
            return contentType;
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private static MaterialDto toDto(Material m) {
        return new MaterialDto(m.getId(), m.getContestId(), m.getFilename(),
                m.getContentType(), m.getSizeBytes(), m.getUploadedAt().toEpochMilli());
    }
}
