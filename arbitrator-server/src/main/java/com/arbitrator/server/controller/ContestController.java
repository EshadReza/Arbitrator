package com.arbitrator.server.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.service.ContestService;

@RestController
public class ContestController {

    private final ContestService contestService;
    private final ProblemRepository problems;

    public ContestController(ContestService contestService, ProblemRepository problems) {
        this.contestService = contestService;
        this.problems = problems;
    }

    /** Contests a student may enter, for the client's contest picker. */
    @GetMapping(ApiPaths.CONTESTS)
    public List<ContestSummaryDto> joinable() {
        return contestService.joinable().stream().map(this::summarise).toList();
    }

    /** Convenience for a single-contest lab: whichever contest is live. */
    @GetMapping(ApiPaths.CONTEST_CURRENT)
    public ContestStateDto current() {
        return contestService.currentState();
    }

    /**
     * The chosen contest's live state — drives the client countdown (FR-06).
     *
     * {@code password} is required, and checked against the stored hash,
     * whenever the contest has one — never trust the client's own
     * {@code passwordProtected} flag, that's just what tells it to prompt.
     *
     * ADMIN callers skip this check: this same endpoint is what the admin
     * console polls every few seconds to paint Contest Control (clocks,
     * lifecycle, state badge), and an admin's own ADMIN JWT is already a
     * stronger credential than the contest's join password — without this
     * exemption a password-protected contest was unmanageable from its own
     * control page, repeatedly 403ing on every poll.
     */
    @GetMapping(ApiPaths.CONTEST_BY_ID)
    public ContestStateDto byId(@PathVariable long id,
                                @RequestParam(required = false) String password,
                                Authentication authentication) {
        Contest c = contestService.require(id);
        boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin && !contestService.verifyPassword(c, password)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Incorrect contest password");
        }
        return contestService.stateOf(c);
    }

    private ContestSummaryDto summarise(Contest c) {
        var start = c.getStartTime();
        var end = c.endTime();
        return new ContestSummaryDto(
                c.getId(),
                c.getTitle(),
                c.getState(),
                start == null ? -1 : start.toEpochMilli(),
                end == null ? -1 : end.toEpochMilli(),
                c.getDurationMinutes(),
                problems.findByContestIdOrderByOrderingAscCodeAsc(c.getId()).size(),
                c.getState().isJoinable(),
                c.hasPassword());
    }
}
