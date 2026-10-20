/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.arbitrator.server.service.AArchiveExportService;

/** The archive tells AArchive how penalty works; it must say what the leaderboard actually does. */
class AArchiveExportPenaltyTest {

    @Test
    void exportedPenaltyRuleMatchesTheLeaderboard() {
        assertEquals(LeaderboardService.PENALTY_PER_REJECT, AArchiveExportService.PENALTY_PER_REJECT_MINUTES);
    }
}
