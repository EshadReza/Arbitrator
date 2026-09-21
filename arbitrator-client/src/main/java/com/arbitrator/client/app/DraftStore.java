/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.arbitrator.common.enums.Language;

/**
 * Editor drafts that survive signing out.
 *
 * They used to live only in a map inside MainController, so signing out — or
 * switching contest, which rebuilds the same screen — threw away whatever was
 * in the editor, including code that had already been submitted. Losing a
 * student's work mid-contest is not a state this application may reach.
 *
 * Stored under the user's home directory, one file per user per problem, so two
 * students sharing a lab machine never see each other's code. The file is plain
 * text with a one-line header carrying the language:
 *
 * <pre>
 *   #arbitrator-draft CPP17
 *   ...the source...
 * </pre>
 *
 * Every failure here is swallowed deliberately. A draft is a convenience: a
 * read-only home directory or a full disk must never stop somebody competing.
 */
public final class DraftStore {

    private static final String HEADER = "#arbitrator-draft ";

    private DraftStore() {
    }

    /** A restored draft: what was typed, and which language it was typed for. */
    public record Draft(Language language, String code) {
    }

    public static void save(String username, long contestId, long problemId,
                            Language language, String code) {
        if (username == null || problemId < 0) {
            return;
        }
        try {
            Path file = fileFor(username, contestId, problemId);
            Files.createDirectories(file.getParent());
            String header = HEADER + (language == null ? "" : language.name()) + "\n";
            Files.writeString(file, header + (code == null ? "" : code), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // A draft is a convenience, never a precondition for competing.
        }
    }

    public static Optional<Draft> load(String username, long contestId, long problemId) {
        if (username == null || problemId < 0) {
            return Optional.empty();
        }
        try {
            Path file = fileFor(username, contestId, problemId);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (!body.startsWith(HEADER)) {
                return Optional.of(new Draft(null, body));   // written by an older build
            }
            int newline = body.indexOf('\n');
            String name = body.substring(HEADER.length(), newline < 0 ? body.length() : newline).trim();
            String code = newline < 0 ? "" : body.substring(newline + 1);
            return Optional.of(new Draft(parseLanguage(name), code));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Language parseLanguage(String name) {
        try {
            return name.isEmpty() ? null : Language.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Throws away every draft for one contest.
     *
     * Restarting a contest is a fresh run — the server already archives the
     * previous run's submissions, so leaving last run's code sitting in the
     * editor contradicted that and let work carry across a boundary the
     * standings treat as absolute. Drafts are filed per contest precisely so
     * this can be done without touching any other contest's work.
     */
    public static void clearContest(String username, long contestId) {
        if (username == null) {
            return;
        }
        Path dir = contestDir(username, contestId);
        try (var entries = Files.list(dir)) {
            for (Path p : entries.toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException ignored) {
            // Nothing stored for this contest yet, or unreadable — either way
            // there is nothing to carry over.
        }
    }

    private static Path contestDir(String username, long contestId) {
        return Path.of(System.getProperty("user.home"), ".arbitrator", "drafts",
                sanitize(username), "contest-" + contestId);
    }

    private static Path fileFor(String username, long contestId, long problemId) {
        return contestDir(username, contestId).resolve(problemId + ".txt");
    }

    /** A username reaches the filesystem here, so it may not contain a path. */
    private static String sanitize(String username) {
        String safe = username.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "anonymous" : safe;
    }
}
