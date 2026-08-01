package com.labjudge.server.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.labjudge.common.api.ApiPaths;
import com.labjudge.common.dto.ContestStateDto;
import com.labjudge.server.service.ContestService;

/**
 * Admin contest lifecycle (FR-04, FR-08). Reached only from loopback
 * (LoopbackAdminFilter) with an ADMIN JWT (SecurityConfig) — decision D3.
 */
@RestController
public class AdminContestController {

    private final ContestService contestService;

    public AdminContestController(ContestService contestService) {
        this.contestService = contestService;
    }

    @PostMapping(ApiPaths.ADMIN_CONTESTS)
    public ContestStateDto create(@RequestParam String title,
                                  @RequestParam(defaultValue = "120") int durationMinutes) {
        contestService.create(title, durationMinutes);
        return contestService.currentState();
    }

    @PostMapping(ApiPaths.ADMIN_CONTEST_START)
    public ContestStateDto start(@PathVariable long id) {
        contestService.start(id);
        return contestService.currentState();
    }

    @PostMapping(ApiPaths.ADMIN_CONTEST_END)
    public ContestStateDto end(@PathVariable long id) {
        contestService.end(id);
        return contestService.currentState();
    }
}
