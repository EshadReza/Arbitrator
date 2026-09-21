/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

class JudgeQueueLimitTest {

    @Test
    void tenThousandParallelAttemptsRespectGlobalBackpressureAndWorkerConcurrencyThenRecover() throws Exception {
        JudgeProperties props = new JudgeProperties();
        props.setThreads(4);
        props.setMaxBacklog(500);
        var releaseWorkers = new CountDownLatch(1);
        var workersStarted = new CountDownLatch(4);
        var completed = new AtomicInteger();
        var active = new AtomicInteger();
        var peakActive = new AtomicInteger();
        JudgeWorker worker = worker(props, id -> {
            int running = active.incrementAndGet();
            peakActive.accumulateAndGet(running, Math::max);
            workersStarted.countDown();
            try { assertTrue(releaseWorkers.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            finally { active.decrementAndGet(); completed.incrementAndGet(); }
        });
        JudgeQueue queue = new JudgeQueue(props, worker, null);
        var callers = Executors.newFixedThreadPool(32);
        var admitted = new AtomicInteger();
        var rejected = new AtomicInteger();
        var peakDepth = new AtomicInteger();
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int caller = 0; caller < 32; caller++) {
                final int start = caller;
                tasks.add(callers.submit(() -> {
                    for (int attempt = start; attempt < 10000; attempt += 32) {
                        if (queue.tryReserve()) {
                            admitted.incrementAndGet();
                            queue.enqueueReserved(attempt + 1L);
                            peakDepth.accumulateAndGet(queue.depth(), Math::max);
                        } else rejected.incrementAndGet();
                    }
                }));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS));
            assertEquals(500, admitted.get());
            assertEquals(9500, rejected.get());
            assertEquals(500, queue.depth());
            assertEquals(500, peakDepth.get());
            assertEquals(4, peakActive.get());
            releaseWorkers.countDown();
            await(() -> completed.get() == 500 && queue.depth() == 0);
            assertAllSlotsRecover(queue, 500);
            assertTrue(queue.tryReserve());
            queue.enqueueReserved(10001L);
            await(() -> completed.get() == 501 && queue.depth() == 0);
            assertTrue(peakActive.get() <= 4);
        } finally {
            releaseWorkers.countDown();
            callers.shutdownNow();
            queue.shutdown();
        }
    }

    @Test
    void workerFailuresReleaseEveryReservationAndOutstandingCount() throws Exception {
        JudgeProperties props = new JudgeProperties();
        props.setThreads(1);
        props.setMaxBacklog(3);
        var failed = new AtomicInteger();
        JudgeQueue queue = new JudgeQueue(props, worker(props, id -> {
            failed.incrementAndGet();
            throw new IllegalStateException("controlled worker failure");
        }), null);
        try {
            for (int i = 0; i < 3; i++) { assertTrue(queue.tryReserve()); queue.enqueueReserved(i); }
            await(() -> failed.get() == 3 && queue.depth() == 0);
            assertAllSlotsRecover(queue, 3);
        } finally { queue.shutdown(); }
    }

    @Test
    void executorRejectionReleasesReservationAndDoesNotLeavePhantomDepth() throws Exception {
        JudgeProperties props = new JudgeProperties();
        props.setThreads(1);
        props.setMaxBacklog(3);
        JudgeQueue queue = new JudgeQueue(props, null, null);
        queue.shutdown();
        assertTrue(queue.tryReserve());
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> queue.enqueueReserved(1));
        assertEquals(0, queue.depth());
        assertAllSlotsRecover(queue, 3);
    }

    private static JudgeWorker worker(JudgeProperties props, java.util.function.LongConsumer action) {
        return new JudgeWorker(null, null, null, null, null, null, null, null, null, props, null) {
            @Override public void judge(long id) { action.accept(id); }
        };
    }
    private static void await(BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000);
        assertTrue(done.getAsBoolean(), "queue failed to drain/recover within five seconds");
    }
    private static void assertAllSlotsRecover(JudgeQueue queue, int capacity) {
        var reserved = new AtomicInteger();
        try {
            await(() -> {
                while (reserved.get() < capacity && queue.tryReserve()) reserved.incrementAndGet();
                return reserved.get() == capacity;
            });
            assertFalse(queue.tryReserve(), "recovery must not accidentally increase queue capacity");
        } finally {
            for (int i = 0; i < reserved.get(); i++) queue.releaseReservation();
        }
    }

    @Test
    void globalBacklogReservationsAreBounded() {
        JudgeProperties props = new JudgeProperties();
        props.setThreads(1);
        props.setMaxBacklog(2);
        JudgeQueue queue = new JudgeQueue(props, null, null);
        try {
            assertTrue(queue.tryReserve());
            assertTrue(queue.tryReserve());
            assertFalse(queue.tryReserve());
            queue.releaseReservation();
            assertTrue(queue.tryReserve());
            queue.releaseReservation();
            queue.releaseReservation();
        } finally {
            queue.shutdown();
        }
    }
}
