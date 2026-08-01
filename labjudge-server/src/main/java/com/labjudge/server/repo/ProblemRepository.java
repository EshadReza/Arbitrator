package com.labjudge.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.labjudge.server.entity.Problem;

public interface ProblemRepository extends JpaRepository<Problem, Long> {

    List<Problem> findByContestIdOrderByOrderingAscCodeAsc(Long contestId);
}
