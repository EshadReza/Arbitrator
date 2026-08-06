package com.arbitrator.server.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.AnnouncementDto;
import com.arbitrator.server.entity.Announcement;
import com.arbitrator.server.repo.AnnouncementRepository;

/**
 * FR-07: instructor announcements.
 *
 * Owner: Mahir (rules.md Rule 1 — service/announcement).
 *
 * Publishing writes the row first and only then broadcasts, the same ordering
 * the judge uses for submissions (FMEA-01): a client that misses the push can
 * still find the announcement by re-reading the list, but a client that acted
 * on a push for a row that was never committed would be showing a message that
 * does not exist.
 */
@Service
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);

    /** Long enough for a real notice, short enough not to be a document. */
    private static final int MAX_BODY_CHARS = 8192;

    private final AnnouncementRepository announcements;
    private final ContestService contestService;
    private final SimpMessagingTemplate template;

    public AnnouncementService(AnnouncementRepository announcements,
                               ContestService contestService,
                               SimpMessagingTemplate template) {
        this.announcements = announcements;
        this.contestService = contestService;
        this.template = template;
    }

    public AnnouncementDto publish(long contestId, String body) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An announcement cannot be empty");
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An announcement is limited to " + MAX_BODY_CHARS + " characters");
        }
        contestService.require(contestId);      // 404 rather than an orphan row

        Announcement a = new Announcement();
        a.setContestId(contestId);
        a.setBody(body.trim());
        a.setCreatedAt(Instant.now());
        announcements.save(a);

        AnnouncementDto dto = toDto(a);
        try {
            template.convertAndSend(StompDestinations.contestAnnouncements(contestId), dto);
        } catch (RuntimeException e) {
            // The row is committed; a failed broadcast costs a client its popup,
            // not the announcement itself. It still appears in the list.
            log.warn("Announcement broadcast failed for contest {}", contestId, e);
        }
        return dto;
    }

    public List<AnnouncementDto> forContest(long contestId) {
        return announcements.findByContestIdOrderByCreatedAtDesc(contestId).stream()
                .map(AnnouncementService::toDto)
                .toList();
    }

    public void delete(long id) {
        Announcement a = announcements.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such announcement"));
        announcements.delete(a);
    }

    private static AnnouncementDto toDto(Announcement a) {
        return new AnnouncementDto(a.getId(), a.getContestId(), a.getBody(),
                a.getCreatedAt().toEpochMilli());
    }
}
