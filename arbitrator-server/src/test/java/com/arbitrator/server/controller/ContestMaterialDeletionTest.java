/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.arbitrator.server.repo.MaterialRepository;
import com.arbitrator.server.service.MaterialService;

/** Real MySQL FK/rollback tests, isolated to arbitrator_test and temporary files. */
@DataJpaTest(properties = "spring.flyway.clean-disabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("mysqlIsReachable")
class ContestMaterialDeletionTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MaterialRepository materials;
    @Autowired private PlatformTransactionManager transactionManager;
    @TempDir Path root;

    private final List<Long> contestIds = new ArrayList<>();
    private TransactionTemplate transaction;
    private MaterialService service;
    private AdminContestController controller;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        service = new MaterialService(materials, null, null, root.toString());
        controller = new AdminContestController(null, null, null, null, null,
                jdbc, null, null, service);
    }

    @AfterEach
    void cleanRows() {
        for (long id : contestIds) {
            jdbc.update("DELETE FROM materials WHERE contest_id = ?", id);
            jdbc.update("DELETE FROM contests WHERE id = ?", id);
        }
    }

    @Test
    void commitDeletesOnlyTargetMaterialsAndWaitsUntilCommitToRemoveFiles() throws Exception {
        long target = contest();
        long other = contest();
        Path first = material(target, "first.txt");
        Path second = material(target, "second.txt");
        Path unrelated = material(other, "unrelated.txt");

        transaction.executeWithoutResult(status -> {
            assertEquals(204, controller.delete(target).getStatusCode().value());
            assertEquals(0, materialCount(target));
            assertEquals(0, contestCount(target));
            assertTrue(Files.exists(first), "Files must survive until commit");
            assertTrue(Files.exists(second));
        });

        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        assertEquals(1, contestCount(other));
        assertEquals(1, materialCount(other));
        assertEquals("test material", Files.readString(unrelated));
    }

    @Test
    void laterDatabaseFailureRollsBackDeletionAndPreservesDownloads() throws Exception {
        long target = contest();
        Path file = material(target, "rollback.txt");

        assertThrows(DataIntegrityViolationException.class, () -> transaction.executeWithoutResult(status -> {
            controller.delete(target);
            assertEquals(0, contestCount(target));
            assertEquals(0, materialCount(target));
            assertTrue(Files.exists(file));
            // Force a real FK failure AFTER all contest deletion SQL ran.
            jdbc.update("INSERT INTO materials (contest_id, filename, stored_name, content_type, size_bytes) "
                    + "VALUES (?, 'invalid.txt', 'invalid.txt', 'text/plain', 1)", target);
        }));

        assertEquals(1, contestCount(target));
        assertEquals(1, materialCount(target));
        assertEquals("test material", Files.readString(file));
    }

    @Test
    void alreadyMissingFileDoesNotBlockDeletion() throws Exception {
        long target = contest();
        Path missing = material(target, "missing.txt");
        Files.delete(missing);
        transaction.executeWithoutResult(status -> controller.delete(target));
        assertEquals(0, contestCount(target));
        assertEquals(0, materialCount(target));
        assertFalse(Files.exists(missing));
    }

    @Test
    void cleanupFailureDoesNotUndoCommitOrPreventOtherFileCleanup() throws Exception {
        long target = contest();
        Path blocked = material(target, "blocked.txt");
        Files.delete(blocked);
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("child"), "force DirectoryNotEmptyException");
        Path normal = material(target, "normal.txt");

        transaction.executeWithoutResult(status -> controller.delete(target));

        assertEquals(0, contestCount(target));
        assertEquals(0, materialCount(target));
        assertTrue(Files.exists(blocked), "Unremovable path remains for manual cleanup");
        assertFalse(Files.exists(normal));
    }

    @Test
    void contestWithoutMaterialsStillDeletes() {
        long target = contest();
        transaction.executeWithoutResult(status -> controller.delete(target));
        assertEquals(0, contestCount(target));
    }

    @Test
    void missingTransactionCannotDeleteMaterialRowsOrFiles() throws Exception {
        long target = contest();
        Path file = material(target, "no-transaction.txt");
        assertThrows(IllegalStateException.class, () -> service.deleteForContest(target));
        assertEquals(1, materialCount(target));
        assertTrue(Files.exists(file));
    }

    private long contest() {
        String title = "material-deletion-test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO contests (title, state, duration_minutes) VALUES (?, 'DRAFT', 120)", title);
        long id = jdbc.queryForObject("SELECT id FROM contests WHERE title = ?", Long.class, title);
        contestIds.add(id);
        return id;
    }

    private Path material(long contestId, String filename) throws IOException {
        Path file = root.resolve(UUID.randomUUID() + "-" + filename);
        Files.writeString(file, "test material");
        jdbc.update("INSERT INTO materials (contest_id, filename, stored_name, content_type, size_bytes) "
                + "VALUES (?, ?, ?, 'text/plain', ?)", contestId, filename,
                file.getFileName().toString(), Files.size(file));
        return file;
    }

    private int materialCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM materials WHERE contest_id = ?", Integer.class, id);
    }

    private int contestCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM contests WHERE id = ?", Integer.class, id);
    }

    static boolean mysqlIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 3306), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
