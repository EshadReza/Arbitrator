package com.arbitrator.server.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.server.service.SubmissionService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
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

    /** UIF-12: the code behind one of my submissions. */
    @GetMapping(ApiPaths.SUBMISSION_SOURCE)
    public SubmissionSourceDto source(@PathVariable long id, Principal principal) {
        return submissionService.source(principal.getName(), id, false);
    }

    /** FR-16. */
    @GetMapping(ApiPaths.SUBMISSIONS_MINE)
    public List<SubmissionHistoryDto> mine(Principal principal) {
        return submissionService.history(principal.getName());
    }
}
