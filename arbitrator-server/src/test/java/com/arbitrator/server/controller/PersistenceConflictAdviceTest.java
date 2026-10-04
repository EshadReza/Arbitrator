/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.config.SecurityHeadersFilter;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class PersistenceConflictAdviceTest {
    final PersistenceConflictAdvice advice = new PersistenceConflictAdvice();
    final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");

    @Test void onlyRecognizedDuplicateConstraintsReturn409() {
        for (String key : new String[]{"users.uk_users_username", "uk_contests_title"}) {
            var result = advice.integrity(new DataIntegrityViolationException("wrapped",
                    new SQLException("Duplicate entry 'private-value' for key '" + key + "'", "23000", 1062)), request);
            assertEquals(409, result.getStatusCode().value());
            assertFalse(result.getBody().toString().contains("private-value"));
            assertEquals("no-store", result.getHeaders().getFirst("Cache-Control"));
        }
    }

    @Test void unrelatedIntegrityFailuresRemain500WithoutDatabaseDetails() {
        for (SQLException failure : new SQLException[]{
                new SQLException("SECRET foreign key uk_users_username", "23000", 1452),
                new SQLException("Duplicate entry SECRET for key 'other_unique_key'", "23000", 1062)}) {
            var result = advice.integrity(new DataIntegrityViolationException("wrapped", failure), request);
            assertEquals(500, result.getStatusCode().value());
            assertFalse(result.getBody().toString().contains("SECRET"));
        }
    }

    @Test void staleEditsReturnReloadConflict() {
        var result = advice.staleWrite(new ObjectOptimisticLockingFailureException(Problem.class, 1L), request);
        assertEquals(409, result.getStatusCode().value());
        assertTrue(result.getBody().get("message").toString().contains("Reload"));
    }

    @Test void unexpectedDatabaseFailureDoesNotLogDriverValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(PersistenceConflictAdvice.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var result = advice.integrity(new DataIntegrityViolationException("PRIVATE_WRAPPER_SENTINEL",
                    new SQLException("PRIVATE_SQL_SENTINEL", "23000", 1452)), request);
            assertEquals(500, result.getStatusCode().value());
            assertEquals(SecurityHeadersFilter.requestId(request), result.getBody().get("requestId"));
            assertFalse(result.getBody().containsKey("path"));
            assertEquals(1, appender.list.size());
            assertFalse(appender.list.get(0).getFormattedMessage().contains("PRIVATE_"));
            assertTrue(appender.list.get(0).getThrowableProxy() == null);
        } finally {
            logger.detachAppender(appender); appender.stop();
        }
    }
}
