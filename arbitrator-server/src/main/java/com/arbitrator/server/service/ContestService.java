package com.arbitrator.server.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.repo.ContestRepository;

@Service
public class ContestService {

    private final ContestRepository contests;
    private final PasswordEncoder passwordEncoder;

    public ContestService(ContestRepository contests, PasswordEncoder passwordEncoder) {
        this.contests = contests;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * The one contest students are working in.
     *
     * {@link #openLobby} and {@link #start} refuse to make a second contest
     * live while one is already LOBBY/ACTIVE/PAUSED/FROZEN (see
     * {@link #assertNoOtherContestLive}), so there is at most one candidate
     * here — the state filter plus id-desc ordering is defensive, not load-
     * bearing, for the case where none is currently live and the caller wants
     * whatever contest is "current" for display purposes anyway.
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
            endNow(c, c.endTime());
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
            c = endNow(c, c.endTime());
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
        return create(title, durationMinutes, null);
    }

    /**
     * {@code password} blank/null means "no password required" (the
     * default) — most contests in a closed LAN lab don't need one. Only the
     * bcrypt hash is ever persisted (see {@link Contest#passwordHash}).
     */
    public Contest create(String title, int durationMinutes, String password) {
        Contest c = new Contest();
        c.setTitle(title);
        c.setDurationMinutes(durationMinutes);
        c.setState(ContestState.DRAFT);
        if (password != null && !password.isBlank()) {
            c.setPasswordHash(passwordEncoder.encode(password));
        }
        return contests.save(c);
    }

    /**
     * True when the contest has no password, or {@code suppliedPassword}
     * matches the one it was created with. Always call this before letting a
     * student into a protected contest's state/problems — never trust a
     * client-side "I have the password" flag.
     */
    public boolean verifyPassword(Contest c, String suppliedPassword) {
        if (!c.hasPassword()) {
            return true;
        }
        return suppliedPassword != null
                && passwordEncoder.matches(suppliedPassword, c.getPasswordHash());
    }

    /**
     * Only one contest may be LOBBY/ACTIVE/PAUSED/FROZEN at a time — a lab has
     * one room, and letting two run together made it ambiguous which contest
     * a client landed in and left standings/clarifications/announcements
     * split across two audiences without anyone choosing that on purpose.
     * {@link ContestState#isJoinable()} is exactly this set of states.
     */
    private void assertNoOtherContestLive(long excludeId) {
        contests.findAll().stream()
                .filter(c -> c.getId() != excludeId)
                .filter(c -> c.getState().isJoinable())
                .findFirst()
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "\"" + other.getTitle() + "\" is already " + other.getState()
                                    + " — end it before starting another contest.");
                });
    }

    /**
     * Opens the doors without starting the contest. Students can enter and
     * wait; problems stay hidden and no clock runs until {@link #start(long)}.
     *
     * ENDED is terminal (BR-02: submissions are permanently refused, and the
     * lifecycle now matches that in spirit too). A previous version of this
     * method deliberately allowed reopening an ENDED contest as a way to
     * re-run it — that is exactly what {@link ProblemPackageService#cloneContest}
     * exists to replace: cloning makes a fresh DRAFT contest with a copy of
     * the problems, rather than resurrecting the one that already ended (and
     * with it, resurrecting its old submissions/announcements/clarifications,
     * which the clear-on-restart listeners would otherwise have to reconcile).
     */
    public Contest openLobby(long id) {
        Contest c = require(id);
        if (c.getState() == ContestState.ENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This contest has ended and cannot be reopened. Clone it to run a fresh copy.");
        }
        assertNoOtherContestLive(id);
        c.setState(ContestState.LOBBY);
        c.setStartTime(null);          // no clock yet
        c.setPausedAt(null);
        c.setPausedMillis(0);
        // A lobby countdown from a PREVIOUS open-lobby is almost always in the
        // past by the time this runs again — it already fired once. Left in
        // place, the very next 1 s tick of
        // LeaderboardBroadcaster.checkScheduledLobbies() (and stateOf()'s own
        // copy of the same check) sees a LOBBY contest whose scheduledStartAt
        // is already due and calls start() immediately — so "Open lobby"
        // would appear to skip the lobby and jump straight to ACTIVE.
        c.setScheduledStartAt(null);
        return contests.save(c);
    }

    /**
     * Starts a contest. Only one contest may be live at a time (see
     * {@link #assertNoOtherContestLive}) — a lab has one room and one clock on
     * the wall, and running two at once made it unclear which one a freshly
     * opened client would land in.
     *
     * Rejects ENDED for the same reason {@link #openLobby} does — see its
     * comment. DRAFT or LOBBY are the only states this actually transitions —
     * anything else (ACTIVE/PAUSED/FROZEN) is a no-op that returns the
     * contest unchanged rather than resetting its clock, so a contest can
     * only ever really start once per lifetime, and {@link #onStarted}'s
     * hooks — which archive the previous run's submissions/announcements/
     * clarifications — always fire against an always-empty previous run.
     */
    public Contest start(long id) {
        Contest c = require(id);
        if (c.getState() == ContestState.ENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This contest has ended and cannot be started again. Clone it to run a fresh copy.");
        }
        if (c.getState() != ContestState.DRAFT && c.getState() != ContestState.LOBBY) {
            // Already running — a no-op, not a re-start. Without this guard,
            // two independent callers checking the identical "LOBBY whose
            // scheduledStartAt is due" condition (LeaderboardBroadcaster's 1s
            // checkScheduledLobbies() and stateOf()'s own copy of the same
            // check, driven by ContestStatePublisher's 10s heartbeat — see the
            // comment on openLobby above) can both see the contest as still
            // LOBBY in the same narrow window and both call start(). The
            // second call used to overwrite startTime with a brand new
            // Instant.now() on an already-ACTIVE contest — a real field
            // change that MainController.onContestState's restart detector
            // correctly read as a genuine restart, wiping every connected
            // client's editor a few seconds into the contest.
            return c;
        }
        assertNoOtherContestLive(id);
        c.setState(ContestState.ACTIVE);
        c.setStartTime(Instant.now());
        c.setPausedAt(null);
        c.setPausedMillis(0);
        Contest saved = contests.save(c);
        onStarted.forEach(hook -> hook.accept(saved.getId()));
        return saved;
    }

    /**
     * Callbacks run when a contest starts. Used to reset per-contest state that
     * lives outside this service, without ContestService depending on it.
     */
    private final List<java.util.function.Consumer<Long>> onStarted = new ArrayList<>();

    /**
     * Callbacks run when a contest ends — by an explicit "End contest" click
     * OR by its clock simply running out ({@link #checkExpiredContests} /
     * {@link #stateOf}, both of which also flow through {@link #endNow}).
     * Used to stop per-contest state that lives outside this service (e.g.
     * NotificationService's offline-duration clocks) from continuing to run,
     * or from being read later, against a contest that is no longer live.
     */
    private final List<java.util.function.Consumer<Long>> onEnded = new ArrayList<>();

    public void onContestStarted(java.util.function.Consumer<Long> hook) {
        onStarted.add(hook);
    }

    public void onContestEnded(java.util.function.Consumer<Long> hook) {
        onEnded.add(hook);
    }

    /** Every path to ENDED funnels through here so {@link #onEnded} always fires. */
    private Contest endNow(Contest c, Instant endedAt) {
        c.setEndedAt(endedAt);
        c.setState(ContestState.ENDED);
        Contest saved = contests.save(c);
        onEnded.forEach(hook -> hook.accept(saved.getId()));
        return saved;
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
        return endNow(c, Instant.now());
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
