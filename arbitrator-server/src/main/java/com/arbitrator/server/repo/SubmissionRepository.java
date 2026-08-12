package com.arbitrator.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

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
     * Queue-flooding guard: true while this user has a PENDING or JUDGING
     * submission already in the system. One infinite-loop program submitted
     * a few thousand times over would otherwise occupy a large share of the
     * shared judge pool for the rest of a contest — capping every user to
     * one in-flight submission at a time removes that attack entirely,
     * independent of the 10s-between-submissions cooldown (BR-01), which
     * alone still allows building up an arbitrarily deep personal backlog.
     */
    boolean existsByUserIdAndStatusNot(Long userId, Submission.Status status);

    /** Guards problem deletion — submissions are never destroyed (DBR-04). */
    long countByProblemId(Long problemId);

    /** Every submission of a contest, including already-inactive ones. */
    List<Submission> findByContestId(Long contestId);

    /** Everything the leaderboard needs, oldest first (FR-17, FR-18). */
    List<Submission> findByContestIdAndActiveTrueOrderByQueuedAtAsc(Long contestId);
}
