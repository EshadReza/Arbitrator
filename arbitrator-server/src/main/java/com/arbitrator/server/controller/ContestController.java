/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ContestJoinRequest;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.service.ContestAccessService;
import com.arbitrator.server.service.ContestService;

@RestController
public class ContestController {

    private final ContestService contestService;
    private final ContestAccessService contestAccess;
    private final ProblemRepository problems;

    public ContestController(ContestService contestService,
                             ContestAccessService contestAccess,
                             ProblemRepository problems) {
        this.contestService = contestService;
        this.contestAccess = contestAccess;
        this.problems = problems;
    }

    /** Contests a student may enter, for the client's contest picker. */
    @GetMapping(ApiPaths.CONTESTS)
    public List<ContestSummaryDto> joinable() {
        return contestService.joinable().stream().map(this::summarise).toList();
    }

    /** Convenience for a single-contest lab: whichever contest is live. */
    @GetMapping(ApiPaths.CONTEST_CURRENT)
    public ContestStateDto current(Principal principal) {
        Contest contest = contestService.requireCurrent();
        contestAccess.requireAccess(contest.getId(), principal.getName());
        return contestService.stateOf(contest);
    }

    @GetMapping(ApiPaths.CONTEST_BY_ID)
    public ContestStateDto byId(@PathVariable long id, Principal principal) {
        return contestService.stateOf(contestAccess.requireAccess(id, principal.getName()));
    }

    /** Validate the password once and persist an opaque user/contest grant. */
    @PostMapping(ApiPaths.CONTEST_JOIN)
    public ContestStateDto join(@PathVariable long id,
                                @RequestBody(required = false) ContestJoinRequest request,
                                Principal principal) {
        String password = request == null ? null : request.password();
        return contestService.stateOf(contestAccess.join(id, principal.getName(), password));
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
                c.getState().isJoinable(),
                c.hasPassword());
    }
}
