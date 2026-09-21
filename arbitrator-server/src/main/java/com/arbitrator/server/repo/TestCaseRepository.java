/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.arbitrator.server.entity.TestCase;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    /** Ascending order is a business rule (BR-05), not a display choice. */
    List<TestCase> findByProblemIdOrderByIdxAsc(Long problemId);
}
