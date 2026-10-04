/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * Bounded worker pool over a bounded FIFO queue (NFR-P04: >= 10 parallel
 * jobs). Submissions are persisted BEFORE enqueueing
 * (FMEA-01), so the in-memory queue is disposable: on startup anything not
 * DONE in the database is requeued (NFR-R02 crash recovery).
 */
@Component
public class JudgeQueue {

    private static final Logger log = LoggerFactory.getLogger(JudgeQueue.class);

    private final ThreadPoolExecutor pool;
    private final Semaphore backlogSlots;
    private final AtomicInteger outstanding = new AtomicInteger();
    private final JudgeWorker worker;
    private final SubmissionRepository submissions;

    public JudgeQueue(JudgeProperties props, JudgeWorker worker,
                      SubmissionRepository submissions) {
        this.worker = worker;
        this.submissions = submissions;
        int threads = Math.max(1, props.getThreads());
        int maxBacklog = Math.max(threads, props.getMaxBacklog());
        this.backlogSlots = new Semaphore(maxBacklog, true);
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxBacklog), r -> {
            Thread t = new Thread(r, "judge-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** Atomically reserves global backlog capacity before a submission is persisted. */
    public boolean tryReserve() {
        return backlogSlots.tryAcquire();
    }

    public void releaseReservation() {
        backlogSlots.release();
    }

    /** @return 1-based queue position at receipt (SubmitAckDto, §6.1 glossary). */
    public int enqueue(long submissionId) {
        backlogSlots.acquireUninterruptibly();
        return enqueueReserved(submissionId);
    }

    /** Enqueues after a successful {@link #tryReserve()} call. */
    public int enqueueReserved(long submissionId) {
        int position = outstanding.incrementAndGet();
        try {
            pool.submit(() -> {
                try {
                    worker.judge(submissionId);
                } finally {
                    outstanding.decrementAndGet();
                    backlogSlots.release();
                }
            });
        } catch (RuntimeException e) {
            outstanding.decrementAndGet();
            backlogSlots.release();
            throw e;
        }
        return position;
    }

    /** Admin dashboard metric (UIF-23, wired in chunk S4-B4). */
    public int depth() {
        return outstanding.get();
    }

    /** True when the worker executor is running and a new submission can reserve a slot. */
    public boolean canAcceptWork() {
        return workersAvailable() && backlogSlots.availablePermits() > 0;
    }

    /** Executor availability, not proof that an individual long-running judge job will finish. */
    public boolean workersAvailable() {
        return !pool.isShutdown() && !pool.isTerminating() && !pool.isTerminated();
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
