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
 * and answer visible to everyone, because a clarification given privately to
 * one team is an advantage the rest of the room never gets a chance at. The
 * asker may instead mark a question private — for something that identifies
 * their approach, or isn't really a fairness issue — in which case it is
 * visible only to them and the instructor.
 *
 * Both filters happen here, in {@link #forContest} and {@link #toDto}, never
 * in the client: a participant's response is missing the name and missing
 * other people's private questions outright, not merely told not to display
 * them. Hiding either in the UI would leave it sitting in the JSON for anyone
 * who looked.
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
        contestService.require(contestId);

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

    /** The instructor answers; the board updates for everyone at once. */
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
                .filter(c -> admin || c.isPublic() || c.getUserId().equals(callerId))
                .map(c -> toDto(c, problemMap, nameMap, admin))
                .toList();
    }

    // ------------------------------------------------------------------

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
                c.isPublic());
    }
}
