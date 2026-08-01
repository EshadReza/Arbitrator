package com.labjudge.server.judge;

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
        boolean timedOut
) {

    public boolean ok() {
        return exitCode == 0 && !timedOut;
    }
}
