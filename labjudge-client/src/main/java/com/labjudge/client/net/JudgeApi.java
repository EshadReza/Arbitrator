package com.labjudge.client.net;

import java.util.List;
import java.util.function.Consumer;

import com.labjudge.common.dto.ContestStateDto;
import com.labjudge.common.dto.LeaderboardDto;
import com.labjudge.common.dto.LoginResponse;
import com.labjudge.common.dto.ProblemDetailDto;
import com.labjudge.common.dto.ProblemSummaryDto;
import com.labjudge.common.dto.SubmissionHistoryDto;
import com.labjudge.common.dto.SubmitAckDto;
import com.labjudge.common.dto.SubmitRequest;
import com.labjudge.common.dto.VerdictEventDto;

/**
 * The client's ONLY window onto the server. Two implementations:
 * HttpJudgeApi (real) and FakeJudgeApi (canned data, -Dlabjudge.mock=true) —
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

    List<ProblemSummaryDto> problems() throws ApiException;

    ProblemDetailDto problem(long id) throws ApiException;

    SubmitAckDto submit(SubmitRequest request) throws ApiException;

    List<SubmissionHistoryDto> mySubmissions() throws ApiException;

    /** Current standings over REST — first paint and after a reconnect (FR-17). */
    LeaderboardDto leaderboard() throws ApiException;

    /** Opens the WebSocket and routes verdict pushes to the consumer (FR-15). */
    void connectVerdicts(Consumer<VerdictEventDto> onVerdict) throws ApiException;

    /** Subscribes to the 30 s standings broadcast for this contest (FR-17). */
    void connectLeaderboard(long contestId, Consumer<LeaderboardDto> onUpdate) throws ApiException;

    /** Quick reachability probe for the login screen's status dot (UIF-01). */
    boolean ping();

    /**
     * True when the push channel is actually open. A dead socket must be
     * visible (NFR-R03) — otherwise verdicts and standings silently stop and
     * the app merely looks idle.
     */
    boolean isLive();

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
