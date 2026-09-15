package com.arbitrator.server.security;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks the one JWT session id ("sid" claim) currently allowed to
 * authenticate as each username — the enforcement side of "same user cannot
 * log in from two instances". Pure in-memory: this is a single-server
 * deployment (D2/D3, no cluster), so there is nothing to share across
 * instances, same reasoning as {@link com.arbitrator.server.realtime.PresenceTracker}'s
 * own maps.
 *
 * A logged-in token is valid only as long as its sid still matches the entry
 * here — a second successful login with {@code force=true} overwrites the
 * entry, which is what silently invalidates the first session's token on its
 * very next request (see {@link JwtAuthFilter}).
 */
@Component
public class ActiveSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveSessionRegistry.class);
    private final Map<String, String> activeSessionId = new ConcurrentHashMap<>();
    private final List<SessionInvalidationListener> invalidationListeners =
            new CopyOnWriteArrayList<>();
    private final Object mutationLock = new Object();

    /**
     * @return true if {@code sid} is now the active session for
     *         {@code username} (either none was active, or {@code force}
     *         overrode the existing one); false if someone else's session is
     *         active and {@code force} was not set — the caller must not
     *         issue a token in that case.
     */
    public boolean register(String username, String sid, boolean force) {
        String previous;
        synchronized (mutationLock) {
            previous = activeSessionId.get(username);
            if (previous != null && !force) {
                return false;
            }
            activeSessionId.put(username, sid);
        }
        if (previous != null && !previous.equals(sid)) {
            notifyInvalidated(username, previous);
        }
        return true;
    }

    /** True when {@code sid} is (still) the active session for {@code username}. */
    public boolean isActive(String username, String sid) {
        return sid != null && sid.equals(activeSessionId.get(username));
    }

    public boolean hasActiveSession(String username) {
        return activeSessionId.containsKey(username);
    }

    /** Frees the slot so the next login never needs force=true. */
    public void clear(String username) {
        String previous;
        synchronized (mutationLock) {
            previous = activeSessionId.remove(username);
        }
        if (previous != null) {
            notifyInvalidated(username, previous);
        }
    }

    /** Lets transports revoke their already-open sessions at the same instant as REST. */
    public void onInvalidated(SessionInvalidationListener listener) {
        invalidationListeners.add(listener);
    }

    private void notifyInvalidated(String username, String sid) {
        invalidationListeners.forEach(listener -> {
            try {
                listener.invalidated(username, sid);
            } catch (RuntimeException e) {
                // Authentication state has already changed and must not be
                // rolled back merely because transport cleanup had a problem.
                log.warn("Session {} for {} was invalidated, but a cleanup listener failed",
                        sid, username, e);
            }
        });
    }

    @FunctionalInterface
    public interface SessionInvalidationListener {
        void invalidated(String username, String sid);
    }
}
