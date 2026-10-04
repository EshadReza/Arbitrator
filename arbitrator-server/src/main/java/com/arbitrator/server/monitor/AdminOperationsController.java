package com.arbitrator.server.monitor;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.arbitrator.server.judge.JudgeProperties;
import com.arbitrator.server.judge.JudgeQueue;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

/** Read-only, ADMIN + loopback operations snapshot; no secrets or filesystem paths. */
@RestController
public class AdminOperationsController {
    private final OperationalMetrics metrics;
    private final JudgeQueue queue;
    private final JudgeProperties judge;
    private final DataSource dataSource;
    private final Path materialsRoot;

    public AdminOperationsController(OperationalMetrics metrics, JudgeQueue queue,
                                     JudgeProperties judge, DataSource dataSource,
                                     @Value("${arbitrator.materials.root:./arbitrator-data/materials}") String materialsRoot) {
        this.metrics = metrics;
        this.queue = queue;
        this.judge = judge;
        this.dataSource = dataSource;
        this.materialsRoot = Path.of(materialsRoot);
    }

    @GetMapping("/api/admin/operations")
    public Snapshot operations() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os = ManagementFactory.getOperatingSystemMXBean();
        Double cpuPercent = null;
        long hostMemoryTotal = -1, hostMemoryFree = -1;
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            double load = extended.getCpuLoad();
            if (Double.isFinite(load) && load >= 0) cpuPercent = Math.round(load * 1000) / 10.0;
            hostMemoryTotal = extended.getTotalMemorySize();
            hostMemoryFree = extended.getFreeMemorySize();
        }
        return new Snapshot(Instant.now(), ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                cpuPercent, hostMemoryTotal, hostMemoryFree, heap.getUsed(), heap.getMax(),
                disk(Path.of(judge.getWorkRoot())), disk(materialsRoot), queue.depth(),
                pool(), metrics.snapshot());
    }

    private static Disk disk(Path root) {
        try {
            var store = Files.getFileStore(root);
            return new Disk(true, store.getUsableSpace(), store.getTotalSpace());
        } catch (IOException | SecurityException unavailable) {
            return new Disk(false, -1, -1);
        }
    }

    private Pool pool() {
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean bean = hikari.getHikariPoolMXBean();
            if (bean != null) {
                return new Pool(true, bean.getActiveConnections(), bean.getIdleConnections(),
                        bean.getThreadsAwaitingConnection(), bean.getTotalConnections());
            }
        }
        return new Pool(false, -1, -1, -1, -1);
    }

    public record Disk(boolean available, long freeBytes, long totalBytes) {}
    public record Pool(boolean available, int active, int idle, int waiting, int total) {}
    public record Snapshot(Instant sampledAt, long uptimeSeconds, Double hostCpuPercent,
                           long hostMemoryTotalBytes, long hostMemoryFreeBytes,
                           long jvmHeapUsedBytes, long jvmHeapMaxBytes,
                           Disk judgeDisk, Disk materialsDisk, int judgeQueueDepth,
                           Pool databasePool, OperationalMetrics.WindowSnapshot recent60Seconds) {}
}
