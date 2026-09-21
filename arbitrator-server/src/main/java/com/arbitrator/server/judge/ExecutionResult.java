/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

/**
 * Outcome of one sandboxed process run.
 * peakMemoryKb is -1 when the platform could not measure it
 * (macOS/Windows dev fallback); the Linux sandbox always fills it.
 */
public record ExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        long wallTimeMs,
        long peakMemoryKb,
        boolean timedOut,
        boolean outputLimitExceeded
) {

    public boolean ok() {
        return exitCode == 0 && !timedOut;
    }
}
