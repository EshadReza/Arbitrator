package com.arbitrator.server.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.repo.ContestRepository;

@Service
public class ContestService {

    private final ContestRepository contests;

    public ContestService(ContestRepository contests) {
        this.contests = contests;
    }

    /**
     * The one contest students are working in.
     *
     * A live contest (ACTIVE or FROZEN) always wins over a finished one.
     * Picking purely by highest id was fragile: creating a second contest and
     * starting it silently orphaned every existing problem, and submissions
     * then failed with a misleading "not part of the current contest" 403.
     * {@link #start(long)} keeps at most one contest live, so this is
     * unambiguous in practice.
     */
    public void checkExpiredContests() {
        List<Contest> live = contests.findAll().stream()
                .filter(c -> c.getState() == ContestState.ACTIVE || c.getState() == ContestState.FROZEN)
                .filter(c -> c.endTime() != null && !Instant.now().isBefore(c.endTime()))
                .toList();
        for (Contest c : live) {
            // The clock ran out on its own, so the end instant IS the scheduled
            // one — record it before flipping state, since endTime() starts
            // answering with endedAt the moment it is set.
            c.setEndedAt(c.endTime());
            c.setState(ContestState.ENDED);
            contests.save(c);
        }
    }

    public Contest requireCurrent() {
        checkExpiredContests();
        return contests.findFirstByStateInOrderByIdDesc(
                        List.of(ContestState.ACTIVE, ContestState.FROZEN, ContestState.LOBBY, ContestState.PAUSED))
                .or(() -> contests.findAll().stream().max(java.util.Comparator.comparingLong(Contest::getId)))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No contest is configured"));
    }

    /** Server clock is authoritative (FR-06, FMEA-06). */
    public ContestStateDto currentState() {
        Contest c = requireCurrent();
        return stateOf(c);
    }

    public ContestStateDto stateOf(Contest c) {
        if (c.getState() == ContestState.LOBBY && c.getScheduledStartAt() != null && !Instant.now().isBefore(c.getScheduledStartAt())) {
            c = start(c.getId());
        } else if ((c.getState() == ContestState.ACTIVE || c.getState() == ContestState.FROZEN)
                && c.endTime() != null && !Instant.now().isBefore(c.endTime())) {
            c.setEndedAt(c.endTime());
            c.setState(ContestState.ENDED);
            c = contests.save(c);
        }
        Instant start = c.getStartTime();
        if (start == null && c.getState() == ContestState.LOBBY && c.getScheduledStartAt() != null) {
            start = c.getScheduledStartAt();
        }
        Instant end = c.endTime();
        return new ContestStateDto(
                c.getId(), c.getTitle(), c.getState(),
                System.currentTimeMillis(),
                start == null ? -1 : start.toEpochMilli(),
                end == null ? -1 : end.toEpochMilli());
    }

