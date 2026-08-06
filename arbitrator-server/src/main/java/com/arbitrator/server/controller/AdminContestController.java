package com.arbitrator.server.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.realtime.ContestStatePublisher;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.service.ClarificationService;
import com.arbitrator.server.service.ContestService;

/**
 * Contest lifecycle for instructors (FR-04, FR-06, FR-08, FR-19).
 * Reached only from loopback (LoopbackAdminFilter) with an ADMIN JWT — D3.
 */
@RestController
public class AdminContestController {

    private final ContestService contestService;
    private final ProblemRepository problems;
    private final ContestStatePublisher statePublisher;
    private final SubmissionRepository submissions;
    private final TestCaseRepository testCases;
    private final JdbcTemplate jdbcTemplate;
    private final ClarificationService clarifications;

    public AdminContestController(ContestService contestService,
                                  ProblemRepository problems,
                                  ContestStatePublisher statePublisher,
                                  SubmissionRepository submissions,
                                  TestCaseRepository testCases,
                                  JdbcTemplate jdbcTemplate,
                                  ClarificationService clarifications) {
        this.contestService = contestService;
        this.problems = problems;
        this.statePublisher = statePublisher;
        this.submissions = submissions;
        this.testCases = testCases;
        this.jdbcTemplate = jdbcTemplate;
        this.clarifications = clarifications;
    }

    /** Every lifecycle action pushes the new state so clients react at once. */
    private ContestStateDto applied(Contest c) {
        statePublisher.publish(c);
        return contestService.stateOf(c);
    }

    /** Every contest, whatever its state — the admin needs drafts too. */
    @GetMapping(ApiPaths.ADMIN_CONTESTS)
    public List<ContestSummaryDto> list() {
        return contestService.all().stream().map(this::summarise).toList();
    }

    @PostMapping(ApiPaths.ADMIN_CONTESTS)
    public ContestSummaryDto create(@RequestParam String title,
                                    @RequestParam(defaultValue = "120") int durationMinutes) {
        return summarise(contestService.create(title, durationMinutes));
    }

    /** Doors open, clock not running — students wait in the lobby. */
    @PostMapping(ApiPaths.ADMIN_CONTEST_OPEN)
    public ContestStateDto open(@PathVariable long id) {
        return applied(contestService.openLobby(id));
    }

    @PostMapping(ApiPaths.ADMIN_CONTEST_START)
    public ContestStateDto start(@PathVariable long id) {
        return applied(contestService.start(id));
    }

    /** Stops the clock; remaining time is preserved (FR-06). */
    @PostMapping(ApiPaths.ADMIN_CONTEST_PAUSE)
    public ContestStateDto pause(@PathVariable long id) {
        return applied(contestService.pause(id));
    }

    @PostMapping(ApiPaths.ADMIN_CONTEST_RESUME)
    public ContestStateDto resume(@PathVariable long id) {
        return applied(contestService.resume(id));
    }

    /** Add or subtract minutes from the deadline mid-contest. */
    @PostMapping(ApiPaths.ADMIN_CONTEST_EXTEND)
    public ContestStateDto extend(@PathVariable long id, @RequestParam int minutes) {
        return applied(contestService.extend(id, minutes));
    }

    /** FR-19: hold public standings while judging continues. */
    @PostMapping(ApiPaths.ADMIN_CONTEST_FREEZE)
    public ContestStateDto freeze(@PathVariable long id) {
        return applied(contestService.freeze(id));
    }

    @PostMapping(ApiPaths.ADMIN_CONTEST_UNFREEZE)
    public ContestStateDto unfreeze(@PathVariable long id) {
        return applied(contestService.unfreeze(id));
    }

    /**
     * Whether contestants may see the tests behind their own verdicts.
     *
     * Kept off the contest DTOs deliberately: those are a frozen contract
     * (rules.md Rule 2) and this is one boolean only the console needs.
     */
    @GetMapping(ApiPaths.ADMIN_CONTEST_TEST_VISIBILITY)
    public Map<String, Boolean> testVisibility(@PathVariable long id) {
        return Map.of("visible", contestService.require(id).isShowTestCases());
    }

    @PostMapping(ApiPaths.ADMIN_CONTEST_TEST_VISIBILITY)
    public Map<String, Boolean> setTestVisibility(@PathVariable long id,
                                                 @RequestParam boolean visible) {
        return Map.of("visible",
                contestService.setTestCaseVisibility(id, visible).isShowTestCases());
    }

    /** Owner: Mahir — whether askers may mark a clarification private (V61). */
    @GetMapping(ApiPaths.ADMIN_CONTEST_CLARIFICATION_PRIVACY)
    public Map<String, Boolean> clarificationPrivacy(@PathVariable long id) {
        return Map.of("allowed", contestService.require(id).isAllowPrivateClarifications());
    }

    @PostMapping(ApiPaths.ADMIN_CONTEST_CLARIFICATION_PRIVACY)
    public Map<String, Boolean> setClarificationPrivacy(@PathVariable long id,
                                                        @RequestParam boolean allowed) {
        boolean result = contestService.setAllowPrivateClarifications(id, allowed)
                .isAllowPrivateClarifications();
        // Otherwise a connected client only learns about this the next time it
        // happens to refresh on its own (asking a question, or restarting) —
        // this piggybacks the same push the clarification board already uses.
        clarifications.notifyBoardChanged(id);
        return Map.of("allowed", result);
    }

    /** BR-02: submissions are refused from this moment on. */
    @PostMapping(ApiPaths.ADMIN_CONTEST_END)
    public ContestStateDto end(@PathVariable long id) {
        return applied(contestService.end(id));
    }

    /** Destructive action: deletes contest and all associated submissions, submission_results, problems, and testcases. */
    @DeleteMapping(ApiPaths.ADMIN_CONTEST_BY_ID)
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable long id) {
        jdbcTemplate.update("DELETE FROM submission_results WHERE submission_id IN (SELECT id FROM submissions WHERE contest_id = ?)", id);
        jdbcTemplate.update("DELETE FROM submissions WHERE contest_id = ?", id);
        jdbcTemplate.update("DELETE FROM test_cases WHERE problem_id IN (SELECT id FROM problems WHERE contest_id = ?)", id);
        // Same fk_statement_pdf_problem gap as AdminProblemController.delete().
        jdbcTemplate.update("DELETE FROM problem_statement_pdfs WHERE problem_id IN (SELECT id FROM problems WHERE contest_id = ?)", id);
        jdbcTemplate.update("DELETE FROM problems WHERE contest_id = ?", id);
        jdbcTemplate.update("DELETE FROM contests WHERE id = ?", id);
        return ResponseEntity.noContent().build();
    }

    private ContestSummaryDto summarise(Contest c) {
        var start = c.getStartTime();
        var end = c.endTime();
        return new ContestSummaryDto(
                c.getId(), c.getTitle(), c.getState(),
                start == null ? -1 : start.toEpochMilli(),
                end == null ? -1 : end.toEpochMilli(),
                c.getDurationMinutes(),
                problems.findByContestIdOrderByOrderingAscCodeAsc(c.getId()).size(),
                c.getState().isJoinable());
    }
}
