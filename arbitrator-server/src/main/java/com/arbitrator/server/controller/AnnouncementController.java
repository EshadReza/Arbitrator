package com.arbitrator.server.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.AnnouncementDto;
import com.arbitrator.server.service.AnnouncementService;
import com.arbitrator.server.service.ContestService;

/**
 * FR-07, contestant side: read the announcements for a contest.
 * Owner: Mahir (rules.md Rule 1 — controller/announcement).
 */
@RestController
public class AnnouncementController {

    private final AnnouncementService announcements;
    private final ContestService contestService;

    public AnnouncementController(AnnouncementService announcements,
                                  ContestService contestService) {
        this.announcements = announcements;
        this.contestService = contestService;
    }

    /**
     * @param contestId which contest to read; defaults to the current one so a
     *                  single-contest lab needs no parameter.
     */
    @GetMapping(ApiPaths.ANNOUNCEMENTS)
    public List<AnnouncementDto> list(
            @RequestParam(value = "contestId", required = false) Long contestId) {
        long target = contestId != null ? contestId : contestService.requireCurrent().getId();
        return announcements.forContest(target);
    }
}
