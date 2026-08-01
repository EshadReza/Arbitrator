package com.labjudge.server.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.labjudge.common.api.ApiPaths;
import com.labjudge.common.dto.SubmissionHistoryDto;
import com.labjudge.common.dto.SubmitAckDto;
import com.labjudge.common.dto.SubmitRequest;
import com.labjudge.server.service.SubmissionService;

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

    /** FR-16. */
    @GetMapping(ApiPaths.SUBMISSIONS_MINE)
    public List<SubmissionHistoryDto> mine(Principal principal) {
        return submissionService.history(principal.getName());
    }
}
