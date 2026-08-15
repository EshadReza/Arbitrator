package com.arbitrator.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.arbitrator.server.entity.Material;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    /** Newest first — most recently posted material is what a student looks for. */
    List<Material> findByContestIdOrderByUploadedAtDesc(Long contestId);
}
