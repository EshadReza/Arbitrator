package com.arbitrator.server.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.common.enums.Verdict;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.Submission;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.SubmissionRepository;

@Service
public class ProblemService {

    private final ProblemRepository problems;
    private final SubmissionRepository submissions;

    public ProblemService(ProblemRepository problems, SubmissionRepository submissions) {
        this.problems = problems;
        this.submissions = submissions;
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
                p.getStatementHtml(), p.getTimeLimitMs(), p.getMemoryLimitKb());
    }

    public Problem require(long problemId) {
        return problems.findById(problemId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem unavailable"));
    }
}
