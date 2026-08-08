package com.arbitrator.server.entity;

import java.time.Duration;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.arbitrator.common.enums.ContestState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "contests")
public class Contest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)   // plain VARCHAR, not MySQL's native ENUM(...)
    @Column(nullable = false, length = 16)
    private ContestState state = ContestState.DRAFT;

    @Column(name = "start_time")
    private Instant startTime;

    /** Set while a pause is in progress; null otherwise. */
    @Column(name = "paused_at")
    private Instant pausedAt;

    /** Total milliseconds of completed pauses. */
    @Column(name = "paused_millis", nullable = false)
    private long pausedMillis;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 120;

    /**
     * End instant on the server clock (FR-06), shifted forward by all paused
     * time — pausing has to actually give the time back, otherwise a pause
     * silently costs contestants their remaining minutes.
     *
     * An instructor who ends a contest early ends it *now*: endedAt wins over
     * the schedule. Without that, every clock derived from this kept counting
     * down to the original deadline after the contest was already over.
     */
    public Instant endTime() {
        if (endedAt != null) {
            return endedAt;
        }
        if (startTime == null) {
            return null;
        }
        return startTime
                .plusSeconds(durationMinutes * 60L)
                .plusMillis(totalPausedMillis());
    }

    /** Completed pauses plus any pause currently running. */
    public long totalPausedMillis() {
        long inProgress = pausedAt == null
                ? 0
                : Duration.between(pausedAt, Instant.now()).toMillis();
        return pausedMillis + Math.max(0, inProgress);
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public long getPausedMillis() {
        return pausedMillis;
    }

    public void setPausedMillis(long pausedMillis) {
        this.pausedMillis = pausedMillis;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ContestState getState() {
        return state;
    }

    public void setState(ContestState state) {
        this.state = state;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "scheduled_start_at")
    private Instant scheduledStartAt;

    /** Set when the contest actually ended; null while it has not. */
    @Column(name = "ended_at")
    private Instant endedAt;

    /**
     * FR-12 adjunct: may contestants see the test data behind their own
     * verdicts? Off by default — showing tests changes what the contest
     * measures, so it is the instructor's deliberate choice, per contest.
     */
    @Column(name = "show_test_cases", nullable = false)
    private boolean showTestCases = false;

    /**
     * Per-contest kill switch (V61): may an asker mark a clarification
     * private, or does every question in this contest go to the public
     * board? On by default — the option existed unconditionally before this
     * toggle did, so an untouched contest keeps behaving the way it always
     * has. Enforced in ClarificationService.ask(), never trusted from the
     * client's own checkbox state.
     */
    @Column(name = "allow_private_clarifications", nullable = false)
    private boolean allowPrivateClarifications = true;

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public boolean isShowTestCases() {
        return showTestCases;
    }

    public void setShowTestCases(boolean showTestCases) {
        this.showTestCases = showTestCases;
    }

    public boolean isAllowPrivateClarifications() {
        return allowPrivateClarifications;
    }

    public void setAllowPrivateClarifications(boolean allowPrivateClarifications) {
        this.allowPrivateClarifications = allowPrivateClarifications;
    }

    /**
     * Bcrypt hash of the join password, or null when this contest needs
     * none (the default — most contests in a closed LAN lab don't need one).
     * Never the raw password; verified through the same PasswordEncoder bean
     * user auth already uses.
     */
    @Column(name = "password_hash")
    private String passwordHash;

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(Instant frozenAt) {
        this.frozenAt = frozenAt;
    }

    public Instant getScheduledStartAt() {
        return scheduledStartAt;
    }

    public void setScheduledStartAt(Instant scheduledStartAt) {
        this.scheduledStartAt = scheduledStartAt;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }
}
