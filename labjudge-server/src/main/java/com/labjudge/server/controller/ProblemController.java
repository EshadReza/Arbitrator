package com.labjudge.server.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.labjudge.common.api.ApiPaths;
import com.labjudge.common.dto.ProblemDetailDto;
import com.labjudge.common.dto.ProblemSummaryDto;
import com.labjudge.server.service.ContestService;
import com.labjudge.server.service.ProblemService;
import com.labjudge.server.service.UserService;

@RestController
public class ProblemController {

    private final ProblemService problemService;
    private final ContestService contestService;
    private final UserService userService;

    public ProblemController(ProblemService problemService,
                             ContestService contestService,
                             UserService userService) {
        this.problemService = problemService;
        this.contestService = contestService;
        this.userService = userService;
    }

    @GetMapping(ApiPaths.PROBLEMS)
    public List<ProblemSummaryDto> list(Principal principal) {
        long userId = userService.requireByUsername(principal.getName()).getId();
        long contestId = contestService.requireCurrent().getId();
        return problemService.listForContest(contestId, userId);
    }

    @GetMapping(ApiPaths.PROBLEM_BY_ID)
    public ProblemDetailDto detail(@PathVariable long id) {
        return problemService.detail(id);
    }
}
