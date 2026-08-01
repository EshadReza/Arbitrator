package com.labjudge.server.leaderboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.labjudge.common.api.StompDestinations;
import com.labjudge.common.dto.LeaderboardDto;
import com.labjudge.common.enums.ContestState;
import com.labjudge.server.entity.Contest;
import com.labjudge.server.service.ContestService;

/**
 * FR-17: recalculate and push standings to every connected client every 30 s
 * while a contest is running.
 *
 * FR-19 freeze is handled one level down: when the contest is FROZEN the
 * service still computes internally (judging never stops) but marks the
 * payload frozen, and the client shows the freeze banner (UIF-16).
 */
@Component
public class LeaderboardBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardBroadcaster.class);

    private final LeaderboardService leaderboard;
    private final ContestService contestService;
    private final SimpMessagingTemplate template;

    public LeaderboardBroadcaster(LeaderboardService leaderboard,
                                  ContestService contestService,
                                  SimpMessagingTemplate template) {
        this.leaderboard = leaderboard;
        this.contestService = contestService;
        this.template = template;
    }

    /** FR-17: the periodic heartbeat, so late joiners and idle clients stay current. */
    @Scheduled(fixedDelayString = "${labjudge.leaderboard.push-interval-ms:30000}")
    public void broadcast() {
        publish();
    }

    /**
     * NFR-P03: standings must reach clients within 5 s of a verdict being
     * recorded — waiting for the next 30 s tick misses that by design and makes
     * the board look frozen. Called by the judge worker right after it records
     * a result.
     */
    public void broadcastNow() {
        publish();
    }

    private void publish() {
        Contest contest;
        try {
            contest = contestService.requireCurrent();
        } catch (RuntimeException noContest) {
            return;                      // nothing configured yet; stay quiet
        }
        if (contest.getState() == ContestState.DRAFT) {
            return;
        }
        try {
            LeaderboardDto dto = leaderboard.forContest(contest);
            template.convertAndSend(
                    StompDestinations.contestLeaderboard(contest.getId()), dto);
        } catch (RuntimeException e) {
            // A broken push must never kill the scheduler thread or fail a
            // judging job — the next tick should still fire (FMEA-10).
            log.warn("Leaderboard broadcast failed", e);
        }
    }
}
