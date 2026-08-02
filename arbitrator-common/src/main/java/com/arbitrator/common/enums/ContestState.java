package com.arbitrator.common.enums;

/** Contest lifecycle per FR-08 / FR-19. */
public enum ContestState {

    /** Created but not opened; invisible to students. */
    DRAFT,

    /**
     * Doors open, contest not begun. Students can enter and wait, but no
     * problems are released, no clock runs and nothing can be submitted —
     * a holding room so everyone is seated before the timer starts.
     */
    LOBBY,

    /** Running and accepting submissions. */
    ACTIVE,

    /** Clock stopped, submissions refused, resumable without losing time. */
    PAUSED,

    /** Running, but the public standings are held at the freeze point (FR-19). */
    FROZEN,

    /** Finished; submissions permanently refused (BR-02). */
    ENDED;

    /** States a student may enter — including the pre-start holding room. */
    public boolean isJoinable() {
        return this == LOBBY || this == ACTIVE || this == PAUSED || this == FROZEN;
    }

    /** Only these accept new submissions. */
    public boolean acceptsSubmissions() {
        return this == ACTIVE || this == FROZEN;
    }

    /**
     * Whether problem statements are visible. False in LOBBY: releasing the
     * problems early would let people read and plan before the clock starts.
     */
    public boolean releasesProblems() {
        return this == ACTIVE || this == PAUSED || this == FROZEN || this == ENDED;
    }

    /** Whether a countdown should be shown/counted at all. */
    public boolean hasStarted() {
        return this != DRAFT && this != LOBBY;
    }
}
