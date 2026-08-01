package com.arbitrator.server.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.service.ContestService;

@RestController
public class ContestController {

    private final ContestService contestService;
    private final ProblemRepository problems;

    public ContestController(ContestService contestService, ProblemRepository problems) {
        this.contestService = contestService;
        this.problems = problems;
    }

    /** Contests a student may enter, for the client's contest picker. */
    @GetMapping(ApiPaths.CONTESTS)
    public List<ContestSummaryDto> joinable() {
        return contestService.joinable().stream().map(this::summarise).toList();
    }

    /** Convenience for a single-contest lab: whichever contest is live. */
    @GetMapping(ApiPaths.CONTEST_CURRENT)
    public ContestStateDto current() {
        return contestService.currentState();
    }

    /** The chosen contest's live state — drives the client countdown (FR-06). */
    @GetMapping(ApiPaths.CONTEST_BY_ID)
    public ContestStateDto byId(@PathVariable long id) {
        return contestService.stateOf(contestService.require(id));
    }

    private ContestSummaryDto summarise(Contest c) {
        var start = c.getStartTime();
        var end = c.endTime();
        return new ContestSummaryDto(
                c.getId(),
                c.getTitle(),
                c.getState(),
                start == null ? -1 : start.toEpochMilli(),
                end == null ? -1 : end.toEpochMilli(),
                c.getDurationMinutes(),
                problems.findByContestIdOrderByOrderingAscCodeAsc(c.getId()).size(),
                c.getState().isJoinable());
    }
}
