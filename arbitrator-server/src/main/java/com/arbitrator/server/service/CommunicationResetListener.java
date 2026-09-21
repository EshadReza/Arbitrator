/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.arbitrator.server.entity.Announcement;
import com.arbitrator.server.entity.Clarification;
import com.arbitrator.server.repo.AnnouncementRepository;
import com.arbitrator.server.repo.ClarificationRepository;

import jakarta.annotation.PostConstruct;

/**
 * Clears a contest's previous announcements and clarifications when it is
 * (re)started — the same board, one hook over from {@link ContestResetListener}.
 *
 * A restart reuses the same contest id, and both boards are queried by
 * contest id alone with no notion of "run" (AnnouncementRepository /
 * ClarificationRepository only ever filter by contestId). Left alone, every
 * announcement and clarification from the contest's previous attempt stayed
 * visible forever, mixed in with whatever the instructor posts this time —
 * exactly the stale-standings problem archivePreviousRun() already solves
 * for submissions, just on a different table.
 */
@Component
public class CommunicationResetListener {

    private static final Logger log = LoggerFactory.getLogger(CommunicationResetListener.class);

    private final ContestService contestService;
    private final AnnouncementRepository announcements;
    private final ClarificationRepository clarifications;

    public CommunicationResetListener(ContestService contestService,
                                      AnnouncementRepository announcements,
                                      ClarificationRepository clarifications) {
        this.contestService = contestService;
        this.announcements = announcements;
        this.clarifications = clarifications;
    }

    @PostConstruct
    void register() {
        contestService.onContestStarted(this::clearPreviousRun);
    }

    /**
     * deleteAll(Iterable) rather than a derived deleteByContestId: the latter
     * is executed via find-then-remove on the entity manager, and needs an
     * active transaction that isn't there — this class runs its own
     * @Transactional through a self-invoked method reference (registered in
     * {@link #register}), which bypasses the Spring proxy that would normally
     * open one. deleteAll(), like save(), is a genuine SimpleJpaRepository
     * method the repository proxy itself wraps in a transaction, so it works
     * regardless of the caller's transactional context — the same reason
     * {@link ContestResetListener#archivePreviousRun} works via save() alone.
     */
    @Transactional
    void clearPreviousRun(long contestId) {
        List<Announcement> oldAnnouncements = announcements.findByContestIdOrderByCreatedAtDesc(contestId);
        List<Clarification> oldClarifications = clarifications.findByContestIdOrderByAskedAtDesc(contestId);
        if (!oldAnnouncements.isEmpty()) {
            announcements.deleteAll(oldAnnouncements);
        }
        if (!oldClarifications.isEmpty()) {
            clarifications.deleteAll(oldClarifications);
        }
        if (!oldAnnouncements.isEmpty() || !oldClarifications.isEmpty()) {
            log.info("Contest {} restarted — cleared {} announcement(s) and {} clarification(s) from the previous run",
                    contestId, oldAnnouncements.size(), oldClarifications.size());
        }
    }
}
