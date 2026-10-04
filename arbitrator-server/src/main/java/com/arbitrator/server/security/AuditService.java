package com.arbitrator.server.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final java.util.function.LongSupplier clock;
    private long failureWindow;
    private int failures;
    private long suppressed;

    @org.springframework.beans.factory.annotation.Autowired
    public AuditService(JdbcTemplate jdbc, ObjectMapper json) { this(jdbc, json, System::currentTimeMillis); }

    AuditService(JdbcTemplate jdbc, ObjectMapper json, java.util.function.LongSupplier clock) {
        this.jdbc = jdbc; this.json = json; this.clock = clock;
        this.failureWindow = clock.getAsLong() / 60_000;
    }

    /** Joins the same datasource transaction as Hibernate/direct SQL. Failure rolls back the change. */
    public void change(String type, Object id, Object before, Object after) {
        AuditContext context = AuditContext.current();
        if (context == null) return; // Internal startup/judge work is not an administrative HTTP action.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit change requires an active transaction");
        }
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("before", before);
        details.put("after", after);
        insert(context.actor(), context.action(), type, String.valueOf(id), context.peerIp(),
                "COMMITTED_CHANGE", null, details);
    }

    /** Request completion is independent of commit: an action can contain several transactions. */
    public void request(String actor, String action, String peer, int status, String claimedSubject) {
        if (status >= 400 && !admitFailure()) return;
        try {
            insert(actor, action, null, null, peer, status < 400 ? "REQUEST_COMPLETED" : "REQUEST_FAILED",
                    status, claimedSubject == null ? Map.of() : Map.of("claimedSubject", claimedSubject));
        } catch (RuntimeException failure) {
            // Do not turn a completed login into a failed login or leak SQL values into error logs.
            log.warn("Security request audit could not be persisted");
        }
    }

    private synchronized boolean admitFailure() {
        flushFailureWindow();
        if (failures < 200) { failures++; return true; }
        suppressed++;
        return false;
    }

    private synchronized void flushFailureWindow() {
        long now = clock.getAsLong() / 60_000;
        if (now == failureWindow) return;
        if (suppressed > 0) {
            try {
                insert(null, "FAILED_REQUESTS_SUPPRESSED", null, null, null, "SUMMARY", null,
                        Map.of("count", suppressed, "windowEpochMinute", failureWindow));
            } catch (RuntimeException failure) {
                log.warn("Security audit suppression summary could not be persisted");
            }
        }
        failureWindow = now; failures = 0; suppressed = 0;
    }

    private void insert(String actor, String action, String type, String id, String peer,
                        String outcome, Integer status, Object details) {
        String encoded;
        try { encoded = json.writeValueAsString(details); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Audit metadata encoding failed"); }
        if (encoded.length() > 4096) throw new IllegalStateException("Audit metadata exceeds its bound");
        jdbc.update("INSERT INTO audit_events(actor,action,target_type,target_id,peer_ip,outcome,http_status,details) VALUES (?,?,?,?,?,?,?,?)",
                actor, action, type, id, peer, outcome, status, encoded);
    }

    public record Event(long id, Instant occurredAt, String actor, String action, String targetType,
                        String targetId, String peerIp, String outcome, Integer httpStatus, Object details) {}

    public List<Event> page(long before, int limit) {
        return jdbc.query("SELECT * FROM audit_events WHERE id < ? ORDER BY id DESC LIMIT ?", (row, n) -> {
            try {
                return new Event(row.getLong("id"), row.getTimestamp("occurred_at").toInstant(),
                        row.getString("actor"), row.getString("action"), row.getString("target_type"),
                        row.getString("target_id"), row.getString("peer_ip"), row.getString("outcome"),
                        (Integer) row.getObject("http_status"), json.readTree(row.getString("details")));
            } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid audit metadata"); }
        }, before, limit);
    }

    /** Keep at most the newest 100,000 ID slots and 90 days, checked every minute.
     * Concurrent writes may temporarily exceed the cap until the next sweep. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void maintain() {
        flushFailureWindow();
        try {
            Long latest = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM audit_events", Long.class);
            jdbc.update("DELETE FROM audit_events WHERE id <= ? OR occurred_at < CURRENT_TIMESTAMP - INTERVAL 90 DAY",
                    Math.max(0, latest - 100_000));
        } catch (RuntimeException failure) { log.warn("Security audit retention sweep failed"); }
    }
}
