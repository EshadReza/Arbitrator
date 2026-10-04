package com.arbitrator.server.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;

/** Transactional ORM change history with a field allowlist, never full entity serialization. */
@Component
public class AuditEntityListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {
    private static final Set<String> SAFE = Set.of("role", "contestId", "problemId", "userId", "idx",
            "state", "durationMinutes", "startTime", "endedAt", "pausedAt", "pausedMillis", "scheduledStartAt", "frozenAt",
            "timeLimitMs", "memoryLimitKb", "ordering", "checkerType", "sizeBytes",
            "statementIsPdf", "version", "marks", "manualPenaltyDelta", "verdict", "active", "status",
            "approved", "isPublic", "showTestCases", "allowPrivateClarifications");
    private final EntityManagerFactory factory;
    private final AuditService audit;
    public AuditEntityListener(EntityManagerFactory factory, AuditService audit) { this.factory = factory; this.audit = audit; }

    @PostConstruct void register() {
        var registry = factory.unwrap(SessionFactoryImplementor.class).getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    private Map<String, Object> snapshot(EntityPersister persister, Object[] state) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (state == null) return result;
        String[] names = persister.getPropertyNames();
        for (int i = 0; i < names.length; i++) {
            Object value = state[i];
            if (SAFE.contains(names[i]) && (value == null || value instanceof Number || value instanceof Boolean
                    || value instanceof Enum<?> || value instanceof java.time.Instant)) result.put(names[i], value);
        }
        return result;
    }

    private void record(EntityPersister p, Object id, Object[] before, Object[] after) {
        if (AuditContext.current() == null) return;
        String name = p.getMappedClass().getSimpleName();
        audit.change(name, id, before == null ? null : snapshot(p, before), after == null ? null : snapshot(p, after));
    }
    @Override public void onPostInsert(PostInsertEvent event) { record(event.getPersister(), event.getId(), null, event.getState()); }
    @Override public void onPostUpdate(PostUpdateEvent event) { record(event.getPersister(), event.getId(), event.getOldState(), event.getState()); }
    @Override public void onPostDelete(PostDeleteEvent event) { record(event.getPersister(), event.getId(), event.getDeletedState(), null); }
    @Override public boolean requiresPostCommitHandling(EntityPersister persister) { return false; }
}
