package com.arbitrator.server.monitor;

import java.sql.Connection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.arbitrator.server.judge.JudgeQueue;
import com.arbitrator.server.judge.SandboxExecutor;
import jakarta.annotation.PreDestroy;

/** Short, bounded probes. No caller-supplied input and no diagnostic details in responses. */
@Component
public class HealthChecks {
    private static final long DATABASE_TIMEOUT_MS = 2_000;
    private final DataSource dataSource;
    private final JudgeQueue queue;
    private final SandboxExecutor sandbox;
    private final long databaseTimeoutMs;
    private final ThreadPoolExecutor databaseExecutor = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "health-db-probe");
                thread.setDaemon(true);
                return thread;
            });

    @Autowired
    public HealthChecks(DataSource dataSource, JudgeQueue queue, SandboxExecutor sandbox) {
        this(dataSource, queue, sandbox, DATABASE_TIMEOUT_MS);
    }

    HealthChecks(DataSource dataSource, JudgeQueue queue, SandboxExecutor sandbox,
                 long databaseTimeoutMs) {
        this.dataSource = dataSource;
        this.queue = queue;
        this.sandbox = sandbox;
        this.databaseTimeoutMs = databaseTimeoutMs;
    }

    public Readiness readiness() {
        boolean databaseReady = databaseReady();
        boolean queueReady = safeQueueReady();
        boolean judgeReady = safeJudgeReady();
        boolean ready = databaseReady && queueReady && judgeReady;
        return new Readiness(ready ? "READY" : "NOT_READY", "UP",
                state(databaseReady), state(queueReady), state(judgeReady));
    }

    private boolean databaseReady() {
        Future<Boolean> task;
        try {
            task = databaseExecutor.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    return connection.isValid(1);
                } catch (Exception unavailable) {
                    return false;
                }
            });
        } catch (RejectedExecutionException unavailable) {
            return false;
        }
        try {
            return task.get(databaseTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException unavailable) {
            // A connection-acquisition stall must not hold the HTTP probe open indefinitely.
        }
        task.cancel(true);
        return false;
    }

    private boolean safeQueueReady() {
        try { return queue.canAcceptWork(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private boolean safeJudgeReady() {
        try { return queue.workersAvailable() && sandbox.isRuntimeReady(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private static String state(boolean available) { return available ? "UP" : "DOWN"; }

    @PreDestroy
    void shutdown() {
        databaseExecutor.shutdownNow();
    }

    public record Readiness(String status, String web, String database, String queue, String judge) {}
}
