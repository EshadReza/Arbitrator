package com.arbitrator.server.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ClarificationDto;
import com.arbitrator.server.service.ClarificationService;
import com.arbitrator.server.service.ContestService;

/**
 * The clarification board as contestants see it: every PUBLIC question and
 * answer, with nobody's name attached, plus the caller's OWN private ones —
 * theirs to keep track of even though nobody else's private questions show up
 * here at all. Owner: Mahir.
 */
@RestController
public class ClarificationController {

    private final ClarificationService clarifications;
    private final ContestService contestService;

    public ClarificationController(ClarificationService clarifications,
                                   ContestService contestService) {
        this.clarifications = clarifications;
        this.contestService = contestService;
    }

    @GetMapping(ApiPaths.CLARIFICATIONS)
    public List<ClarificationDto> list(
            @RequestParam(value = "contestId", required = false) Long contestId,
            Principal principal) {
        long target = contestId != null ? contestId : contestService.requireCurrent().getId();
        // admin=false: no name attached, and private questions from anyone
        // else are left out of the list entirely, not merely unlabelled.
        return clarifications.forContest(target, false, principal.getName());
    }

    @PostMapping(ApiPaths.CLARIFICATIONS)
    public ClarificationDto ask(@RequestBody AskRequest req, Principal principal) {
        // Public is the default when the field is omitted: that was the only
        // behaviour before this option existed, so an older client (or a
        // request that just forgets the flag) keeps behaving the same way.
        boolean isPublic = req.isPublic() == null || req.isPublic();
        return clarifications.ask(principal.getName(), req.problemId(), req.contestId(),
                req.question(), isPublic);
    }

    /**
     * {@code problemId} null means the question is about the contest itself.
     * {@code isPublic} null means "public" — see {@link #ask}.
     */
    public record AskRequest(Long problemId, Long contestId, String question, Boolean isPublic) {
    }
}
