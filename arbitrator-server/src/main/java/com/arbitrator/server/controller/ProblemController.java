/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.server.service.ContestAccessService;
import com.arbitrator.server.service.ContestService;
import com.arbitrator.server.service.ProblemService;
import com.arbitrator.server.service.UserService;

@RestController
public class ProblemController {

    private final ProblemService problemService;
    private final ContestService contestService;
    private final ContestAccessService contestAccess;
    private final UserService userService;

    public ProblemController(ProblemService problemService,
                             ContestService contestService,
                             ContestAccessService contestAccess,
                             UserService userService) {
        this.problemService = problemService;
        this.contestService = contestService;
        this.contestAccess = contestAccess;
        this.userService = userService;
    }

    @GetMapping(ApiPaths.PROBLEMS)
    public List<ProblemSummaryDto> list(Principal principal) {
        long userId = userService.requireByUsername(principal.getName()).getId();
        var contest = contestService.requireCurrent();
        contestAccess.requireAccess(contest.getId(), principal.getName());
        return problemService.listForContest(contest.getId(), userId,
                contest.getState().releasesProblems());
    }

    @GetMapping(ApiPaths.PROBLEM_BY_ID)
    public ProblemDetailDto detail(@PathVariable long id, Principal principal) {
        var problem = problemService.require(id);
        contestAccess.requireReleasedAccess(problem.getContestId(), principal.getName());
        return problemService.detail(id);
    }

    /**
     * FR-05: the PDF statement itself. Served as bytes over the authenticated
     * REST channel rather than as a browsable URL, because the client renders
     * it into the statement pane and every other request already carries a JWT.
     */
    @GetMapping(value = ApiPaths.PROBLEM_STATEMENT_PDF, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> statementPdf(@PathVariable long id, Principal principal) {
        var problem = problemService.require(id);
        contestAccess.requireReleasedAccess(problem.getContestId(), principal.getName());
        byte[] pdf = problemService.statementPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }
}
