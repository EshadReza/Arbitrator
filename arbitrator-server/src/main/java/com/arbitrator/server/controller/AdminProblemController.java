package com.arbitrator.server.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.arbitrator.server.realtime.ContestStatePublisher;
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
    private final ContestStatePublisher statePublisher;
    private final JdbcTemplate jdbcTemplate;

    public AdminProblemController(ProblemPackageService packageService,
                                  ProblemRepository problems,
                                  TestCaseRepository testCases,
                                  SubmissionRepository submissions,
                                  ContestService contestService,
                                  ContestStatePublisher statePublisher,
                                  JdbcTemplate jdbcTemplate) {
        this.packageService = packageService;
        this.problems = problems;
        this.testCases = testCases;
        this.submissions = submissions;
        this.contestService = contestService;
        this.statePublisher = statePublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Re-pushes contest state so clients re-fetch the problem list. */
    private void notifyContestChanged() {
        try {
            statePublisher.publish(contestService.requireCurrent());
        } catch (RuntimeException ignored) {
            // no current contest; nothing is listening anyway
        }
    }

    @PostMapping(ApiPaths.ADMIN_PROBLEM_UPLOAD)
    public ResponseEntity<ProblemPackageResultDto> upload(@RequestParam("file") MultipartFile file,
                                                          @RequestParam(value = "contestId", required = false) Long contestId) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload");
        }
        ProblemPackageResultDto result = packageService.importPackageForContest(bytes, contestId);
        if (result.accepted()) {
            notifyContestChanged();
        }
        return ResponseEntity
                .status(result.accepted() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(result);
    }

    /** Problems in the requested contest (or current contest). */
    @GetMapping(ApiPaths.ADMIN_PROBLEMS)
    public List<ProblemSummaryDto> list(@RequestParam(value = "contestId", required = false) Long contestId) {
        long targetId = (contestId != null) ? contestId : contestService.requireCurrent().getId();
        return problems.findByContestIdOrderByOrderingAscCodeAsc(targetId).stream()
                .map(p -> new ProblemSummaryDto(p.getId(), p.getCode(), p.getTitle(), false, 0))
                .toList();
    }

    @DeleteMapping(ApiPaths.ADMIN_PROBLEM_BY_ID)
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable long id) {
        Problem problem = problems.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));

        jdbcTemplate.update("DELETE FROM submission_results WHERE submission_id IN (SELECT id FROM submissions WHERE problem_id = ?)", id);
        jdbcTemplate.update("DELETE FROM submissions WHERE problem_id = ?", id);
        testCases.deleteAll(testCases.findByProblemIdOrderByIdxAsc(id));
        problems.delete(problem);
        notifyContestChanged();
        return ResponseEntity.noContent().build();
    }
}
