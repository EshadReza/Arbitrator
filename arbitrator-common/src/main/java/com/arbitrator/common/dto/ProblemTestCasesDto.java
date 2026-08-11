package com.arbitrator.common.dto;

import java.util.List;

/**
 * Admin-only read of a problem's full statement plus every stored test case
 * (item 6 — "View" alongside Edit/Delete). Unlike {@link TestCaseResultDto}
 * this is the raw test suite itself, not what one submission happened to
 * reach — the instructor may see all of it, always.
 */
public record ProblemTestCasesDto(
        long problemId,
        String code,
        String title,
        String statementHtml,
        List<Entry> testCases
) {
    public record Entry(int idx, String inputData, String expectedOutput) {
    }
}
