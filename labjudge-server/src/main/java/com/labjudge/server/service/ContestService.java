package com.labjudge.server.service;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.labjudge.common.dto.ContestStateDto;
import com.labjudge.common.enums.ContestState;
import com.labjudge.server.entity.Contest;
import com.labjudge.server.repo.ContestRepository;

@Service
public class ContestService {

    private final ContestRepository contests;

    public ContestService(ContestRepository contests) {
        this.contests = contests;
    }

    /** The one non-draft contest of Bundle 1 (multi-contest comes later). */
    public Contest requireCurrent() {
        return contests.findFirstByStateNotOrderByIdDesc(ContestState.DRAFT)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No contest is configured"));
    }

    /** Server clock is authoritative (FR-06, FMEA-06). */
    public ContestStateDto currentState() {
        Contest c = requireCurrent();
        Instant start = c.getStartTime();
        Instant end = c.endTime();
        return new ContestStateDto(
                c.getId(),
                c.getTitle(),
                c.getState(),
                System.currentTimeMillis(),
                start == null ? -1 : start.toEpochMilli(),
                end == null ? -1 : end.toEpochMilli());
    }

    /**
     * BR-02: submissions after the server-side end instant are rejected
     * regardless of what the client timer showed.
     */
    public void assertAcceptingSubmissions(Contest c) {
        boolean live = (c.getState() == ContestState.ACTIVE || c.getState() == ContestState.FROZEN)
                && c.getStartTime() != null
                && !Instant.now().isBefore(c.getStartTime())
                && Instant.now().isBefore(c.endTime());
        if (!live) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Contest is not accepting submissions");
        }
    }

    // --- admin operations (FR-04, FR-08) — called from AdminContestController ---

    public Contest create(String title, int durationMinutes) {
        Contest c = new Contest();
        c.setTitle(title);
        c.setDurationMinutes(durationMinutes);
        c.setState(ContestState.DRAFT);
        return contests.save(c);
    }

    public Contest start(long id) {
        Contest c = contests.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such contest"));
        c.setState(ContestState.ACTIVE);
        c.setStartTime(Instant.now());
        return contests.save(c);
    }

    public Contest end(long id) {
        Contest c = contests.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such contest"));
        c.setState(ContestState.ENDED);
        return contests.save(c);
    }
}
