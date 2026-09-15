package com.arbitrator.server.controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.ClarificationDto;
import com.arbitrator.server.service.ClarificationService;
import com.arbitrator.server.service.ContestAccessService;
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
    private final ContestAccessService contestAccess;

    public ClarificationController(ClarificationService clarifications,
                                   ContestService contestService,
                                   ContestAccessService contestAccess) {
        this.clarifications = clarifications;
        this.contestService = contestService;
        this.contestAccess = contestAccess;
    }

    @GetMapping(ApiPaths.CLARIFICATIONS)
    public List<ClarificationDto> list(
            @RequestParam(value = "contestId", required = false) Long contestId,
            Principal principal) {
        long target = contestId != null ? contestId : contestService.requireCurrent().getId();
        contestAccess.requireAccess(target, principal.getName());
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

    /**
     * Whether the client should offer the private checkbox at all (V61).
     * A student-reachable mirror of the admin toggle — the client needs this
     * before it can render the ask dialog, unlike show-test-cases, which the
     * client never needs to know in advance.
     */
    @GetMapping(ApiPaths.CONTEST_CLARIFICATION_PRIVACY)
    public Map<String, Boolean> privacyAllowed(@PathVariable long id, Principal principal) {
        return Map.of("allowed", contestAccess.requireAccess(id, principal.getName())
                .isAllowPrivateClarifications());
    }
}
