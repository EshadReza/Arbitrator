package com.arbitrator.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.arbitrator.server.entity.Submission;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    List<Submission> findByUserIdAndActiveTrueOrderByQueuedAtDesc(Long userId);

    /**
     * FR-16 personal history, scoped to one contest — a clone's problems are
     * brand new rows, so this join can never pull in the source contest's
     * submissions, but without the contestId predicate at all (the bug this
     * replaces) "my submissions" meant literally every contest ever entered.
     */
    List<Submission> findByUserIdAndContestIdAndActiveTrueOrderByQueuedAtDesc(Long userId, Long contestId);

    /** Crash recovery (NFR-R02): everything not DONE is requeued on startup. */
    List<Submission> findByStatusNotOrderByQueuedAtAsc(Submission.Status status);

    /**
     * Queue-flooding guard: how many of this user's submissions are still
     * PENDING or JUDGING right now. SubmissionService trips a hysteresis
     * block once this reaches 20 and holds it until the count drops back
     * below 5 — see SubmissionService.QUEUE_BLOCK_AT's javadoc. Bounds
     * backlog DEPTH per user, independent of the 10s-between-submissions
     * cooldown (BR-01), which alone only bounds submission RATE and still
     * allows an arbitrarily deep personal backlog over a long contest.
     */
    long countByUserIdAndStatusNot(Long userId, Submission.Status status);

    /**
     * Duplicate-submission guard source data: every prior source code this
     * user has on record for this problem. {@code active} scoped — DOC-9
     * archives a contest's submissions on restart/clone, and a fresh run
     * must not treat a previous run's attempt as a duplicate.
     *
     * Whitespace-insensitive by design, so the comparison happens in
     * SubmissionService rather than as a SQL equality predicate here — see
     * SubmissionService.isDuplicate. A byte-for-byte SQL match let a single
     * deleted space count as "new" code, which defeats the guard's whole
     * point (the verdict cannot change from whitespace alone).
     */
    @Query("select s.sourceCode from Submission s "
            + "where s.userId = ?1 and s.problemId = ?2 and s.active = true")
    List<String> findSourceCodesByUserIdAndProblemIdAndActiveTrue(Long userId, Long problemId);

    /** Guards problem deletion — submissions are never destroyed (DBR-04). */
    long countByProblemId(Long problemId);

    /** Every submission of a contest, including already-inactive ones. */
    List<Submission> findByContestId(Long contestId);

    /** Everything the leaderboard needs, oldest first (FR-17, FR-18). */
    List<Submission> findByContestIdAndActiveTrueOrderByQueuedAtAsc(Long contestId);
}