    /**
     * BR-02: submissions after the server-side end instant are rejected
     * regardless of what the client timer showed. Messages say *why*, because
     * a bare "not accepting submissions" is impossible to act on.
     */
    public void assertAcceptingSubmissions(Contest c) {
        if (c.getState() == ContestState.PAUSED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The contest is paused — wait for the instructor to resume it");
        }
        if (!c.getState().acceptsSubmissions()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Contest is " + c.getState() + " and is not accepting submissions");
        }
        if (c.getStartTime() == null || Instant.now().isBefore(c.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The contest has not started yet");
        }
        if (c.endTime() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The contest has not started yet");
        }
        if (!Instant.now().isBefore(c.endTime())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The contest has ended");
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

    /**
     * Opens the doors without starting the contest. Students can enter and
     * wait; problems stay hidden and no clock runs until {@link #start(long)}.
     */
    public Contest openLobby(long id) {
        Contest c = require(id);
        // An ended contest is exactly what a restart begins from — the console's
        // "Open lobby" button has always been enabled for DRAFT and ENDED alike,
        // but this used to refuse ENDED with a 409 CONFLICT, so re-running the
        // same contest was blocked from the one place instructors do it.
        c.setState(ContestState.LOBBY);
        c.setStartTime(null);          // no clock yet
        c.setPausedAt(null);
        c.setPausedMillis(0);
        c.setEndedAt(null);            // reopening clears the previous run's end
        // A lobby countdown from the PREVIOUS run is almost always in the past
        // by the time a contest is reopened — it already fired once to start
        // that run. Left in place, the very next 1 s tick of
        // LeaderboardBroadcaster.checkScheduledLobbies() (and stateOf()'s own
        // copy of the same check) sees a LOBBY contest whose scheduledStartAt
        // is already due and calls start() immediately — so "Open lobby" after
        // an END appeared to skip the lobby and jump straight to ACTIVE.
        c.setScheduledStartAt(null);
        return contests.save(c);
    }

    /**
     * Starts (or restarts) a contest. Several contests may run at once — the
     * student picks which to join, and a submission's contest is derived from
     * its problem, so there is no ambiguous "current contest" to get wrong.
     */
    public Contest start(long id) {
        Contest c = require(id);
        c.setState(ContestState.ACTIVE);
        c.setStartTime(Instant.now());
        c.setPausedAt(null);
        c.setPausedMillis(0);          // a restart resets the clock entirely
        c.setEndedAt(null);            // ...including any early end from last time
        Contest saved = contests.save(c);
        // A restart is a fresh contest: last run's submissions must not leave
        // problems pre-solved or carry penalty into the new standings. They are
        // flagged inactive rather than deleted — DBR-04 keeps the record.
        onStarted.forEach(hook -> hook.accept(saved.getId()));
        return saved;
    }

    /**
     * Callbacks run when a contest starts. Used to reset per-contest state that
     * lives outside this service, without ContestService depending on it.
     */
    private final List<java.util.function.Consumer<Long>> onStarted = new ArrayList<>();

    public void onContestStarted(java.util.function.Consumer<Long> hook) {
        onStarted.add(hook);
    }

    public Contest end(long id) {
        Contest c = require(id);
        // Close any pause in progress so the recorded elapsed time is honest.
        if (c.getPausedAt() != null) {
            c.setPausedMillis(c.totalPausedMillis());
            c.setPausedAt(null);
        }
        // Ending early moves the deadline to now. Leaving it at start+duration
        // left every clock derived from endTime() counting down to a deadline
        // that no longer meant anything.
        c.setEndedAt(Instant.now());
        c.setState(ContestState.ENDED);
        return contests.save(c);
    }

    /**
     * FR-12 adjunct: whether contestants may see the test data behind their own
     * verdicts. Instructors only — and only ever the tests a submission
     * actually reached, never the whole hidden set.
     */
    public Contest setTestCaseVisibility(long id, boolean visible) {
        Contest c = require(id);
        c.setShowTestCases(visible);
        return contests.save(c);
    }

    /** Owner: Mahir — see Contest.allowPrivateClarifications (V61). */
    public Contest setAllowPrivateClarifications(long id, boolean allowed) {
        Contest c = require(id);
        c.setAllowPrivateClarifications(allowed);
        return contests.save(c);
    }

    /** Stops the clock. Remaining time is preserved, not spent (FR-06/FR-08). */
    public Contest pause(long id) {
        Contest c = require(id);
        if (c.getState() != ContestState.ACTIVE && c.getState() != ContestState.FROZEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a running contest can be paused (this one is " + c.getState() + ")");
        }
        c.setPausedAt(Instant.now());
        c.setState(ContestState.PAUSED);
        return contests.save(c);
    }

    /** Restarts the clock, banking however long the pause lasted. */
    public Contest resume(long id) {
        Contest c = require(id);
        if (c.getState() != ContestState.PAUSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a paused contest can be resumed (this one is " + c.getState() + ")");
        }
        c.setPausedMillis(c.totalPausedMillis());
        c.setPausedAt(null);
        c.setState(ContestState.ACTIVE);
        return contests.save(c);
    }

    /** Adds (or with a negative value, removes) minutes from the deadline. */
    public Contest extend(long id, int minutes) {
        Contest c = require(id);
        if (c.getState() == ContestState.ENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot extend a contest that has already ended");
        }
        int updated = c.getDurationMinutes() + minutes;
        if (updated < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Duration cannot be reduced below 1 minute");
        }
        c.setDurationMinutes(updated);
        return contests.save(c);
    }

    /** FR-19: hold the public standings while judging continues underneath. */
    public Contest freeze(long id) {
        Contest c = require(id);
        if (c.getState() != ContestState.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an active contest can be frozen (this one is " + c.getState() + ")");
        }
        c.setState(ContestState.FROZEN);
        if (c.getFrozenAt() == null) {
            c.setFrozenAt(Instant.now());
        }
        return contests.save(c);
    }

    public Contest unfreeze(long id) {
        Contest c = require(id);
        if (c.getState() != ContestState.FROZEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Contest is not frozen (this one is " + c.getState() + ")");
        }
        c.setState(ContestState.ACTIVE);
        c.setFrozenAt(null);
        return contests.save(c);
    }

    public Contest scheduleLobby(long id, Instant scheduledStartAt) {
        Contest c = require(id);
        if (c.getState().hasStarted()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot set lobby countdown for a contest that has already started (" + c.getState() + ")");
        }
        c.setScheduledStartAt(scheduledStartAt);
        return contests.save(c);
    }

    /**
     * Removes a contest and all associated problems and submissions.
     */
    public void delete(long id, Runnable deleteAssociatedResources) {
        Contest c = require(id);
        deleteAssociatedResources.run();
        contests.delete(c);
    }

    public Contest require(long id) {
        return contests.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such contest"));
    }

    public List<Contest> all() {
        return contests.findAll();
    }

    /** Contests a student may enter — everything except DRAFT and ENDED. */
    public List<Contest> joinable() {
        checkExpiredContests();
        return contests.findAll().stream()
                .filter(c -> c.getState().isJoinable())
                .toList();
    }
}
