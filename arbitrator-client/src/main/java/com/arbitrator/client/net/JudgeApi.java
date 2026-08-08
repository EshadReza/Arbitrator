package com.arbitrator.client.net;

import java.util.List;
import java.util.function.Consumer;

import com.arbitrator.common.dto.AnnouncementDto;
import com.arbitrator.common.dto.ClarificationDto;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.CustomRunRequest;
import com.arbitrator.common.dto.CustomRunResultDto;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
import com.arbitrator.common.dto.SubmissionTestsDto;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.dto.VerdictEventDto;

/**
 * The client's ONLY window onto the server. Two implementations:
 * HttpJudgeApi (real) and FakeJudgeApi (canned data, -Darbitrator.mock=true) —
 * which is how Zahin builds the whole UI before the server exists
 * (WORKFLOW_PLAN §5).
 */
public interface JudgeApi extends AutoCloseable {

    LoginResponse login(String username, String password) throws ApiException;

    /**
     * FR-01. On success the account exists and the returned token is already
     * usable — registering logs you straight in, no second round trip.
     * Always creates a STUDENT; admin accounts are provisioned server-side.
     */
    LoginResponse register(String username, String displayName, String password)
            throws ApiException;

    ContestStateDto currentContest() throws ApiException;

    /** Contests the student may enter, for the picker shown after login. */
    List<ContestSummaryDto> contests() throws ApiException;

    /**
     * Live state of the chosen contest — drives the countdown (FR-06).
     *
     * @param password required only when the contest's
     *                 {@link com.arbitrator.common.dto.ContestSummaryDto#passwordProtected()}
     *                 flag is set; null/ignored otherwise. Never trust the
     *                 client's own flag for enforcement — the server
     *                 re-checks against the stored hash regardless.
     */
    ContestStateDto contest(long id, String password) throws ApiException;

    List<ProblemSummaryDto> problems() throws ApiException;

    ProblemDetailDto problem(long id) throws ApiException;

    /** Raw PDF bytes for a problem whose {@code pdfStatement} flag is set. */
    byte[] problemStatementPdf(long id) throws ApiException;

    /** FR-07: every announcement in this contest, newest first. */
    List<AnnouncementDto> announcements(long contestId) throws ApiException;

    /** The public clarification board; the asker's name is never included. */
    List<ClarificationDto> clarifications(long contestId) throws ApiException;

    /**
     * @param problemId null when the question is about the contest itself
     * @param isPublic  true to join the public board everyone reads; false to
     *                  keep it between the asker and the instructor
     */
    ClarificationDto askClarification(Long problemId, long contestId, String question,
                                      boolean isPublic) throws ApiException;

    /** Whether this contest lets an asker mark a clarification private (V61). */
    boolean clarificationPrivacyAllowed(long contestId) throws ApiException;

    SubmitAckDto submit(SubmitRequest request) throws ApiException;

    /** Run against custom input without submitting — never judged or stored. */
    CustomRunResultDto runCustom(CustomRunRequest request) throws ApiException;

    /**
     * FR-16. {@code all=false} scopes to {@code contestId} (a clone starts
     * with none of its own); {@code all=true} is every submission this
     * account has ever made, across every contest, ignoring contestId.
     */
    List<SubmissionHistoryDto> mySubmissions(long contestId, boolean all) throws ApiException;

    /** UIF-12: the code behind one of my submissions. */
    SubmissionSourceDto submissionSource(long submissionId) throws ApiException;

    /**
     * The tests behind one of my verdicts — those I passed plus the one that
     * failed me. The result carries {@code visible=false} when the instructor
     * has not enabled it for that contest, so this is never an error path.
     */
    SubmissionTestsDto submissionTests(long submissionId) throws ApiException;

    /** Current standings over REST — first paint and after a reconnect (FR-17). */
    LeaderboardDto leaderboard() throws ApiException;

    /** Opens the WebSocket and routes verdict pushes to the consumer (FR-15). */
    void connectVerdicts(Consumer<VerdictEventDto> onVerdict) throws ApiException;

    /** Subscribes to the 30 s standings broadcast for this contest (FR-17). */
    void connectLeaderboard(long contestId, Consumer<LeaderboardDto> onUpdate) throws ApiException;

    /**
     * Subscribes to contest state (FR-06). Without this the countdown runs
     * purely locally and ignores a pause, an extension or the contest ending.
     */
    void connectContestState(long contestId, Consumer<ContestStateDto> onState) throws ApiException;

    /** FR-07: a new announcement, which the client pops up immediately. */
    void connectAnnouncements(long contestId, Consumer<AnnouncementDto> onAnnouncement)
            throws ApiException;

    /**
     * The clarification board changed. The payload carries no content on
     * purpose — what an admin may see differs from what a contestant may — so
     * the callback simply re-reads the board it is entitled to.
     */
    void connectClarifications(long contestId, Runnable onChanged) throws ApiException;

    /** Quick reachability probe for the login screen's status dot (UIF-01). */
    boolean ping();

    /**
     * True when the push channel is actually open. A dead socket must be
     * visible (NFR-R03) — otherwise verdicts and standings silently stop and
     * the app merely looks idle.
     */
    boolean isLive();

    /**
     * Abandons the current push channel so the next connect starts over. Used
     * when the server address changes mid-session: without it the client keeps
     * trying to reach the machine it can no longer see.
     */
    void dropConnection();

    @Override
    void close();

    /** Single checked exception the UI has to deal with. */
    class ApiException extends Exception {

        private final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
            this.status = -1;
        }

        /** HTTP status, or -1 for transport-level failures. */
        public int status() {
            return status;
        }
    }
}
