/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;
import com.arbitrator.server.security.RichTextSanitizer;

@Service
public class ProblemService {

    private final ProblemRepository problems;
    private final SubmissionRepository submissions;
    /** Only for the statement-PDF side table; everything else goes through JPA. */
    private final JdbcTemplate jdbc;

    public ProblemService(ProblemRepository problems, SubmissionRepository submissions,
                          JdbcTemplate jdbc) {
        this.problems = problems;
        this.submissions = submissions;
        this.jdbc = jdbc;
    }

    /**
     * Left-panel list with per-user badge state (SRS §4.2).
     *
     * Returns nothing while the contest is still in its lobby: releasing
     * statements before the clock starts would let people read and plan early.
     */
    public List<ProblemSummaryDto> listForContest(long contestId, long userId,
                                                  boolean released) {
        if (!released) {
            return List.of();
        }
        return listForContest(contestId, userId);
    }

    /** Left-panel list with per-user badge state (SRS §4.2). */
    public List<ProblemSummaryDto> listForContest(long contestId, long userId) {
        List<Submission> mine = submissions.findByUserIdAndActiveTrueOrderByQueuedAtDesc(userId);
        return problems.findByContestIdOrderByOrderingAscCodeAsc(contestId).stream()
                .map(p -> {
                    boolean solved = mine.stream().anyMatch(s ->
                            s.getProblemId().equals(p.getId()) && s.getVerdict() == Verdict.AC);
                    // CE does not count as a failed attempt (BR-04)
                    int failed = (int) mine.stream().filter(s ->
                            s.getProblemId().equals(p.getId())
                                    && s.getVerdict() != null
                                    && s.getVerdict() != Verdict.AC
                                    && s.getVerdict() != Verdict.CE).count();
                    return new ProblemSummaryDto(p.getId(), p.getCode(), p.getTitle(),
                            solved, failed);
                })
                .toList();
    }

    public ProblemDetailDto detail(long problemId) {
        Problem p = require(problemId);
        return new ProblemDetailDto(p.getId(), p.getCode(), p.getTitle(),
                RichTextSanitizer.sanitize(p.getStatementHtml()),
                p.getTimeLimitMs(), p.getMemoryLimitKb(),
                p.isStatementIsPdf());
    }

    /**
     * The PDF statement's bytes (FR-05). Read straight from the side table
     * rather than through the entity — see V59 for why they live apart.
     */
    public byte[] statementPdf(long problemId) {
        Problem p = require(problemId);
        if (!p.isStatementIsPdf()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "This problem's statement is not a PDF");
        }
        List<byte[]> rows = jdbc.query(
                "SELECT data FROM problem_statement_pdfs WHERE problem_id = ?",
                (rs, n) -> rs.getBytes("data"), problemId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The PDF statement is missing — re-upload the package");
        }
        return rows.get(0);
    }

    public Problem require(long problemId) {
        return problems.findById(problemId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem unavailable"));
    }
}
