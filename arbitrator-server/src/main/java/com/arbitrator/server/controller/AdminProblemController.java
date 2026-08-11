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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemPackageResultDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.common.dto.ProblemTestCasesDto;
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

    /**
     * The full problem, for the console's editor. The list endpoint carries
     * only code and title; editing needs the statement itself.
     */
    @GetMapping(ApiPaths.ADMIN_PROBLEM_BY_ID)
    public ProblemDetailDto detail(@PathVariable long id) {
        Problem p = problems.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));
        return new ProblemDetailDto(p.getId(), p.getCode(), p.getTitle(),
                p.getStatementHtml(), p.getTimeLimitMs(), p.getMemoryLimitKb(),
                p.isStatementIsPdf());
    }

    /**
     * Read-only: statement + every stored test case (item 6) — Edit only ever
     * showed the statement, and there was previously no way to see a
     * problem's test data from the console at all short of the per-submission
     * view, which only shows the tests one particular run happened to reach.
     */
    @GetMapping(ApiPaths.ADMIN_PROBLEM_TESTCASES)
    public ProblemTestCasesDto viewTestCases(@PathVariable long id) {
        Problem p = problems.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));
        List<ProblemTestCasesDto.Entry> entries = testCases.findByProblemIdOrderByIdxAsc(id).stream()
                .map(tc -> new ProblemTestCasesDto.Entry(tc.getIdx(), tc.getInputData(), tc.getExpectedOutput()))
                .toList();
        return new ProblemTestCasesDto(p.getId(), p.getCode(), p.getTitle(), p.getStatementHtml(), entries);
    }

    /**
     * Edit a problem in place (FR-05 follow-up): a typo in a statement, or a
     * clarification mid-contest, previously meant deleting the problem and
     * re-uploading the whole package — which also destroyed every submission
     * against it. Test data is untouched here; only the wording and limits.
     *
     * Clients are told immediately, so a correction reaches the room without
     * anyone signing out.
     */
    @PutMapping(ApiPaths.ADMIN_PROBLEM_BY_ID)
    @Transactional
    public ProblemDetailDto update(@PathVariable long id,
                                   @RequestBody ProblemEditRequest edit) {
        Problem p = problems.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));

        if (edit.title() != null && !edit.title().isBlank()) {
            p.setTitle(edit.title().trim());
        }
        if (edit.statementHtml() != null && !edit.statementHtml().isBlank()) {
            p.setStatementHtml(edit.statementHtml());
        }
        // Limits are optional in the payload; 0 or negative means "leave alone"
        // rather than "set to zero", which would make the problem unjudgeable.
        if (edit.timeLimitMs() != null && edit.timeLimitMs() > 0) {
            p.setTimeLimitMs(edit.timeLimitMs());
        }
        if (edit.memoryLimitKb() != null && edit.memoryLimitKb() > 0) {
            p.setMemoryLimitKb(edit.memoryLimitKb());
        }
        problems.save(p);
        notifyContestChanged();
        return new ProblemDetailDto(p.getId(), p.getCode(), p.getTitle(),
                p.getStatementHtml(), p.getTimeLimitMs(), p.getMemoryLimitKb(),
                p.isStatementIsPdf());
    }

    /** Every field optional: the console sends only what the instructor changed. */
    public record ProblemEditRequest(String title, String statementHtml,
                                     Integer timeLimitMs, Integer memoryLimitKb) {
    }

    /**
     * Re-uploads just the PDF behind an existing problem — a correction to the
     * statement no longer means deleting the problem and losing every
     * submission against it, same reasoning as {@link #update}.
     */
    @PostMapping(ApiPaths.ADMIN_PROBLEM_STATEMENT_PDF)
    public ProblemDetailDto replaceStatementPdf(@PathVariable long id,
                                                @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload");
        }
        packageService.replaceStatementPdf(id, bytes);
        notifyContestChanged();
        return detail(id);
    }

    /** Drag-to-reorder in the console; codes are re-lettered to match. */
    @PostMapping(ApiPaths.ADMIN_PROBLEM_REORDER)
    public List<ProblemSummaryDto> reorder(@PathVariable long id, @RequestBody List<Long> problemIds) {
        List<Problem> updated = packageService.reorder(id, problemIds);
        notifyContestChanged();
        return updated.stream()
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
        // V59's fk_statement_pdf_problem: a PDF-statement problem otherwise
        // fails this whole delete with a 500 (FK violation), latent since V59
        // and only just started showing up once PDF problems became common.
        jdbcTemplate.update("DELETE FROM problem_statement_pdfs WHERE problem_id = ?", id);
        testCases.deleteAll(testCases.findByProblemIdOrderByIdxAsc(id));
        problems.delete(problem);
        notifyContestChanged();
        return ResponseEntity.noContent().build();
    }
}
