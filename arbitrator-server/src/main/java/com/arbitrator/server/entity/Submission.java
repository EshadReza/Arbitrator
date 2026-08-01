package com.arbitrator.server.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * FROZEN after Sprint 1 (WORKFLOW_PLAN §3): Eshad owns the file, Mahir's judge
 * worker writes verdict/exec/memory/compilerOutput/failedTestIndex/judgedAt.
 * Every field the judge needs is already here — changes need a contract-change
 * issue.
 */
@Entity
@Table(name = "submissions")
public class Submission {

    public enum Status { PENDING, JUDGING, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)   // plain VARCHAR, not MySQL's native ENUM(...)
    @Column(nullable = false, length = 16)
    private Language language;

    @Column(name = "source_code", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String sourceCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)   // plain VARCHAR, not MySQL's native ENUM(...)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)   // plain VARCHAR, not MySQL's native ENUM(...)
    @Column(length = 8)
    private Verdict verdict;

    @Column(name = "exec_time_ms", nullable = false)
    private long execTimeMs = -1;

    @Column(name = "peak_memory_kb", nullable = false)
    private long peakMemoryKb = -1;

    /** Truncated to 4096 chars before persisting (FR-20). */
    @Column(name = "compiler_output", columnDefinition = "TEXT")
    private String compilerOutput;

    @Column(name = "failed_test_index", nullable = false)
    private int failedTestIndex = -1;

    /** Originating workstation, for the academic-integrity log (NFR-C02). */
    @Column(name = "workstation_ip", length = 45)
    private String workstationIp;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt = Instant.now();

    @Column(name = "judged_at")
    private Instant judgedAt;

    /** Soft-delete flag; rows are never removed (DBR-04). */
    @Column(nullable = false)
    private boolean active = true;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProblemId() {
        return problemId;
    }

    public void setProblemId(Long problemId) {
        this.problemId = problemId;
    }

    public Long getContestId() {
        return contestId;
    }

    public void setContestId(Long contestId) {
        this.contestId = contestId;
    }

    public Language getLanguage() {
        return language;
    }

    public void setLanguage(Language language) {
        this.language = language;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public void setVerdict(Verdict verdict) {
        this.verdict = verdict;
    }

    public long getExecTimeMs() {
        return execTimeMs;
    }

    public void setExecTimeMs(long execTimeMs) {
        this.execTimeMs = execTimeMs;
    }

    public long getPeakMemoryKb() {
        return peakMemoryKb;
    }

    public void setPeakMemoryKb(long peakMemoryKb) {
        this.peakMemoryKb = peakMemoryKb;
    }

    public String getCompilerOutput() {
        return compilerOutput;
    }

    public void setCompilerOutput(String compilerOutput) {
        this.compilerOutput = compilerOutput;
    }

    public int getFailedTestIndex() {
        return failedTestIndex;
    }

    public void setFailedTestIndex(int failedTestIndex) {
        this.failedTestIndex = failedTestIndex;
    }

    public String getWorkstationIp() {
        return workstationIp;
    }

    public void setWorkstationIp(String workstationIp) {
        this.workstationIp = workstationIp;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(Instant queuedAt) {
        this.queuedAt = queuedAt;
    }

    public Instant getJudgedAt() {
        return judgedAt;
    }

    public void setJudgedAt(Instant judgedAt) {
        this.judgedAt = judgedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
