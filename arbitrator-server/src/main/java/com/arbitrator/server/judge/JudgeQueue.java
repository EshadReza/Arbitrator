package com.arbitrator.server.judge;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.repo.SubmissionRepository;

import jakarta.annotation.PreDestroy;

/**
 * Bounded worker pool over an unbounded FIFO queue (NFR-P04: >= 10 parallel
 * jobs, nothing dropped). Submissions are persisted BEFORE enqueueing
 * (FMEA-01), so the in-memory queue is disposable: on startup anything not
 * DONE in the database is requeued (NFR-R02 crash recovery).
 */
@Component
public class JudgeQueue {

    private static final Logger log = LoggerFactory.getLogger(JudgeQueue.class);

    private final ExecutorService pool;
    private final AtomicInteger outstanding = new AtomicInteger();
    private final JudgeWorker worker;
    private final SubmissionRepository submissions;

    public JudgeQueue(JudgeProperties props, JudgeWorker worker,
                      SubmissionRepository submissions) {
        this.worker = worker;
        this.submissions = submissions;
        this.pool = Executors.newFixedThreadPool(props.getThreads(), r -> {
            Thread t = new Thread(r, "judge-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** @return 1-based queue position at receipt (SubmitAckDto, §6.1 glossary). */
    public int enqueue(long submissionId) {
        int position = outstanding.incrementAndGet();
        pool.submit(() -> {
            try {
                worker.judge(submissionId);
            } finally {
                outstanding.decrementAndGet();
            }
        });
        return position;
    }

    /** Admin dashboard metric (UIF-23, wired in chunk S4-B4). */
    public int depth() {
        return outstanding.get();
    }

    /** NFR-R02: requeue everything the previous process never finished. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinished() {
        List<Submission> pending =
                submissions.findByStatusNotOrderByQueuedAtAsc(Submission.Status.DONE);
        if (!pending.isEmpty()) {
            log.info("Crash recovery: requeueing {} unfinished submissions", pending.size());
            pending.forEach(s -> enqueue(s.getId()));
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
}
