/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

/** Full problem for the center statement panel (UC-02). */
public record ProblemDetailDto(
        long id,
        String code,
        String title,
        String statementHtml,   // UTF-8 HTML rendered by the client (I18N-01)
        int timeLimitMs,
        int memoryLimitKb,
        /**
         * True when the real statement is a PDF and must be fetched from
         * {@code /api/problems/{id}/statement.pdf} instead of being read out of
         * {@code statementHtml} — which then holds only a placeholder. Jackson
         * defaults it to false, so a client talking to an older server behaves
         * exactly as before.
         */
        boolean pdfStatement
) {
}
