package com.arbitrator.server.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.ClarificationDto;
import com.arbitrator.server.entity.Clarification;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ClarificationRepository;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.UserRepository;

/**
 * The clarification board.
 *
 * Owner: Mahir. The default, and the common case, is public: every question
 * and answer intended to reach everyone, because a clarification given
 * privately to one team is an advantage the rest of the room never gets a
 * chance at. The asker may instead mark a question private — for something
 * that identifies their approach, or isn't really a fairness issue — in which
 * case it is visible only to them and the instructor.
 *
 * A public question isn't broadcast the instant it's answered, though — an
 * admin must {@link #setApproved approve} it first. "Public" at ask-time is
 * the asker's request to eventually share it, not a publish decision; a
 * question can be worth answering privately-in-effect (typo in the phrasing,
 * or half the class already knows) without the instructor having to
 * mislabel it "private" to keep the raw answer off the board.
 *
 * All three filters — public/private, approved/not, and the asker's own name
 * — happen here, in {@link #forContest} and {@link #toDto}, never in the
 * client: a participant's response is missing the name, missing other
 * people's private questions, and missing not-yet-approved public ones
 * outright, not merely told not to display them. Hiding any of it in the UI
 * would leave it sitting in the JSON for anyone who looked.
 */
@Service
public class ClarificationService {

    private static final Logger log = LoggerFactory.getLogger(ClarificationService.class);

    private static final int MAX_QUESTION_CHARS = 4096;
    private static final int MAX_ANSWER_CHARS = 8192;

    private final ClarificationRepository clarifications;
    private final ProblemRepository problems;
    private final UserRepository users;
    private final ContestService contestService;
    private final UserService userService;
    private final SimpMessagingTemplate template;

    public ClarificationService(ClarificationRepository clarifications,
                                ProblemRepository problems,
                                UserRepository users,
                                ContestService contestService,
                                UserService userService,
                                SimpMessagingTemplate template) {
        this.clarifications = clarifications;
        this.problems = problems;
        this.users = users;
        this.contestService = contestService;
        this.userService = userService;
        this.template = template;
    }

    /**
     * A contestant asks. The contest is derived from the problem when one is
     * named, so a question cannot be filed against a contest the problem is not
     * in; a general question uses the contest the caller passes.
     */
    public ClarificationDto ask(String username, Long problemId, Long contestId, String question,
                                boolean isPublic) {
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Write your question before sending it");
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A question is limited to " + MAX_QUESTION_CHARS + " characters");
        }
        User user = userService.requireByUsername(username);

