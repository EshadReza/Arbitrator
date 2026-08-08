package com.arbitrator.server.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.arbitrator.common.dto.OfflineAlertDto;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.service.ContestService;

import jakarta.annotation.PostConstruct;

/**
 * Watches {@link PresenceTracker#offlineSince()} and raises one alert per
 * whole minute a participant stays gone — offline at 1 minute, another at 2,
 * another at 3, and so on, not one alert that silently updates its number.
 * That escalation is the point: a student gone 1 minute is probably just
 * re-reading the problem on paper, gone 5 minutes is worth walking over to.
 *
 * Alerts are in-memory only (same lifetime guarantee as PresenceTracker
 * itself — a server restart already drops every live session, so stale
 * alerts would be meaningless anyway) and capped so a long-forgotten offline
 * student cannot grow the list forever.
 */
@Component
public class OfflineAlertService {

    private static final int MAX_ALERTS = 300;

    private final PresenceTracker presence;
    private final UserRepository users;
    private final ContestRepository contests;
    private final ContestService contestService;

    /** Newest first — index 0 is what the console renders at the top. */
    private final List<OfflineAlertDto> alerts = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);

    /** "contestId:username" -> next whole-minute mark that still owes an alert. */
    private final Map<String, Integer> nextThreshold = new ConcurrentHashMap<>();

    public OfflineAlertService(PresenceTracker presence, UserRepository users,
                               ContestRepository contests, ContestService contestService) {
        this.presence = presence;
        this.users = users;
        this.contests = contests;
        this.contestService = contestService;
    }

    @PostConstruct
    void register() {
        // A fresh run of a contest starts every participant's offline clock
        // over — see clearContest()'s own javadoc for why.
        contestService.onContestStarted(this::clearContest);
    }

    @Scheduled(fixedDelayString = "15000")
    void sweep() {
        Map<String, Instant> offline = presence.offlineSince();

        // Forget thresholds for anyone who has reconnected since the last
        // sweep — if they go offline again later it starts back at minute 1.
        nextThreshold.keySet().retainAll(offline.keySet());

        offline.forEach((key, since) -> {
            int elapsedMinutes = (int) Duration.between(since, Instant.now()).toMinutes();
            int threshold = nextThreshold.getOrDefault(key, 1);
            // A while(...) rather than if(...): a sweep that was ever late
            // (or a very short SWEEP_INTERVAL vs. a long pause) must still
            // raise 1, 2, 3... in order rather than skipping straight to
            // whatever minute it currently is.
            while (elapsedMinutes >= threshold) {
                raise(key, threshold);
                threshold++;
            }
            nextThreshold.put(key, threshold);
        });
    }

    private void raise(String key, int minutesOffline) {
        int sep = key.indexOf(':');
        long contestId = Long.parseLong(key.substring(0, sep));
        String username = key.substring(sep + 1);

        String displayName = users.findByUsername(username)
                .map(User::getDisplayName).orElse(username);
        String contestTitle = contests.findById(contestId)
                .map(c -> c.getTitle()).orElse("Contest #" + contestId);

        OfflineAlertDto alert = new OfflineAlertDto(
                nextId.getAndIncrement(), contestId, contestTitle,
                username, displayName, minutesOffline, System.currentTimeMillis());
        alerts.add(0, alert);
        while (alerts.size() > MAX_ALERTS) {
            alerts.remove(alerts.size() - 1);
        }
    }

    /** Newest first, for the console's notification stack. */
    public List<OfflineAlertDto> current() {
        return List.copyOf(alerts);
    }

    /** The instructor dismissed one — it never reappears. */
    public boolean dismiss(long id) {
        return alerts.removeIf(a -> a.id() == id);
    }

    /**
     * Hooked to {@code ContestService.onContestStarted}: a fresh run of a
     * contest starts every participant's offline clock over, so alerts from
     * a previous run (or from the lobby, before this method even existed to
     * gate it) don't linger into the new one.
     */
    public void clearContest(long contestId) {
        presence.clearContest(contestId);
        nextThreshold.keySet().removeIf(k -> k.startsWith(contestId + ":"));
        alerts.removeIf(a -> a.contestId() == contestId);
    }
}
