package com.labjudge.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.labjudge.server.entity.TestCase;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    /** Ascending order is a business rule (BR-05), not a display choice. */
    List<TestCase> findByProblemIdOrderByIdxAsc(Long problemId);
}
