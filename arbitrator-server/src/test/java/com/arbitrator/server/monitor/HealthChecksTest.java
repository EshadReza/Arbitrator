package com.arbitrator.server.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.arbitrator.server.judge.JudgeQueue;
import com.arbitrator.server.judge.SandboxExecutor;

class HealthChecksTest {
    private HealthChecks checks;

    @AfterEach void cleanup() {
        if (checks != null) checks.shutdown();
    }

    @Test
    void componentFailuresAndRecoveryAreReportedSeparately() throws Exception {
        DataSource database = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        JudgeQueue queue = mock(JudgeQueue.class);
        SandboxExecutor judge = mock(SandboxExecutor.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);
        when(queue.canAcceptWork()).thenReturn(true);
        when(queue.workersAvailable()).thenReturn(true);
        when(judge.isRuntimeReady()).thenReturn(true);
        checks = new HealthChecks(database, queue, judge);

        assertEquals(new HealthChecks.Readiness("READY", "UP", "UP", "UP", "UP"), checks.readiness());

        when(connection.isValid(1)).thenReturn(false);
        assertEquals(new HealthChecks.Readiness("NOT_READY", "UP", "DOWN", "UP", "UP"),
                checks.readiness());

        when(connection.isValid(1)).thenReturn(true);
        when(queue.canAcceptWork()).thenReturn(false);
        assertEquals(new HealthChecks.Readiness("NOT_READY", "UP", "UP", "DOWN", "UP"),
                checks.readiness());

        when(queue.canAcceptWork()).thenReturn(true);
        when(queue.workersAvailable()).thenReturn(false);
        assertEquals(new HealthChecks.Readiness("NOT_READY", "UP", "UP", "UP", "DOWN"),
                checks.readiness());

        when(queue.workersAvailable()).thenReturn(true);
        when(judge.isRuntimeReady()).thenReturn(false);
        assertEquals(new HealthChecks.Readiness("NOT_READY", "UP", "UP", "UP", "DOWN"),
                checks.readiness());

        when(judge.isRuntimeReady()).thenReturn(true);
        assertEquals("READY", checks.readiness().status());
    }

    @Test
    void databaseFailureAndAcquisitionTimeoutDoNotHangProbe() throws Exception {
        DataSource database = mock(DataSource.class);
        JudgeQueue queue = mock(JudgeQueue.class);
        SandboxExecutor judge = mock(SandboxExecutor.class);
        when(queue.canAcceptWork()).thenReturn(true);
        when(queue.workersAvailable()).thenReturn(true);
        when(judge.isRuntimeReady()).thenReturn(true);
        when(database.getConnection()).thenThrow(new SQLException("unavailable"));
        checks = new HealthChecks(database, queue, judge, 50);
        assertEquals("DOWN", checks.readiness().database());
        checks.shutdown();

        DataSource slowDatabase = mock(DataSource.class);
        when(slowDatabase.getConnection()).thenAnswer(ignored -> {
            Thread.sleep(5_000);
            return mock(Connection.class);
        });
        checks = new HealthChecks(slowDatabase, queue, judge, 50);
        long start = System.nanoTime();
        assertEquals("DOWN", checks.readiness().database());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000, "database probe must time out");
    }

    @Test
    void controllerReturns503ForUnreadyComponentsWithoutDetails() {
        HealthChecks service = mock(HealthChecks.class);
        HealthController controller = new HealthController(service);
        when(service.readiness()).thenReturn(new HealthChecks.Readiness(
                "NOT_READY", "UP", "DOWN", "UP", "DOWN"));
        var live = controller.live();
        var ready = controller.ready();
        assertEquals(200, live.getStatusCode().value());
        assertEquals(503, ready.getStatusCode().value());
        assertFalse(live.getBody().containsKey("database"));
        assertEquals("no-store", ready.getHeaders().getCacheControl());
    }
}
