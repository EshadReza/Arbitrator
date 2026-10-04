/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.MaterialDto;
import com.arbitrator.server.entity.Material;
import com.arbitrator.server.repo.MaterialRepository;

/**
 * FR-07 sibling: downloadable instructor materials — slides, PDFs, any file.
 *
 * Owner: Mahir (AGENTS.md Rule 1 — service/material, alongside announcement).
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

    /** Match the effective multipart file/request cap in application.yml. */
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_DISPLAY_FILENAME_CODEPOINTS = 200;
    private static final int MAX_PRESERVED_EXTENSION_CODEPOINTS = 20;

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
        // The uploader's name is display-only. Keeping even its extension out
        // of the physical name makes the filesystem boundary independent of
        // attacker-controlled length, characters and platform conventions.
        String storedName = UUID.randomUUID().toString();
        Path target = storedPath(storedName);
        try {
            Files.copy(file.getInputStream(), target);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException | SecurityException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
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
        try {
            materials.save(m);
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException | SecurityException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
                log.warn("Material metadata failed and its new file {} needs cleanup ({})",
                        safeLogStoredName(storedName), cleanupFailure.getClass().getSimpleName());
            }
            throw e;
        }

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
        final Path p;
        try {
            p = storedPath(m.getStoredName());
        } catch (IllegalArgumentException e) {
            log.warn("Rejected unsafe stored material name for id {}", m.getId());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The file is missing on disk — re-upload it");
        }
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The file is missing on disk — re-upload it");
        }
        return p;
    }

    public void delete(long id) {
        Material m = require(id);
        try {
            Files.deleteIfExists(storedPath(m.getStoredName()));
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            // The catalog row is still removed below — a stray orphan file on
            // disk is a cheap failure mode, a delete the instructor can't get
            // rid of is not.
            log.warn("Could not delete material file {} for id {} ({})",
                    safeLogStoredName(m.getStoredName()), id, e.getClass().getSimpleName());
        }
        materials.delete(m);
        broadcast(m.getContestId());
    }

    /**
     * Join contest deletion: remove the FK rows before its JDBC DELETE, but
     * retain the files until the whole database transaction commits. Deleting
     * files first would leave broken downloads if any later SQL rolled back.
     * File cleanup is best-effort after commit; failures are logged for manual
     * cleanup, since the committed database deletion can no longer roll back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForContest(long contestId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Contest material deletion requires transaction synchronization");
        }
        List<String> storedNames = materials.findByContestIdOrderByUploadedAtDesc(contestId)
                .stream().map(Material::getStoredName).toList();
        materials.deleteForContest(contestId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String storedName : storedNames) {
                    try {
                        Files.deleteIfExists(storedPath(storedName));
                    } catch (IOException | SecurityException | IllegalArgumentException e) {
                        log.warn("Contest {} was deleted, but material file {} needs cleanup ({})",
                                contestId, safeLogStoredName(storedName), e.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    /** Tells the contest its material list moved — client re-reads it (FR-07 sibling). */
    private void broadcast(long contestId) {
        try {
            template.convertAndSend(StompDestinations.contestMaterials(contestId),
                    Map.of("contestId", contestId, "changedAtMs", System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("Material broadcast failed for contest {} ({})", contestId, e.getClass().getSimpleName());
        }
    }

    /**
     * Strips any directory component a browser might send (Chrome on some
     * platforms still includes one) and characters that would break a
     * Content-Disposition header — the sanitized name is what both the
     * catalog and the download response show, not what ends up on disk
     * (that's {@code storedName}, a UUID).
     */
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "material";
        }

        // Treat both separator styles as separators regardless of the host OS.
        // Do this as string processing so malformed path syntax (including NUL)
        // can be cleaned rather than throwing before validation.
        String portable = name.replace('\\', '/');
        int slash = portable.lastIndexOf('/');
        String base = slash >= 0 ? portable.substring(slash + 1) : portable;
        base = Normalizer.normalize(base, Normalizer.Form.NFC);

        StringBuilder safe = new StringBuilder(base.length());
        base.codePoints().forEach(cp -> safe.appendCodePoint(isUnsafeDisplayCodePoint(cp) ? '_' : cp));
        base = stripUnsafeEdges(safe.toString());
        if (base.isBlank() || base.equals(".") || base.equals("..")) {
            base = "material";
        }
        if (isWindowsDeviceName(base)) {
            base = "_" + base;
        }

        base = truncatePreservingExtension(base, MAX_DISPLAY_FILENAME_CODEPOINTS);
        base = stripUnsafeEdges(base);
        return base.isBlank() || base.equals(".") || base.equals("..") ? "material" : base;
    }

    private static boolean isUnsafeDisplayCodePoint(int cp) {
        int type = Character.getType(cp);
        return Character.isISOControl(cp)
                || type == Character.FORMAT
                || cp == '<' || cp == '>' || cp == ':' || cp == '"'
                || cp == '/' || cp == '\\' || cp == '|' || cp == '?' || cp == '*';
    }

    private static String stripUnsafeEdges(String value) {
        String stripped = value.strip();
        int end = stripped.length();
        while (end > 0) {
            char last = stripped.charAt(end - 1);
            if (last != '.' && last != ' ') {
                break;
            }
            end--;
        }
        return stripped.substring(0, end);
    }

    private static boolean isWindowsDeviceName(String filename) {
        int dot = filename.indexOf('.');
        String stem = (dot < 0 ? filename : filename.substring(0, dot)).toUpperCase(java.util.Locale.ROOT);
        if (stem.equals("CON") || stem.equals("PRN") || stem.equals("AUX") || stem.equals("NUL")) {
            return true;
        }
        return stem.matches("(?:COM|LPT)[1-9]");
    }

    private static String truncatePreservingExtension(String filename, int maxCodePoints) {
        int count = filename.codePointCount(0, filename.length());
        if (count <= maxCodePoints) {
            return filename;
        }
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            String extension = filename.substring(dot);
            int extensionLength = extension.codePointCount(0, extension.length());
            if (extensionLength <= MAX_PRESERVED_EXTENSION_CODEPOINTS) {
                return firstCodePoints(filename.substring(0, dot), maxCodePoints - extensionLength)
                        + extension;
            }
        }
        return firstCodePoints(filename, maxCodePoints);
    }

    private static String firstCodePoints(String value, int count) {
        return value.substring(0, value.offsetByCodePoints(0, count));
    }

    /**
     * Resolve a physical name only when it is exactly one relative component.
     * Existing UUID-with-extension rows remain valid, but traversal, absolute
     * paths and cross-platform separators never reach filesystem operations.
     */
    private Path storedPath(String storedName) {
        if (storedName == null || storedName.isBlank()
                || storedName.indexOf('/') >= 0 || storedName.indexOf('\\') >= 0
                || storedName.equals(".") || storedName.equals("..")) {
            throw new IllegalArgumentException("Unsafe stored material name");
        }
        final Path relative;
        try {
            relative = Path.of(storedName);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Unsafe stored material name", e);
        }
        if (relative.isAbsolute() || relative.getNameCount() != 1) {
            throw new IllegalArgumentException("Unsafe stored material name");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || !root.equals(resolved.getParent())) {
            throw new IllegalArgumentException("Stored material path escapes its root");
        }
        return resolved;
    }

    /** Only generated opaque file IDs are safe to put in an operational warning. */
    private static String safeLogStoredName(String storedName) {
        return storedName != null && storedName.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?:\\.[A-Za-z0-9]{1,16})?")
                ? storedName : "[invalid stored name]";
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
