/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.leaderboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.realtime.ContestStatePublisher;
import com.arbitrator.server.service.ContestService;

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
    private final ContestStatePublisher statePublisher;

    public LeaderboardBroadcaster(LeaderboardService leaderboard,
                                  ContestService contestService,
                                  SimpMessagingTemplate template,
                                  ContestStatePublisher statePublisher) {
        this.leaderboard = leaderboard;
        this.contestService = contestService;
        this.template = template;
        this.statePublisher = statePublisher;
    }

    @Scheduled(fixedDelay = 1000)
    public void checkScheduledLobbies() {
        for (Contest contest : contestService.all()) {
            if (contest.getState() == com.arbitrator.common.enums.ContestState.LOBBY
                    && contest.getScheduledStartAt() != null
                    && !java.time.Instant.now().isBefore(contest.getScheduledStartAt())) {
                Contest started = contestService.start(contest.getId());
                statePublisher.publish(started);
            }
        }
    }

    /** FR-17: the periodic heartbeat, so late joiners and idle clients stay current. */
    @Scheduled(fixedDelayString = "${arbitrator.leaderboard.push-interval-ms:30000}")
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

    /**
     * Publishes standings for EVERY joinable contest, not just one. Several
     * contests can run at once, and each has its own STOMP topic — pushing
     * only "the current" one would leave every other contest's board frozen.
     */
    private void publish() {
        for (Contest contest : contestService.joinable()) {
            try {
                LeaderboardDto dto = leaderboard.forStudents(contest);
                template.convertAndSend(
                        StompDestinations.contestLeaderboard(contest.getId()), dto);
            } catch (RuntimeException e) {
                // One bad contest must not stop the others, and must never kill
                // the scheduler thread or fail a judging job (FMEA-10).
                log.warn("Leaderboard broadcast failed for contest {}", contest.getId(), e);
            }
        }
    }
}