        Problem problem = null;
        if (problemId != null) {
            problem = problems.findById(problemId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));
            contestId = problem.getContestId();
        }
        if (contestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pick a problem, or say which contest the question is about");
        }
        // Never trust the client's own checkbox: it was read when the asker
        // opened the dialog, and the instructor's toggle (V61) may have moved
        // since. Silently sending a requested-private question public anyway
        // would leave the asker unaware it was ever exposed — worse than
        // making them notice and resend. Same reasoning as ContestService's
        // pause/resume/freeze guards: a precondition the caller no longer
        // agrees with is a 409, not something to quietly paper over.
        boolean allowPrivate = contestService.require(contestId).isAllowPrivateClarifications();
        if (!isPublic && !allowPrivate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Private clarifications have been turned off by the instructor. "
                            + "Uncheck \"private\" and send again.");
        }

        Clarification c = new Clarification();
        c.setContestId(contestId);
        c.setProblemId(problem == null ? null : problem.getId());
        c.setUserId(user.getId());
        c.setQuestion(question.trim());
        c.setAskedAt(Instant.now());
        c.setPublic(isPublic);
        clarifications.save(c);

        broadcast(contestId);
        // The asker sees their own question immediately regardless of
        // visibility — asOwner=true is what lets a private one render at all
        // right after sending it, instead of vanishing until a poll re-fetches
        // a list it would otherwise be filtered out of.
        return toDto(c, problemsById(), namesById(), false);
    }

    /**
     * The instructor answers. For a private clarification the board updates
     * for everyone who can already see it (the asker) right away; for a
     * public one, this only writes the answer — it stays invisible to
     * everyone but the asker and admins until {@link #setApproved} publishes
     * it. Revising an already-approved answer does not un-approve it; an
     * admin who wants to pull a revision back for review does so explicitly.
     */
    public ClarificationDto answer(long id, String answer) {
        if (answer == null || answer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An answer cannot be empty");
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An answer is limited to " + MAX_ANSWER_CHARS + " characters");
        }
        Clarification c = clarifications.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such clarification"));

        c.setAnswer(answer.trim());
        c.setAnsweredAt(Instant.now());
        clarifications.save(c);

        broadcast(c.getContestId());
        return toDto(c, problemsById(), namesById(), true);
    }

    /**
     * The instructor's publish decision for a public, answered clarification
     * (item: "approval system"). Approving before an answer exists would
     * publish an empty answer the moment one is later added with no further
     * review step, so it's refused outright rather than silently allowed.
     */
    public ClarificationDto setApproved(long id, boolean approved) {
        Clarification c = clarifications.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such clarification"));
        if (approved && (c.getAnswer() == null || c.getAnswer().isBlank())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Answer it before approving — there's nothing to publish yet");
        }
        c.setApproved(approved);
        clarifications.save(c);

        broadcast(c.getContestId());
        return toDto(c, problemsById(), namesById(), true);
    }

    /**
     * @param admin           true to see every question (public and private)
     *                        with the asker's name attached — the instructor's
     *                        view, never gated by the visibility flag.
     * @param callerUsername  who is asking, so their OWN private questions can
     *                        be included alongside everyone's public ones.
     *                        Ignored when admin is true.
     */
    public List<ClarificationDto> forContest(long contestId, boolean admin, String callerUsername) {
        Map<Long, Problem> problemMap = problemsById();
        Map<Long, String> nameMap = namesById();
        Long callerId = (!admin && callerUsername != null)
                ? userService.requireByUsername(callerUsername).getId() : null;

        return clarifications.findByContestIdOrderByAskedAtDesc(contestId).stream()
                .filter(c -> admin || (c.isPublic() && c.isApproved()) || c.getUserId().equals(callerId))
                .map(c -> toDto(c, problemMap, nameMap, admin))
                .toList();
    }

    // ------------------------------------------------------------------

    /**
     * Same push used after ask/answer, called by AdminContestController when
     * the private-clarification toggle (V61) changes. Nothing about the board
     * itself moved, but the client's ask-dialog decision (show the private
     * checkbox or not) is cached from the same refresh() that this push
     * triggers — without it, a toggle only reached a client the next time it
     * happened to refresh on its own (asking a question, or restarting).
     */
    public void notifyBoardChanged(long contestId) {
        broadcast(contestId);
    }

    /**
     * Tells the contest its board moved. The payload is deliberately not the
     * clarification itself: what one side may see differs from what the other
     * may, and a single broadcast cannot be both. Clients re-fetch, and each
     * gets the view it is entitled to.
     */
    private void broadcast(long contestId) {
        try {
            template.convertAndSend(StompDestinations.contestClarifications(contestId),
                    Map.of("contestId", contestId, "changedAtMs", System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("Clarification broadcast failed for contest {}", contestId, e);
        }
    }

    private Map<Long, Problem> problemsById() {
        Map<Long, Problem> map = new HashMap<>();
        problems.findAll().forEach(p -> map.put(p.getId(), p));
        return map;
    }

    private Map<Long, String> namesById() {
        Map<Long, String> map = new HashMap<>();
        users.findAll().forEach(u -> map.put(u.getId(), u.getUsername()));
        return map;
    }

    private static ClarificationDto toDto(Clarification c, Map<Long, Problem> problemMap,
                                          Map<Long, String> nameMap, boolean admin) {
        Problem p = c.getProblemId() == null ? null : problemMap.get(c.getProblemId());
        return new ClarificationDto(
                c.getId(),
                p == null ? null : p.getCode(),
                p == null ? null : p.getTitle(),
                c.getQuestion(),
                c.getAnswer(),
                c.getAskedAt().toEpochMilli(),
                c.getAnsweredAt() == null ? -1 : c.getAnsweredAt().toEpochMilli(),
                admin ? nameMap.get(c.getUserId()) : null,
                c.isPublic(),
                c.isApproved());
    }
}
