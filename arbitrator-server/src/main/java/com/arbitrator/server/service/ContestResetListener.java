package com.arbitrator.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.repo.SubmissionRepository;

import jakarta.annotation.PostConstruct;

/**
 * Clears a contest's previous submissions when it is (re)started.
 *
 * Without this, restarting a contest leaves problems already showing as solved
 * and old penalty still counted — the standings would begin mid-game. Rows are
 * flagged inactive rather than deleted, so the record survives (DBR-04) while
 * every "active" query ignores them.
 */
@Component
public class ContestResetListener {

    private static final Logger log = LoggerFactory.getLogger(ContestResetListener.class);

    private final ContestService contestService;
    private final SubmissionRepository submissions;

    public ContestResetListener(ContestService contestService,
                                SubmissionRepository submissions) {
        this.contestService = contestService;
        this.submissions = submissions;
    }

    @PostConstruct
    void register() {
        contestService.onContestStarted(this::archivePreviousRun);
    }

    @Transactional
    void archivePreviousRun(long contestId) {
        int archived = 0;
        for (Submission s : submissions.findByContestId(contestId)) {
            if (s.isActive()) {
                s.setActive(false);
                submissions.save(s);
                archived++;
            }
        }
        if (archived > 0) {
            log.info("Contest {} restarted — archived {} submission(s) from the previous run",
                    contestId, archived);
        }
    }
}
