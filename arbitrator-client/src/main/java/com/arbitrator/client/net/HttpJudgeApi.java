/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.arbitrator.client.app.ServerConfig;
import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.AnnouncementDto;
import com.arbitrator.common.dto.AttemptSummaryDto;
import com.arbitrator.common.dto.ClarificationDto;
import com.arbitrator.common.dto.ClarificationPrivacyDto;
import com.arbitrator.common.dto.ContestJoinRequest;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.CustomRunRequest;
import com.arbitrator.common.dto.CustomRunResultDto;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.dto.MaterialDto;
import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
import com.arbitrator.common.dto.SubmissionTestsDto;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.dto.VerdictEventDto;

/** Real implementation: java.net.http + Jackson against the judge server. */
public class HttpJudgeApi implements JudgeApi {

    private final ServerConfig config;
    private final HttpClient http;
    private final ObjectMapper json;
    private final StompClientAdapter stomp;

    private volatile String token;

    public HttpJudgeApi(ServerConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.json = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.stomp = new StompClientAdapter(config);
    }

    @Override
    public LoginResponse login(String username, String password, boolean force) throws ApiException {
        LoginResponse res = post(ApiPaths.AUTH_LOGIN,
                new LoginRequest(username, null, password, MacAddress.detect(), force),
                LoginResponse.class);
        this.token = res.token();
        return res;
    }

    @Override
    public LoginResponse register(String username, String displayName, String password)
            throws ApiException {
        LoginResponse res = post(ApiPaths.AUTH_REGISTER,
                new LoginRequest(username, displayName, password, MacAddress.detect(), null),
                LoginResponse.class);
        this.token = res.token();
        return res;
    }

    @Override
    public void logout() throws ApiException {
        try {
            sendRaw(builder(ApiPaths.AUTH_LOGOUT).POST(HttpRequest.BodyPublishers.noBody()).build());
        } finally {
            // Otherwise builder() keeps attaching this now-invalidated token to
            // the very next login attempt; JwtAuthFilter sees its sid no longer
            // matches ActiveSessionRegistry and rejects the login itself with
            // SESSION_SUPERSEDED, before it ever reaches AuthController.
            token = null;
        }
    }

    @Override
    public ContestStateDto currentContest() throws ApiException {
        return get(ApiPaths.CONTEST_CURRENT, ContestStateDto.class);
    }

    @Override
    public List<ContestSummaryDto> contests() throws ApiException {
        return getList(ApiPaths.CONTESTS, ContestSummaryDto.class);
    }

    @Override
    public ContestStateDto joinContest(long id, String password) throws ApiException {
        return post(ApiPaths.CONTESTS + "/" + id + "/join",
                new ContestJoinRequest(password), ContestStateDto.class);
    }

    @Override
    public ContestStateDto contest(long id) throws ApiException {
        return get(ApiPaths.CONTESTS + "/" + id, ContestStateDto.class);
    }

    @Override
    public List<ProblemSummaryDto> problems() throws ApiException {
        return getList(ApiPaths.PROBLEMS, ProblemSummaryDto.class);
    }

    @Override
    public ProblemDetailDto problem(long id) throws ApiException {
        return get(ApiPaths.PROBLEMS + "/" + id, ProblemDetailDto.class);
    }

