package com.arbitrator.server.leaderboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.LeaderboardDto;

/**
 * REST view of the standings. The live path is the STOMP broadcast
 * (LeaderboardBroadcaster); this endpoint serves the first paint and the
 * reconnect case, where a client that missed pushes needs current state
 * (UC-05 exception flow).
 */
@RestController
public class LeaderboardController {

    private final LeaderboardService leaderboard;

    public LeaderboardController(LeaderboardService leaderboard) {
        this.leaderboard = leaderboard;
    }

    @GetMapping(ApiPaths.LEADERBOARD)
    public LeaderboardDto current() {
        return leaderboard.current();
    }
}
