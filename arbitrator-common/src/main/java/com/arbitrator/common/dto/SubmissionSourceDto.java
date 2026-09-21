/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;

/**
 * A submission with its source code (UIF-12: "clicking a row shall load the
 * submitted source into a read-only editor pane").
 *
 * Kept separate from {@link SubmissionHistoryDto} on purpose: history is polled
 * often and carrying every source blob would bloat each response. LRR-02 means
 * a student may only fetch their own; admins may fetch any.
 */
public record SubmissionSourceDto(
        long id,
        String problemCode,
        String username,
        Language language,
        Verdict verdict,
        String sourceCode,
        String compilerOutput,   // non-null for CE only (FR-20)
        long submittedAtMs
) {
}
