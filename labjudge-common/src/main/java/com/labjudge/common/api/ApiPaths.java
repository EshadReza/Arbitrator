package com.labjudge.common.api;

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
    public static final String CONTEST_CURRENT = "/api/contests/current";
    public static final String PROBLEMS = "/api/problems";                 // GET list
    public static final String PROBLEM_BY_ID = "/api/problems/{id}";       // GET detail
    public static final String SUBMISSIONS = "/api/submissions";           // POST
    public static final String SUBMISSIONS_MINE = "/api/submissions/mine"; // GET history
    public static final String LEADERBOARD = "/api/leaderboard";           // GET current contest

    // --- admin: loopback + ADMIN JWT, both enforced (decision D3) ---
    public static final String ADMIN_API_PREFIX = "/api/admin";
    public static final String ADMIN_CONTESTS = "/api/admin/contests";
    public static final String ADMIN_CONTEST_START = "/api/admin/contests/{id}/start";
    public static final String ADMIN_CONTEST_END = "/api/admin/contests/{id}/end";
    public static final String ADMIN_PROBLEMS = "/api/admin/problems";              // GET list
    public static final String ADMIN_PROBLEM_UPLOAD = "/api/admin/problems/upload"; // POST multipart
    public static final String ADMIN_PROBLEM_BY_ID = "/api/admin/problems/{id}";    // DELETE

    // --- static admin panel, loopback only ---
    public static final String ADMIN_PANEL_PREFIX = "/admin";
}
