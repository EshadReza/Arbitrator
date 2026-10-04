/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.MaterialDto;
import com.arbitrator.server.entity.Material;
import com.arbitrator.server.repo.MaterialRepository;

class MaterialFilenameSecurityTest {

    @TempDir Path root;

    @Test
    void uploadUsesPortableDisplayNameAndPureUuidPhysicalName() throws Exception {
        RepositoryState state = new RepositoryState();
        MaterialRepository repository = repository(state);
        MaterialService service = service(repository);
        byte[] bytes = "course material".getBytes(StandardCharsets.UTF_8);

        MaterialDto result = service.upload(7L, new MockMultipartFile(
                "file", "C:\\fakepath\\..\\lesson.txt", "text/plain", bytes));

        assertEquals("lesson.txt", result.filename());
        Material saved = state.saved;
        assertEquals(saved.getStoredName(), UUID.fromString(saved.getStoredName()).toString());
        assertFalse(saved.getStoredName().contains(".txt"));
        assertEquals("course material", Files.readString(root.resolve(saved.getStoredName())));
    }

    @Test
    void materialLimitMatchesTheMultipartCapBeforePersistence() throws Exception {
        RepositoryState state = new RepositoryState();
        MaterialService service = service(repository(state));
        MockMultipartFile oversized = new MockMultipartFile("file", "lecture.pdf",
                "application/pdf", new byte[]{1}) {
            @Override public long getSize() { return 64L * 1024 * 1024 + 1; }
        };

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> service.upload(7L, oversized));

        assertEquals(400, rejected.getStatusCode().value());
        assertTrue(rejected.getReason().contains("64MB"));
        assertNull(state.saved);
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void sanitizesTraversalControlsReservedNamesAndLongNamesPortably() {
        assertEquals("admin.html", MaterialService.sanitizeFilename("../../templates/admin.html"));
        assertEquals("lesson.pdf", MaterialService.sanitizeFilename("C:\\users\\student\\lesson.pdf"));
        assertEquals("_CON", MaterialService.sanitizeFilename("CON"));
        assertEquals("_lpt9.txt", MaterialService.sanitizeFilename("lpt9.txt"));
        assertEquals("material", MaterialService.sanitizeFilename(".."));
        assertEquals("material", MaterialService.sanitizeFilename("   ...   "));

        String hostile = MaterialService.sanitizeFilename("bad\u0000\u202e:<name>?.txt. ");
        assertFalse(hostile.codePoints().anyMatch(Character::isISOControl));
        assertFalse(hostile.contains("\u202e"));
        assertFalse(hostile.matches(".*[<>:\"/\\\\|?*].*"));
        assertFalse(hostile.endsWith("."));
        assertFalse(hostile.endsWith(" "));

        String longName = "x".repeat(300) + ".pdf";
        String sanitized = MaterialService.sanitizeFilename(longName);
        assertEquals(200, sanitized.codePointCount(0, sanitized.length()));
        assertTrue(sanitized.endsWith(".pdf"));
    }

    @Test
    void fileLookupRejectsTraversalAndSymlinkButAllowsLegacySingleComponent() throws Exception {
        MaterialService service = service(repository(new RepositoryState()));
        Path outside = root.resolveSibling(root.getFileName() + "-outside");
        Files.writeString(outside, "outside");
        try {
            ResponseStatusException traversal = assertThrows(ResponseStatusException.class,
                    () -> service.fileOf(material(1L, "../" + outside.getFileName())));
            assertEquals(404, traversal.getStatusCode().value());

            Path legacy = root.resolve(UUID.randomUUID() + ".pdf");
            Files.writeString(legacy, "legacy");
            assertEquals(legacy, service.fileOf(material(2L, legacy.getFileName().toString())));

            Path link = root.resolve("linked-material");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                Assumptions.assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
            }
            ResponseStatusException symlink = assertThrows(ResponseStatusException.class,
                    () -> service.fileOf(material(3L, link.getFileName().toString())));
            assertEquals(404, symlink.getStatusCode().value());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void hostileStoredNameCannotDeleteOutsideFileAndCatalogRowIsStillRemoved() throws Exception {
        RepositoryState state = new RepositoryState();
        MaterialRepository repository = repository(state);
        Material hostile = material(11L, "../outside-material");
        state.found = hostile;
        Path outside = root.resolveSibling("outside-material");
        Files.writeString(outside, "keep me");
        try {
            service(repository).delete(11L);
            assertEquals("keep me", Files.readString(outside));
            assertTrue(state.deleted);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void failedMetadataSaveRemovesNewPhysicalFile() throws IOException {
        RepositoryState state = new RepositoryState();
        state.saveFailure = new IllegalStateException("database rejected row");
        MaterialRepository repository = repository(state);
        MaterialService service = service(repository);

        assertThrows(IllegalStateException.class, () -> service.upload(7L,
                new MockMultipartFile("file", "lesson.txt", "text/plain", "bytes".getBytes(StandardCharsets.UTF_8))));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }

    private MaterialService service(MaterialRepository repository) {
        ContestService contests = new ContestService(null, null) {
            @Override
            public com.arbitrator.server.entity.Contest require(long id) {
                return new com.arbitrator.server.entity.Contest();
            }
        };
        SimpMessagingTemplate template = new SimpMessagingTemplate(new ExecutorSubscribableChannel());
        return new MaterialService(repository, contests, template, root.toString());
    }

    private static MaterialRepository repository(RepositoryState state) {
        return (MaterialRepository) Proxy.newProxyInstance(
                MaterialRepository.class.getClassLoader(),
                new Class<?>[]{MaterialRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        if (state.saveFailure != null) {
                            throw state.saveFailure;
                        }
                        state.saved = (Material) args[0];
                        if (state.saved.getId() == null) {
                            setId(state.saved, 41L);
                        }
                        yield state.saved;
                    }
                    case "findById" -> Optional.ofNullable(state.found);
                    case "delete" -> {
                        state.deleted = true;
                        yield null;
                    }
                    case "findByContestIdOrderByUploadedAtDesc", "findAll" -> java.util.List.of();
                    case "toString" -> "MaterialRepository test fake";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class RepositoryState {
        private Material saved;
        private Material found;
        private boolean deleted;
        private RuntimeException saveFailure;
    }

    private static Material material(long id, String storedName) throws Exception {
        Material material = new Material();
        setId(material, id);
        material.setContestId(7L);
        material.setFilename("lesson.txt");
        material.setStoredName(storedName);
        material.setContentType("text/plain");
        material.setSizeBytes(1);
        material.setUploadedAt(Instant.now());
        return material;
    }

    private static void setId(Material material, long id) throws Exception {
        Field field = Material.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(material, id);
    }
}
