/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;

/** Translate only recognized conflicts, never expose database diagnostics. */
@RestControllerAdvice
public class PersistenceConflictAdvice {
    private static final Logger log = LoggerFactory.getLogger(PersistenceConflictAdvice.class);
    private static final Pattern UNIQUE_KEY = Pattern.compile(
            "(?i)for key ['`\"](?:[^'`\"]*\\.)?(uk_users_username|uk_contests_title)['`\"]");

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException failure,
                                                         HttpServletRequest request) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062
                    && "23000".equals(sql.getSQLState())) {
                Matcher key = UNIQUE_KEY.matcher(sql.getMessage() == null ? "" : sql.getMessage());
                if (key.find()) return response(409, key.group(1).equalsIgnoreCase("uk_users_username")
                        ? "Username already taken" : "Contest title already exists", request);
            }
        }
        log.error("Unhandled persistence integrity failure", failure);
        return response(500, "Unable to complete this operation", request);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<Map<String, Object>> staleWrite(Exception failure, HttpServletRequest request) {
        return response(409, "This item changed or was deleted. Reload before trying again.", request);
    }

    private static ResponseEntity<Map<String, Object>> response(int status, String message,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of(
                "timestamp", Instant.now().toString(), "status", status,
                "error", status == 409 ? "Conflict" : "Internal Server Error",
                "message", message, "path", request.getRequestURI()));
    }
}
