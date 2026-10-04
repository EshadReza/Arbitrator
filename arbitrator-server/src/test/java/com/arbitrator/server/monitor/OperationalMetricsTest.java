package com.arbitrator.server.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletException;

class OperationalMetricsTest {
    @Test
    void countersAndQueueWaitExpireAfterOneMinute() {
        MutableClock clock = new MutableClock();
        OperationalMetrics metrics = new OperationalMetrics(clock);
        metrics.recordHttpStatus(500, "/api/contests");
        metrics.recordHttpStatus(503, "/api/admin/operations");
        metrics.recordHttpStatus(401, "/api/auth/login");
        metrics.recordHttpStatus(429, "/api/auth/login");
        metrics.recordHttpStatus(401, "/api/contests");
        metrics.recordJudgeFailure();
        metrics.recordQueueWait(10);
        metrics.recordQueueWait(30);

        assertEquals(new OperationalMetrics.WindowSnapshot(2, 2, 1, 2, 20, 30),
                metrics.snapshot());
        clock.seconds += 61;
        assertEquals(new OperationalMetrics.WindowSnapshot(0, 0, 0, 0, 0, 0),
                metrics.snapshot());
    }

    @Test
    void filterCountsFinalStatusesAndUnhandledFailuresWithoutChangingResponses() throws Exception {
        OperationalMetrics metrics = new OperationalMetrics();
        OperationalRequestMetricsFilter filter = new OperationalRequestMetricsFilter(metrics);
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilterInternal(login, denied, (req, res) -> denied.setStatus(401));
        assertEquals(401, denied.getStatus());

        MockHttpServletRequest broken = new MockHttpServletRequest("GET", "/api/contests");
        assertThrows(ServletException.class, () -> filter.doFilterInternal(broken,
                new MockHttpServletResponse(), (req, res) -> {
                    throw new ServletException(new IOException("internal failure"));
                }));

        assertEquals(1, metrics.snapshot().http5xx());
        assertEquals(1, metrics.snapshot().failedLogins());
    }

    private static final class MutableClock extends Clock {
        long seconds;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds); }
    }
}
