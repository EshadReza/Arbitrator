package com.labjudge.common.enums;

/** Submission outcome per SRS §1.5. Order is not significant. */
public enum Verdict {
    AC("Accepted"),
    WA("Wrong Answer"),
    TLE("Time Limit Exceeded"),
    MLE("Memory Limit Exceeded"),
    CE("Compilation Error"),
    RE("Runtime Error");

    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    /** Full descriptive text required next to every badge (NFR-U02). */
    public String label() {
        return label;
    }
}
