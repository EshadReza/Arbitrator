/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.api;

/**
 * Every REST path in the system, as compile-time constants.
 * A typo here is a compile error, not a runtime 404.
 * FROZEN — additions allowed, renames need a contract-change issue.
 */
public final class ApiPaths {

    private ApiPaths() {
    }

    // --- auth (permitAll) ---
    public static final String AUTH_REGISTER = "/api/auth/register";
    public static final String AUTH_LOGIN = "/api/auth/login";
    /** Explicit sign-out: releases the single-session slot and raises
        DISCONNECTED immediately instead of waiting out the presence grace
        period, since a deliberate Sign Out isn't a network blip. */
    public static final String AUTH_LOGOUT = "/api/auth/logout";

    // --- authenticated ---
    public static final String CONTESTS = "/api/contests";                 // GET joinable list
    public static final String CONTEST_CURRENT = "/api/contests/current";
    public static final String CONTEST_BY_ID = "/api/contests/{id}";       // GET one contest's state
    /** Verifies the contest password and records server-side access for this student. */
    public static final String CONTEST_JOIN = "/api/contests/{id}/join";  // POST
    public static final String PROBLEMS = "/api/problems";                 // GET list
    public static final String PROBLEM_BY_ID = "/api/problems/{id}";       // GET detail
    /** Raw PDF bytes, when the problem's statement is a PDF (FR-05). */
    public static final String PROBLEM_STATEMENT_PDF = "/api/problems/{id}/statement.pdf";
    /** FR-07: announcements for the contest the caller is in. */
    public static final String ANNOUNCEMENTS = "/api/announcements";
    /** FR-07 sibling: downloadable materials for the contest the caller is in. */
    public static final String MATERIALS = "/api/materials";
    /** Raw bytes of one material, streamed with its original filename. */
    public static final String MATERIAL_DOWNLOAD = "/api/materials/{id}/download";
    /** Public clarification board: GET to read it, POST to ask. */
    public static final String CLARIFICATIONS = "/api/clarifications";
    public static final String SUBMISSIONS = "/api/submissions";           // POST
    public static final String SUBMISSIONS_MINE = "/api/submissions/mine"; // GET history
    public static final String RUN_CUSTOM = "/api/run";                    // POST, not judged
    public static final String SUBMISSION_SOURCE = "/api/submissions/{id}/source";
    /** Test data behind my own verdict — only if the instructor allowed it. */
    public static final String SUBMISSION_TESTS = "/api/submissions/{id}/tests";
    public static final String LEADERBOARD = "/api/leaderboard";           // GET current contest
    /**
     * Standings-box drill-down: one participant's attempts on one problem,
     * newest first. Public to any contestant (not owner-gated like
     * SUBMISSION_SOURCE) — see AttemptSummaryDto for why that's still safe.
     */
    public static final String CONTEST_PARTICIPANT_PROBLEM_ATTEMPTS =
            "/api/contests/{id}/participants/{username}/problems/{code}/attempts";

