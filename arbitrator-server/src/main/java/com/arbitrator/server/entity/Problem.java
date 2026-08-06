package com.arbitrator.server.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.arbitrator.common.enums.CheckerType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "problems")
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    @Column(nullable = false, length = 8)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "statement_html", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String statementHtml;

    @Column(name = "time_limit_ms", nullable = false)
    private int timeLimitMs = 2000;

    @Column(name = "memory_limit_kb", nullable = false)
    private int memoryLimitKb = 262144;

    @Column(nullable = false)
    private int ordering = 0;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "checker_type", nullable = false, length = 16)
    private CheckerType checkerType = CheckerType.EXACT;

    @Column(name = "checker_source", columnDefinition = "MEDIUMTEXT")
    private String checkerSource;

    /**
     * True when the real statement is a PDF held in problem_statement_pdfs and
     * statementHtml is only a placeholder. The bytes deliberately do not live
     * on this entity — see V59.
     */
    @Column(name = "statement_is_pdf", nullable = false)
    private boolean statementIsPdf = false;

    public boolean isStatementIsPdf() {
        return statementIsPdf;
    }

    public void setStatementIsPdf(boolean statementIsPdf) {
        this.statementIsPdf = statementIsPdf;
    }

    public Long getId() {
        return id;
    }

    public Long getContestId() {
        return contestId;
    }

    public void setContestId(Long contestId) {
        this.contestId = contestId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatementHtml() {
        return statementHtml;
    }

    public void setStatementHtml(String statementHtml) {
        this.statementHtml = statementHtml;
    }

    public int getTimeLimitMs() {
        return timeLimitMs;
    }

    public void setTimeLimitMs(int timeLimitMs) {
        this.timeLimitMs = timeLimitMs;
    }

    public int getMemoryLimitKb() {
        return memoryLimitKb;
    }

    public void setMemoryLimitKb(int memoryLimitKb) {
        this.memoryLimitKb = memoryLimitKb;
    }

    public int getOrdering() {
        return ordering;
    }

    public void setOrdering(int ordering) {
        this.ordering = ordering;
    }

    public CheckerType getCheckerType() {
        return checkerType;
    }

    public void setCheckerType(CheckerType checkerType) {
        this.checkerType = checkerType;
    }

    public String getCheckerSource() {
        return checkerSource;
    }

    public void setCheckerSource(String checkerSource) {
        this.checkerSource = checkerSource;
    }
}

