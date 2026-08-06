package com.arbitrator.server.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.AnnouncementDto;
import com.arbitrator.common.dto.ClarificationDto;
import com.arbitrator.server.service.AnnouncementService;
import com.arbitrator.server.service.ClarificationService;

/**
 * The instructor's side of both message boards (FR-07).
 * Loopback + ADMIN JWT like every other admin route (decision D3).
 */
@RestController
public class AdminCommunicationController {

    private final AnnouncementService announcements;
    private final ClarificationService clarifications;

    public AdminCommunicationController(AnnouncementService announcements,
                                        ClarificationService clarifications) {
        this.announcements = announcements;
        this.clarifications = clarifications;
    }

    // --- announcements ---

    @GetMapping(ApiPaths.ADMIN_ANNOUNCEMENTS)
    public List<AnnouncementDto> listAnnouncements(@PathVariable long id) {
        return announcements.forContest(id);
    }

    /** Publishing pops the announcement on every connected client at once. */
    @PostMapping(ApiPaths.ADMIN_ANNOUNCEMENTS)
    public AnnouncementDto publish(@PathVariable long id, @RequestBody PublishRequest req) {
        return announcements.publish(id, req.body());
    }

    @DeleteMapping(ApiPaths.ADMIN_ANNOUNCEMENT_BY_ID)
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable long id) {
        announcements.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record PublishRequest(String body) {
    }

    // --- clarifications ---

    /** admin=true: this is the one view that carries who asked. */
    @GetMapping(ApiPaths.ADMIN_CLARIFICATIONS)
    public List<ClarificationDto> listClarifications(@PathVariable long id) {
        // admin=true: every question, public or private, name attached — the
        // last argument is only consulted for the participant path.
        return clarifications.forContest(id, true, null);
    }

    @PostMapping(ApiPaths.ADMIN_CLARIFICATION_ANSWER)
    public ClarificationDto answer(@PathVariable long id, @RequestBody AnswerRequest req) {
        return clarifications.answer(id, req.answer());
    }

    public record AnswerRequest(String answer) {
    }
}
