/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.arbitrator.server.entity.Announcement;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Newest first — an announcement board is read from the top. */
    List<Announcement> findByContestIdOrderByCreatedAtDesc(Long contestId);
}