    @Override
    public byte[] problemStatementPdf(long id) throws ApiException {
        try {
            HttpResponse<byte[]> res = http.send(
                    builder(ApiPaths.PROBLEMS + "/" + id + "/statement.pdf").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() >= 400) {
                throw new ApiException(res.statusCode(), "Could not load the PDF statement");
            }
            return res.body();
        } catch (IOException e) {
            throw new ApiException("Server unreachable — check the LAN connection", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
    }

    @Override
    public List<AnnouncementDto> announcements(long contestId) throws ApiException {
        return getList(ApiPaths.ANNOUNCEMENTS + "?contestId=" + contestId, AnnouncementDto.class);
    }

    @Override
    public List<MaterialDto> materials(long contestId) throws ApiException {
        return getList(ApiPaths.MATERIALS + "?contestId=" + contestId, MaterialDto.class);
    }

    @Override
    public byte[] materialBytes(long materialId) throws ApiException {
        try {
            HttpResponse<byte[]> res = http.send(
                    builder(ApiPaths.MATERIALS + "/" + materialId + "/download").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() >= 400) {
                throw new ApiException(res.statusCode(), "Could not download the material");
            }
            return res.body();
        } catch (IOException e) {
            throw new ApiException("Server unreachable — check the LAN connection", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
    }

    @Override
    public List<ClarificationDto> clarifications(long contestId) throws ApiException {
        return getList(ApiPaths.CLARIFICATIONS + "?contestId=" + contestId, ClarificationDto.class);
    }

    @Override
    public ClarificationDto askClarification(Long problemId, long contestId, String question,
                                             boolean isPublic) throws ApiException {
        return post(ApiPaths.CLARIFICATIONS,
                new AskClarification(problemId, contestId, question, isPublic), ClarificationDto.class);
    }

    /** Mirrors ClarificationController.AskRequest. */
    private record AskClarification(Long problemId, Long contestId, String question, Boolean isPublic) {
    }

    @Override
    public boolean clarificationPrivacyAllowed(long contestId) throws ApiException {
        return get(ApiPaths.CONTESTS + "/" + contestId + "/clarification-privacy",
                ClarificationPrivacyDto.class).allowed();
    }

    @Override
    public SubmitAckDto submit(SubmitRequest request) throws ApiException {
        return post(ApiPaths.SUBMISSIONS, request, SubmitAckDto.class);
    }

    @Override
    public CustomRunResultDto runCustom(CustomRunRequest request) throws ApiException {
        return post(ApiPaths.RUN_CUSTOM, request, CustomRunResultDto.class);
    }

    @Override
    public List<SubmissionHistoryDto> mySubmissions(long contestId, boolean all) throws ApiException {
        return getList(ApiPaths.SUBMISSIONS_MINE + "?contestId=" + contestId + "&all=" + all,
                SubmissionHistoryDto.class);
    }

    @Override
    public SubmissionSourceDto submissionSource(long submissionId) throws ApiException {
        return get(ApiPaths.SUBMISSIONS + "/" + submissionId + "/source",
                SubmissionSourceDto.class);
    }

    @Override
    public SubmissionTestsDto submissionTests(long submissionId) throws ApiException {
        return get(ApiPaths.SUBMISSIONS + "/" + submissionId + "/tests",
                SubmissionTestsDto.class);
    }

    @Override
    public LeaderboardDto leaderboard() throws ApiException {
        return get(ApiPaths.LEADERBOARD, LeaderboardDto.class);
    }

    @Override
    public List<AttemptSummaryDto> problemAttempts(long contestId, String username, String problemCode)
            throws ApiException {
        String path = "/api/contests/" + contestId + "/participants/"
                + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                + "/problems/"
                + java.net.URLEncoder.encode(problemCode, java.nio.charset.StandardCharsets.UTF_8)
                + "/attempts";
        return getList(path, AttemptSummaryDto.class);
    }

    @Override
    public void connectVerdicts(Consumer<VerdictEventDto> onVerdict) throws ApiException {
        requireSocket();
        stomp.subscribeVerdicts(onVerdict);
    }

    @Override
    public void connectLeaderboard(long contestId, Consumer<LeaderboardDto> onUpdate)
            throws ApiException {
        requireSocket();
        stomp.subscribeLeaderboard(contestId, onUpdate);
    }

    @Override
    public void connectContestState(long contestId, Consumer<ContestStateDto> onState)
            throws ApiException {
        requireSocket();
        stomp.subscribeContestState(contestId, onState);
    }

    @Override
    public void connectAnnouncements(long contestId, Consumer<AnnouncementDto> onAnnouncement)
            throws ApiException {
        requireSocket();
        stomp.subscribeAnnouncements(contestId, onAnnouncement);
    }

    @Override
    public void connectClarifications(long contestId, Runnable onChanged) throws ApiException {
        requireSocket();
        stomp.subscribeClarifications(contestId, onChanged);
    }

    @Override
    public void connectMaterials(long contestId, Runnable onChanged) throws ApiException {
        requireSocket();
        stomp.subscribeMaterials(contestId, onChanged);
    }

    private void requireSocket() throws ApiException {
        if (token == null) {
            throw new ApiException(-1, "Not logged in");
        }
        stomp.connect(token);
    }

    @Override
    public boolean ping() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/auth/login"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(2))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
            return true;      // any HTTP answer means the server is reachable
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    @Override
    public boolean isLive() {
        return stomp.isConnected();
    }

    @Override
    public void dropConnection() {
        stomp.disconnect();
    }

    @Override
    public void close() {
        stomp.close();
    }

    // ------------------------------------------------------------------

    private <T> T get(String path, Class<T> type) throws ApiException {
        return send(builder(path).GET().build(), type);
    }

    private <T> List<T> getList(String path, Class<T> elementType) throws ApiException {
        String body = sendRaw(builder(path).GET().build());
        try {
            return json.readerForListOf(elementType).readValue(body);
        } catch (IOException e) {
            throw new ApiException("Malformed response from server", e);
        }
    }

    private <T> T post(String path, Object payload, Class<T> type) throws ApiException {
        try {
            HttpRequest req = builder(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            return send(req, type);
        } catch (IOException e) {
            throw new ApiException("Could not encode request", e);
        }
    }

    private HttpRequest.Builder builder(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private <T> T send(HttpRequest req, Class<T> type) throws ApiException {
        String body = sendRaw(req);
        try {
            return json.readValue(body, type);
        } catch (IOException e) {
            throw new ApiException("Malformed response from server", e);
        }
    }

    private String sendRaw(HttpRequest req) throws ApiException {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new ApiException(res.statusCode(), friendlyError(res));
            }
            return res.body();
        } catch (IOException e) {
            throw new ApiException("Server unreachable — check the LAN connection", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
    }

    private String friendlyError(HttpResponse<String> res) {
        try {
            var node = json.readTree(res.body());
            if (node.hasNonNull("message") && !node.get("message").asText().isBlank()) {
                return node.get("message").asText();
            }
        } catch (IOException ignored) {
            // fall through to generic text
        }
        return switch (res.statusCode()) {
            case 401 -> "Invalid credentials";                        // UIF-03
            case 403 -> "Not allowed — the contest may have ended";   // BR-02
            case 429 -> "Please wait before submitting again";        // BR-01
            default -> "Server error (HTTP " + res.statusCode() + ")";
        };
    }
}
