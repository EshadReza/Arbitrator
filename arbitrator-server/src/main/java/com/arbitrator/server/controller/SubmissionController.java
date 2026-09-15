package com.arbitrator.server.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.CustomRunRequest;
import com.arbitrator.common.dto.CustomRunResultDto;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
import com.arbitrator.common.dto.SubmissionTestsDto;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.server.judge.CustomRunService;
import com.arbitrator.server.service.SubmissionService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class SubmissionController {

    private final SubmissionService submissionService;
    private final CustomRunService customRunService;

    public SubmissionController(SubmissionService submissionService,
                                CustomRunService customRunService) {
        this.submissionService = submissionService;
        this.customRunService = customRunService;
    }

    /** FR-09 EARS: 202 Accepted + queue position. */
    @PostMapping(ApiPaths.SUBMISSIONS)
    public ResponseEntity<SubmitAckDto> submit(@RequestBody SubmitRequest req,
                                               Principal principal,
                                               HttpServletRequest http) {
        SubmitAckDto ack = submissionService.submit(
                principal.getName(), req, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ack);
    }

    /**
     * Run against custom input without submitting. Not judged, not stored, no
     * effect on standings — so it is deliberately exempt from the BR-01
     * submission cooldown.
     */
    @PostMapping(ApiPaths.RUN_CUSTOM)
    public CustomRunResultDto runCustom(@RequestBody CustomRunRequest req, Principal principal) {
        return customRunService.run(principal.getName(), req);
    }

    /** UIF-12: the code behind one of my submissions. */
    @GetMapping(ApiPaths.SUBMISSION_SOURCE)
    public SubmissionSourceDto source(@PathVariable long id, Principal principal) {
        return submissionService.source(principal.getName(), id, false);
    }

    /**
     * The tests behind one of my verdicts — the ones I passed plus the one that
     * failed me. Returns visible=false rather than 403 when the instructor has
     * not enabled it, so the client can explain instead of erroring.
     */
    @GetMapping(ApiPaths.SUBMISSION_TESTS)
    public SubmissionTestsDto tests(@PathVariable long id, Principal principal) {
        return submissionService.tests(principal.getName(), id, false);
    }

    /**
     * FR-16. contestId defaults to whichever contest is current so a
     * single-contest lab needs no parameter — same convention as
     * {@code ApiPaths.ANNOUNCEMENTS} and {@code ApiPaths.CLARIFICATIONS}.
     * {@code all=true} is the client's "Current Contest" tick turned off:
     * every submission this account has ever made, ignoring contestId.
     */
    @GetMapping(ApiPaths.SUBMISSIONS_MINE)
    public List<SubmissionHistoryDto> mine(Principal principal,
            @RequestParam(value = "contestId", required = false) Long contestId,
            @RequestParam(value = "all", defaultValue = "false") boolean all) {
        return submissionService.history(principal.getName(), contestId, all);
    }
}
