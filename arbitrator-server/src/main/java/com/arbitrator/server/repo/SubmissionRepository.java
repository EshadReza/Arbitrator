package com.arbitrator.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.arbitrator.server.entity.Submission;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    List<Submission> findByUserIdAndActiveTrueOrderByQueuedAtDesc(Long userId);

    /** Crash recovery (NFR-R02): everything not DONE is requeued on startup. */
    List<Submission> findByStatusNotOrderByQueuedAtAsc(Submission.Status status);

    /** Guards problem deletion — submissions are never destroyed (DBR-04). */
    long countByProblemId(Long problemId);

    /** Everything the leaderboard needs, oldest first (FR-17, FR-18). */
    List<Submission> findByContestIdAndActiveTrueOrderByQueuedAtAsc(Long contestId);
}
