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
     */
    public Instant endTime() {
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

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }
}