    // --- admin: loopback + ADMIN JWT, both enforced (decision D3) ---
    public static final String ADMIN_API_PREFIX = "/api/admin";
    public static final String ADMIN_CONTESTS = "/api/admin/contests";
    public static final String ADMIN_CONTEST_BY_ID = "/api/admin/contests/{id}"; // DELETE
    /** A fresh DRAFT contest with a copy of {id}'s problems; POST body is the new title. */
    public static final String ADMIN_CONTEST_CLONE = "/api/admin/contests/{id}/clone";
    public static final String ADMIN_CONTEST_OPEN = "/api/admin/contests/{id}/open";
    public static final String ADMIN_CONTEST_START = "/api/admin/contests/{id}/start";
    public static final String ADMIN_CONTEST_END = "/api/admin/contests/{id}/end";
    public static final String ADMIN_CONTEST_PAUSE = "/api/admin/contests/{id}/pause";
    public static final String ADMIN_CONTEST_RESUME = "/api/admin/contests/{id}/resume";
    public static final String ADMIN_CONTEST_EXTEND = "/api/admin/contests/{id}/extend";
    public static final String ADMIN_CONTEST_FREEZE = "/api/admin/contests/{id}/freeze";
    public static final String ADMIN_CONTEST_UNFREEZE = "/api/admin/contests/{id}/unfreeze";
    public static final String ADMIN_PROBLEMS = "/api/admin/problems";              // GET list
    public static final String ADMIN_PROBLEM_UPLOAD = "/api/admin/problems/upload"; // POST multipart
    /** GET the full problem for editing, PUT the edits back, DELETE to remove. */
    public static final String ADMIN_PROBLEM_BY_ID = "/api/admin/problems/{id}";
    /** Re-upload just the PDF statement, replacing whatever was there before. */
    public static final String ADMIN_PROBLEM_STATEMENT_PDF = "/api/admin/problems/{id}/statement.pdf";
    /** Resequences a contest's problems; codes are recomputed A, B, C... to match. */
    public static final String ADMIN_PROBLEM_REORDER = "/api/admin/contests/{id}/problems/reorder";
    /** Read-only: statement + every stored test case, for the console's "View" action. */
    public static final String ADMIN_PROBLEM_TESTCASES = "/api/admin/problems/{id}/testcases";
    public static final String ADMIN_PARTICIPANTS = "/api/admin/contests/{id}/participants";
    public static final String ADMIN_CONTEST_SUBMISSIONS = "/api/admin/contests/{id}/submissions";
    public static final String ADMIN_SUBMISSION_SOURCE = "/api/admin/submissions/{id}/source";
    public static final String ADMIN_STANDINGS = "/api/admin/contests/{id}/standings";
    public static final String ADMIN_SET_MARKS = "/api/admin/submissions/{id}/marks";
    public static final String ADMIN_PARTICIPANT_MARKS = "/api/admin/contests/{id}/participants/{username}/marks";
    public static final String ADMIN_PARTICIPANT_CODES = "/api/admin/contests/{id}/participants/{username}/codes";
    public static final String ADMIN_GROUPED_SUBMISSIONS = "/api/admin/contests/{id}/submissions/grouped";
    public static final String ADMIN_OVERRIDE_VERDICT = "/api/admin/submissions/{id}/verdict";
    public static final String ADMIN_ADJUST_PENALTY = "/api/admin/contests/{id}/participants/{username}/penalty";
    public static final String ADMIN_CONTEST_SCHEDULE = "/api/admin/contests/{id}/schedule";
    /** Toggle whether contestants may see the tests behind their verdicts. */
    public static final String ADMIN_CONTEST_TEST_VISIBILITY =
            "/api/admin/contests/{id}/test-visibility";
    /** Toggle whether an asker may mark a clarification private in this contest. */
    public static final String ADMIN_CONTEST_CLARIFICATION_PRIVACY =
            "/api/admin/contests/{id}/clarification-privacy";
    /** Same toggle, read-only, reachable by a contestant (not loopback/admin-gated). */
    public static final String CONTEST_CLARIFICATION_PRIVACY =
            "/api/contests/{id}/clarification-privacy";
    public static final String ADMIN_SUBMISSION_TESTS = "/api/admin/submissions/{id}/tests";
    /** This machine's LAN addresses — what the instructor reads out to the room. */
    public static final String ADMIN_SERVER_INFO = "/api/admin/server";
    /** FR-07: compose and list announcements for a contest. */
    public static final String ADMIN_ANNOUNCEMENTS = "/api/admin/contests/{id}/announcements";
    public static final String ADMIN_ANNOUNCEMENT_BY_ID = "/api/admin/announcements/{id}";
    /** FR-07 sibling: upload (POST, multipart) and list materials for a contest. */
    public static final String ADMIN_MATERIALS = "/api/admin/contests/{id}/materials";
    public static final String ADMIN_MATERIAL_BY_ID = "/api/admin/materials/{id}";
    /** The clarification queue, with the asker's name attached. */
    public static final String ADMIN_CLARIFICATIONS = "/api/admin/contests/{id}/clarifications";
    public static final String ADMIN_CLARIFICATION_ANSWER = "/api/admin/clarifications/{id}/answer";
    /** Publish (or unpublish) a public, answered clarification to the whole class. */
    public static final String ADMIN_CLARIFICATION_APPROVE = "/api/admin/clarifications/{id}/approve";
    /** Escalating "offline for N minutes" alerts, newest first. GET to poll, DELETE one to dismiss it. */
    public static final String ADMIN_NOTIFICATIONS = "/api/admin/notifications";
    public static final String ADMIN_NOTIFICATION_BY_ID = "/api/admin/notifications/{id}";
    /** Dismiss every notification for one user at once — the console groups
        the feed per-user, so clearing a flaky participant's whole run of
        disconnect/reconnect entries is one action, not one per entry. */
    public static final String ADMIN_NOTIFICATIONS_BY_USER = "/api/admin/notifications/by-user/{username}";

    // --- static admin panel, loopback only ---
    public static final String ADMIN_PANEL_PREFIX = "/admin";
}
