/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.UserRepository;

/**
 * Server-side proof that a student has passed a contest's entry gate.
 *
 * The grant is deliberately stored by user/contest instead of keeping the
 * submitted password in a JWT, URL, client singleton, or database row. Admins
 * already have a stronger credential and therefore bypass student grants.
 */
@Service
public class ContestAccessService {

    private final ContestService contests;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public ContestAccessService(ContestService contests, UserRepository users, JdbcTemplate jdbc) {
        this.contests = contests;
        this.users = users;
        this.jdbc = jdbc;
    }

    @Transactional
    public Contest join(long contestId, String username, String password) {
        Contest contest = contests.require(contestId);
        User user = requireUser(username);
        if (user.getRole() == Role.ADMIN) {
            return contest;
        }
        if (!contest.getState().isJoinable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Contest is " + contest.getState() + " and cannot be entered");
        }
        if (!contests.verifyPassword(contest, password)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Incorrect contest password");
        }
        jdbc.update("""
                INSERT INTO contest_access_grants (contest_id, user_id)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE granted_at = granted_at
                """, contestId, user.getId());
        return contest;
    }

    public Contest requireAccess(long contestId, String username) {
        Contest contest = contests.require(contestId);
        User user = requireUser(username);
        if (user.getRole() == Role.ADMIN) {
            return contest;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contest_access_grants
                WHERE contest_id = ? AND user_id = ?
                """, Integer.class, contestId, user.getId());
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Enter this contest before accessing it");
        }
        return contest;
    }

    public Contest requireReleasedAccess(long contestId, String username) {
        Contest contest = requireAccess(contestId, username);
        User user = requireUser(username);
        if (user.getRole() != Role.ADMIN && !contest.getState().releasesProblems()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Problems have not been released");
        }
        return contest;
    }

    private User requireUser(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}
