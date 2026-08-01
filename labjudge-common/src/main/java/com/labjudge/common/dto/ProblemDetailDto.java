package com.labjudge.common.dto;

/** Full problem for the center statement panel (UC-02). */
public record ProblemDetailDto(
        long id,
        String code,
        String title,
        String statementHtml,   // UTF-8 HTML rendered by the client (I18N-01)
        int timeLimitMs,
        int memoryLimitKb
) {
}
