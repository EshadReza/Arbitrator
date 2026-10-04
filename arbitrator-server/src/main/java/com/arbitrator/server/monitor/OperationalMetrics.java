package com.arbitrator.server.monitor;

import java.time.Clock;
import org.springframework.stereotype.Component;

/** Process-local, bounded one-minute counters; never stores users, paths or request bodies. */
@Component
public class OperationalMetrics {
    private static final int WINDOW_SECONDS = 60;
    private final Clock clock;
    private final Bucket[] buckets = new Bucket[WINDOW_SECONDS];

    public OperationalMetrics() {
        this(Clock.systemUTC());
    }

    OperationalMetrics(Clock clock) {
        this.clock = clock;
    }

    public void recordHttpStatus(int status, String path) {
        boolean serverError = status >= 500;
        boolean failedLogin = "/api/auth/login".equals(path) && (status == 401 || status == 429);
        if (!serverError && !failedLogin) return;
        synchronized (this) {
            Bucket bucket = current();
            if (serverError) bucket.http5xx++;
            if (failedLogin) bucket.failedLogins++;
        }
    }

    public synchronized void recordJudgeFailure() {
        current().judgeFailures++;
    }

    public synchronized void recordQueueWait(long millis) {
        Bucket bucket = current();
        long bounded = Math.max(0, millis);
        bucket.queueWaitCount++;
        bucket.queueWaitTotalMs += bounded;
        bucket.queueWaitMaxMs = Math.max(bucket.queueWaitMaxMs, bounded);
    }

    public synchronized WindowSnapshot snapshot() {
        long now = clock.instant().getEpochSecond();
        long http5xx = 0, failedLogins = 0, judgeFailures = 0;
        long waitCount = 0, waitTotal = 0, waitMax = 0;
        for (Bucket bucket : buckets) {
            if (bucket == null || bucket.epochSecond < now - WINDOW_SECONDS + 1
                    || bucket.epochSecond > now) continue;
            http5xx += bucket.http5xx;
            failedLogins += bucket.failedLogins;
            judgeFailures += bucket.judgeFailures;
            waitCount += bucket.queueWaitCount;
            waitTotal += bucket.queueWaitTotalMs;
            waitMax = Math.max(waitMax, bucket.queueWaitMaxMs);
        }
        return new WindowSnapshot(http5xx, failedLogins, judgeFailures,
                waitCount, waitCount == 0 ? 0 : waitTotal / waitCount, waitMax);
    }

    private Bucket current() {
        long second = clock.instant().getEpochSecond();
        int slot = Math.floorMod(second, WINDOW_SECONDS);
        Bucket bucket = buckets[slot];
        if (bucket == null || bucket.epochSecond != second) {
            bucket = new Bucket(second);
            buckets[slot] = bucket;
        }
        return bucket;
    }

    public record WindowSnapshot(long http5xx, long failedLogins, long judgeFailures,
                                 long queueWaitSamples, long queueWaitAverageMs,
                                 long queueWaitMaximumMs) {}

    private static final class Bucket {
        final long epochSecond;
        long http5xx, failedLogins, judgeFailures;
        long queueWaitCount, queueWaitTotalMs, queueWaitMaxMs;
        Bucket(long epochSecond) { this.epochSecond = epochSecond; }
    }
}
