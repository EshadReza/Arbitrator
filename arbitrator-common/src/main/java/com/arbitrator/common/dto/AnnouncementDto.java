/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.dto;

/**
 * One instructor announcement (FR-07).
 *
 * {@code body} is HTML and is rendered as such. An announcement almost always
 * needs mathematics — "for all n &le; 10<sup>9</sup>" — and HTML delivers that
 * offline, with no formula engine and no font bundle to ship to a lab machine
 * that has never seen the internet.
 */
public record AnnouncementDto(
        long id,
        long contestId,
        String body,
        long createdAtMs
) {
}
