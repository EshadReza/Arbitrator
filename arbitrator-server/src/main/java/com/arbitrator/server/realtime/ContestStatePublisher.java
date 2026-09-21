/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.service.ContestService;

/**
 * Pushes contest state to every client in that contest (FR-06).
 *
 * Without this the client reads the state once when it enters and then runs
 * its countdown purely locally — so pausing, extending or ending a contest on
 * the server changed nothing on screen and the timer kept running. State is
 * broadcast immediately on every admin action, plus every 10 s so a client
 * that missed a push (or joined late) self-heals.
 */
@Component
public class ContestStatePublisher {

    private static final Logger log = LoggerFactory.getLogger(ContestStatePublisher.class);

    private final SimpMessagingTemplate template;
    private final ContestService contestService;

    public ContestStatePublisher(SimpMessagingTemplate template, ContestService contestService) {
        this.template = template;
        this.contestService = contestService;
    }

    /** Call right after any change to a contest's lifecycle or clock. */
    public void publish(Contest contest) {
        try {
            ContestStateDto dto = contestService.stateOf(contest);
            template.convertAndSend(StompDestinations.contestState(contest.getId()), dto);
        } catch (RuntimeException e) {
            log.warn("Contest state push failed for contest {}", contest.getId(), e);
        }
    }

    /** Safety net: a missed push would otherwise leave a stale timer forever. */
    @Scheduled(fixedDelayString = "${arbitrator.contest.state-push-interval-ms:10000}")
    public void heartbeat() {
        for (Contest contest : contestService.joinable()) {
            publish(contest);
        }
    }
}
