package com.arbitrator.common.enums;

/** Contest lifecycle per FR-08 / FR-19. */
public enum ContestState {

    /** Created but not started; invisible to students. */
    DRAFT,

    /** Running and accepting submissions. */
    ACTIVE,

    /** Clock stopped, submissions refused, resumable without losing time. */
    PAUSED,

    /** Running, but the public standings are held at the freeze point (FR-19). */
    FROZEN,

    /** Finished; submissions permanently refused (BR-02). */
    ENDED;

    /** States a student may join and work in. */
    public boolean isJoinable() {
        return this == ACTIVE || this == PAUSED || this == FROZEN;
    }

    /** Only these accept new submissions. */
    public boolean acceptsSubmissions() {
        return this == ACTIVE || this == FROZEN;
    }
}
