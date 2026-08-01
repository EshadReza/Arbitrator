package com.labjudge.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.labjudge.common.api.ApiPaths;
import com.labjudge.common.dto.ContestStateDto;
import com.labjudge.server.service.ContestService;

@RestController
public class ContestController {

    private final ContestService contestService;

    public ContestController(ContestService contestService) {
        this.contestService = contestService;
    }

    /** Client polls this once at login and derives its countdown (FR-06). */
    @GetMapping(ApiPaths.CONTEST_CURRENT)
    public ContestStateDto current() {
        return contestService.currentState();
    }
}
