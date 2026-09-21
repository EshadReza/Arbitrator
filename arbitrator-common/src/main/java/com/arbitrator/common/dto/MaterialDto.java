/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

/**
 * One downloadable instructor material (FR-07 sibling to announcements) —
 * lecture slides, a PDF, any file. Unlike an announcement's {@code body},
 * this carries no content itself; {@code id} is what a client passes to
 * {@code /api/materials/{id}/download} to get the actual bytes.
 */
public record MaterialDto(
        long id,
        long contestId,
        String filename,
        String contentType,
        long sizeBytes,
        long uploadedAtMs
) {
}
