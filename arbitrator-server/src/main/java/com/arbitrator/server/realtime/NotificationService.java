/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.realtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.arbitrator.common.dto.NotificationDto;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.service.ContestService;

import jakarta.annotation.PostConstruct;

/**
 * Turns raw presence transitions ({@link PresenceTracker}) into exactly the
 * two notifications an instructor actually wants — "X disconnected" and "X
 * reconnected after Nm" — one each, the moment they happen. This replaced an
 * earlier design that raised a fresh alert every whole minute a participant
 * stayed offline (1 min, 2 min, 3 min, ...), which read as constant spam
 * rather than something worth glancing at.
 *
 * Kept as one flat, ever-growing list (capped) so the console's Notifications
 * tab can show the full history with delete, while the live toast stack
 * (client-side) only pops whatever is new since its last poll.
 */
@Component
public class NotificationService {

    private static final int MAX_NOTIFICATIONS = 500;

    private final PresenceTracker presence;
    private final UserRepository users;
    private final ContestRepository contests;
    private final ContestService contestService;

    /** Newest first — index 0 is what the console renders at the top. */
    private final List<NotificationDto> notifications = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public NotificationService(PresenceTracker presence, UserRepository users,
                                ContestRepository contests, ContestService contestService) {
        this.presence = presence;
        this.users = users;
        this.contests = contests;
        this.contestService = contestService;

        presence.onWentOffline((contestId, username) -> raise(contestId, username, "DISCONNECTED", 0));
        presence.onReconnected((contestId, username, offlineDurationMs) ->
                raise(contestId, username, "RECONNECTED", (int) (offlineDurationMs / 60_000)));
    }

    @PostConstruct
    void register() {
        // A fresh run of a contest starts every participant's offline clock
        // over, and an ended one stops that clock dead — either way, nothing
        // reconnecting afterward should be able to compute a duration against
        // a contest that isn't the one currently live. See PresenceTracker's
        // offlineSince/clearContest javadoc for the mechanism.
        contestService.onContestStarted(this::clearContest);
        contestService.onContestEnded(this::clearContest);
    }

    private void raise(long contestId, String username, String type, int minutesOffline) {
        String contestTitle = contests.findById(contestId)
                .map(c -> c.getTitle()).orElse("Contest #" + contestId);
        raise(contestId, contestTitle, username, type, minutesOffline);
    }

    /**
     * A user's client reported a different MAC address than last time — not
     * contest-scoped (it happens at login, before any contest is chosen), so
     * this bypasses PresenceTracker entirely and is called directly from
     * UserService.login(). Surfaced through the same poll/dismiss channel as
     * DISCONNECTED/RECONNECTED so the console needs no new endpoint.
     */
    public void raiseMacChanged(String username) {
        raise(0, "Account", username, "MAC_CHANGED", 0);
    }

    private void raise(long contestId, String contestTitle, String username, String type,
                       int minutesOffline) {
        String displayName = users.findByUsername(username)
                .map(User::getDisplayName).orElse(username);

        NotificationDto n = new NotificationDto(
                nextId.getAndIncrement(), contestId, contestTitle,
                username, displayName, type, minutesOffline, System.currentTimeMillis());
        notifications.add(0, n);
        while (notifications.size() > MAX_NOTIFICATIONS) {
            notifications.remove(notifications.size() - 1);
        }
    }

    /** Newest first — the console's toast stack and Notifications tab both read this. */
    public List<NotificationDto> current() {
        return List.copyOf(notifications);
    }

    /** The instructor dismissed one — it never reappears. */
    public boolean dismiss(long id) {
        return notifications.removeIf(n -> n.id() == id);
    }

    /**
     * The instructor dismissed one user's whole run of entries at once — the
     * console groups the feed per-user precisely so a participant who
     * dropped and reconnected 500 times can be cleared in one click instead
     * of 500 individual deletes.
     */
    public boolean dismissAllForUser(String username) {
        return notifications.removeIf(n -> n.username().equals(username));
    }

    /**
     * Hooked to both {@code ContestService.onContestStarted} and {@code
     * onContestEnded}: presence tracking for this contest is no longer
     * meaningful once it's not the one currently live, so any participant
     * still marked "offline since ..." must stop counting rather than
     * surface a stale "reconnected after 47m" the next time they're seen —
     * whether that's a fresh run of the same contest or a different one
     * entirely. Notification HISTORY itself is untouched — this only clears
     * the live offline-duration clocks, not what's already in the feed.
     */
    public void clearContest(long contestId) {
        presence.clearContest(contestId);
    }
}
