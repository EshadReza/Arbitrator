package com.labjudge.server.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.labjudge.common.enums.ContestState;
import com.labjudge.server.entity.Contest;

public interface ContestRepository extends JpaRepository<Contest, Long> {

    /** Bundle 1 assumption: at most one non-DRAFT contest at a time. */
    Optional<Contest> findFirstByStateOrderByIdDesc(ContestState state);

    Optional<Contest> findFirstByStateNotOrderByIdDesc(ContestState state);
}
