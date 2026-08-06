package com.arbitrator.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.arbitrator.server.entity.Clarification;

public interface ClarificationRepository extends JpaRepository<Clarification, Long> {

    /** Newest first, matching how the board is read on both sides. */
    List<Clarification> findByContestIdOrderByAskedAtDesc(Long contestId);
}
