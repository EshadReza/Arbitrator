package com.arbitrator.common.dto;

import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;

/**
 * One attempt on one problem, for the standings box drill-down (FR-17
 * sibling) — newest first, same as the admin console's version of this same
 * view.
 *
 * Deliberately excludes sourceCode/compilerOutput: this is served to ANY
 * contestant in the room, not just the submission's own owner (LRR-02 still
 * gates the code itself — see SUBMISSION_SOURCE — just not this metadata,
 * since a standings box is public by nature). Leaving those fields out of the
 * DTO makes leaking them structurally impossible rather than relying on
 * every call site remembering not to render them.
 */
public record AttemptSummaryDto(
        long submissionId,
        Language language,
        Verdict verdict,        // null while still queued/judging
        long execTimeMs,
        long submittedAtMs,
        int failedTestIndex,    // -1 when not applicable (AC, or still judging)
        int passedTestCount,
        int totalTestCases
) {
}
