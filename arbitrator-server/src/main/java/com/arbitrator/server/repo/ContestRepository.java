package com.arbitrator.server.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.arbitrator.common.enums.ContestState;
import com.arbitrator.server.entity.Contest;

public interface ContestRepository extends JpaRepository<Contest, Long> {

    Optional<Contest> findFirstByStateOrderByIdDesc(ContestState state);

    Optional<Contest> findFirstByStateNotOrderByIdDesc(ContestState state);

    /** A live contest (ACTIVE/FROZEN) takes precedence over a finished one. */
    Optional<Contest> findFirstByStateInOrderByIdDesc(Collection<ContestState> states);

    /** Used to close any other live contest when one is started. */
    List<Contest> findByStateIn(Collection<ContestState> states);
}
