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

    // --- authenticated ---
    public static final String CONTESTS = "/api/contests";                 // GET joinable list
    public static final String CONTEST_CURRENT = "/api/contests/current";
    public static final String CONTEST_BY_ID = "/api/contests/{id}";       // GET one contest's state
    public static final String PROBLEMS = "/api/problems";                 // GET list
    public static final String PROBLEM_BY_ID = "/api/problems/{id}";       // GET detail
    public static final String SUBMISSIONS = "/api/submissions";           // POST
    public static final String SUBMISSIONS_MINE = "/api/submissions/mine"; // GET history
    public static final String SUBMISSION_SOURCE = "/api/submissions/{id}/source";
    public static final String LEADERBOARD = "/api/leaderboard";           // GET current contest

    // --- admin: loopback + ADMIN JWT, both enforced (decision D3) ---
    public static final String ADMIN_API_PREFIX = "/api/admin";
    public static final String ADMIN_CONTESTS = "/api/admin/contests";
    public static final String ADMIN_CONTEST_BY_ID = "/api/admin/contests/{id}"; // DELETE
    public static final String ADMIN_CONTEST_START = "/api/admin/contests/{id}/start";
    public static final String ADMIN_CONTEST_END = "/api/admin/contests/{id}/end";
    public static final String ADMIN_CONTEST_PAUSE = "/api/admin/contests/{id}/pause";
    public static final String ADMIN_CONTEST_RESUME = "/api/admin/contests/{id}/resume";
    public static final String ADMIN_CONTEST_EXTEND = "/api/admin/contests/{id}/extend";
    public static final String ADMIN_CONTEST_FREEZE = "/api/admin/contests/{id}/freeze";
    public static final String ADMIN_CONTEST_UNFREEZE = "/api/admin/contests/{id}/unfreeze";
    public static final String ADMIN_PROBLEMS = "/api/admin/problems";              // GET list
    public static final String ADMIN_PROBLEM_UPLOAD = "/api/admin/problems/upload"; // POST multipart
    public static final String ADMIN_PROBLEM_BY_ID = "/api/admin/problems/{id}";    // DELETE
    public static final String ADMIN_PARTICIPANTS = "/api/admin/contests/{id}/participants";
    public static final String ADMIN_CONTEST_SUBMISSIONS = "/api/admin/contests/{id}/submissions";
    public static final String ADMIN_SUBMISSION_SOURCE = "/api/admin/submissions/{id}/source";

    // --- static admin panel, loopback only ---
    public static final String ADMIN_PANEL_PREFIX = "/admin";
}
