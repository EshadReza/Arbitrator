package com.arbitrator.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.arbitrator.server.entity.Material;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    /** Newest first — most recently posted material is what a student looks for. */
    List<Material> findByContestIdOrderByUploadedAtDesc(Long contestId);

    /** Execute immediately: the contest is deleted with JDBC in the same transaction. */
    @Modifying(flushAutomatically = true)
    @Query("delete from Material m where m.contestId = :contestId")
    void deleteForContest(@Param("contestId") long contestId);
}
