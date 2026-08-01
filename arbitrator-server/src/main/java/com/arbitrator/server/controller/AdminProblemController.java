package com.arbitrator.server.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ProblemPackageResultDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.service.ContestService;
import com.arbitrator.server.service.ProblemPackageService;

/**
 * Admin problem management (FR-05, UIF-21). Loopback + ADMIN JWT (decision D3).
 */
@RestController
public class AdminProblemController {

    private final ProblemPackageService packageService;
    private final ProblemRepository problems;
    private final TestCaseRepository testCases;
    private final SubmissionRepository submissions;
    private final ContestService contestService;

    public AdminProblemController(ProblemPackageService packageService,
                                  ProblemRepository problems,
                                  TestCaseRepository testCases,
                                  SubmissionRepository submissions,
                                  ContestService contestService) {
        this.packageService = packageService;
        this.problems = problems;
        this.testCases = testCases;
        this.submissions = submissions;
        this.contestService = contestService;
    }

    /**
     * FR-05. Returns 201 with the imported problem, or 422 with the list of
     * validation failures — the package is never partially imported.
     */
    @PostMapping(ApiPaths.ADMIN_PROBLEM_UPLOAD)
    public ResponseEntity<ProblemPackageResultDto> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload");
        }
        ProblemPackageResultDto result = packageService.importPackage(bytes);
        return ResponseEntity
                .status(result.accepted() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(result);
    }

    /** Problems in the current contest, for the admin Problems page. */
    @GetMapping(ApiPaths.ADMIN_PROBLEMS)
    public List<ProblemSummaryDto> list() {
        long contestId = contestService.requireCurrent().getId();
        return problems.findByContestIdOrderByOrderingAscCodeAsc(contestId).stream()
                .map(p -> new ProblemSummaryDto(p.getId(), p.getCode(), p.getTitle(), false, 0))
                .toList();
    }

    /**
     * UIF-22 destructive action. Refused once anyone has submitted to the
     * problem: submissions are never destroyed (DBR-04) and orphaning them
     * would corrupt contest history and the eventual leaderboard.
     */
    @DeleteMapping(ApiPaths.ADMIN_PROBLEM_BY_ID)
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable long id) {
        Problem problem = problems.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));

        long submissionCount = submissions.countByProblemId(id);
        if (submissionCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete \"" + problem.getCode() + "\": it already has "
                            + submissionCount + " submission(s). Submissions are never deleted.");
        }
        testCases.findByProblemIdOrderByIdxAsc(id).forEach(testCases::delete);
        problems.delete(problem);
        return ResponseEntity.noContent().build();
    }
}
