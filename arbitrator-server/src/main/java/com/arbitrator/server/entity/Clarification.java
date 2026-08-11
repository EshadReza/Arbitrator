package com.arbitrator.server.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A contestant's question and the instructor's answer. Public once asked;
 * {@code userId} is recorded but only ever disclosed to admins.
 */
@Entity
@Table(name = "clarifications")
public class Clarification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    /** Null when the question is about the contest rather than one problem. */
    @Column(name = "problem_id")
    private Long problemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String question;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String answer;

    @Column(name = "asked_at", nullable = false)
    private Instant askedAt = Instant.now();

    @Column(name = "answered_at")
    private Instant answeredAt;

    /** The asker's own choice at ask-time. Default true — see V60. */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = true;

    /**
     * An admin's explicit sign-off that a public question+answer may be shown
     * to everyone, not just the asker — see V64. Irrelevant for a private
     * clarification (already only visible to the asker and admins regardless).
     */
    @Column(nullable = false)
    private boolean approved = false;

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
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

    public Long getProblemId() {
        return problemId;
    }

    public void setProblemId(Long problemId) {
        this.problemId = problemId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Instant getAskedAt() {
        return askedAt;
    }

    public void setAskedAt(Instant askedAt) {
        this.askedAt = askedAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(Instant answeredAt) {
        this.answeredAt = answeredAt;
    }
}
