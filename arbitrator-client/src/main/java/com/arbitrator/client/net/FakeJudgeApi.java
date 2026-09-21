/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.net;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.arbitrator.common.dto.AnnouncementDto;
import com.arbitrator.common.dto.AttemptSummaryDto;
import com.arbitrator.common.dto.ClarificationDto;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.common.dto.CustomRunRequest;
import com.arbitrator.common.dto.CustomRunResultDto;
import com.arbitrator.common.dto.LeaderboardCellDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.dto.MaterialDto;
import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
import com.arbitrator.common.dto.SubmissionTestsDto;
import com.arbitrator.common.dto.TestCaseResultDto;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.dto.VerdictEventDto;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Role;
import com.arbitrator.common.enums.Verdict;

/**
 * Canned server for UI development: -Darbitrator.mock=true.
 * Any credentials log in; verdicts arrive ~2 s after submit; a source
 * containing "a + b" (or "a+b") gets AC, anything else cycles the other
 * verdicts so every banner colour can be eyeballed (UIF-10).
 * This is what lets Zahin demo the entire client before the server exists.
 */
public class FakeJudgeApi implements JudgeApi {

    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fake-judge");
                t.setDaemon(true);
                return t;
            });

    private final AtomicLong ids = new AtomicLong(100);
    private final List<SubmissionHistoryDto> history = new ArrayList<>();
    private final Verdict[] cycle = { Verdict.WA, Verdict.TLE, Verdict.MLE, Verdict.CE, Verdict.RE };
    private int cycleAt = 0;

    private volatile Consumer<VerdictEventDto> onVerdict;
    private volatile Runnable onClarifications;
    private final List<AnnouncementDto> fakeAnnouncements = new ArrayList<>();
    private final List<ClarificationDto> fakeClarifications = new ArrayList<>();
    private final List<MaterialDto> fakeMaterials = new ArrayList<>(List.of(
            new MaterialDto(1, 1, "Lecture 1 - Introduction.pdf", "application/pdf",
                    2_300_000, System.currentTimeMillis()),
            new MaterialDto(2, 1, "Sample Slides.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    5_100_000, System.currentTimeMillis())));

    @Override
    public LoginResponse login(String username, String password, boolean force) {
        return new LoginResponse("fake-token", username,
                username.substring(0, 1).toUpperCase() + username.substring(1), Role.STUDENT);
    }

    @Override
    public LoginResponse register(String username, String displayName, String password) {
        return new LoginResponse("fake-token", username,
                displayName == null || displayName.isBlank() ? username : displayName,
                Role.STUDENT);
    }

    @Override
    public void logout() {
        // No real session/presence tracking to release in mock mode.
    }

    @Override
    public ContestStateDto currentContest() {
        long now = System.currentTimeMillis();
        return new ContestStateDto(1, "Lab Contest #1 (MOCK)", ContestState.ACTIVE,
                now, now - 15 * 60_000, now + 105 * 60_000);
    }

    @Override
    public List<ContestSummaryDto> contests() {
        long now = System.currentTimeMillis();
        return List.of(
                new ContestSummaryDto(1, "Lab Contest #1 (MOCK)", ContestState.ACTIVE,
                        now - 15 * 60_000, now + 105 * 60_000, 120, 3, true, false),
                // Password-protected in mock mode too ("practice"), so the
                // client's password-prompt flow has something to exercise
                // without a real server.
                new ContestSummaryDto(2, "Practice Round (MOCK)", ContestState.PAUSED,
                        now - 60 * 60_000, now + 30 * 60_000, 90, 5, true, true));
    }

    @Override
    public ContestStateDto joinContest(long id, String password) throws ApiException {
        if (id == 2 && !"practice".equals(password)) {
            throw new ApiException(403, "Wrong contest password");
        }
        return currentContest();
    }

    @Override
    public ContestStateDto contest(long id) {
        return currentContest();
    }

    @Override
    public List<ProblemSummaryDto> problems() {
        return List.of(
                new ProblemSummaryDto(1, "A", "Two Sum", false, 0),
                new ProblemSummaryDto(2, "B", "Sorting", false, 2),
                new ProblemSummaryDto(3, "C", "Shortest Path", true, 1));
    }

    @Override
    public ProblemDetailDto problem(long id) {
        return new ProblemDetailDto(id, id == 1 ? "A" : id == 2 ? "B" : "C",
                id == 1 ? "Two Sum" : id == 2 ? "Sorting" : "Shortest Path",
                """
                <h1>A. Two Sum</h1>
                <div class="limits">time limit: 2 s &nbsp; memory limit: 256 MB</div>
                <p>Given two integers <i>a</i> and <i>b</i>, print their sum.</p>
                <h2>Input</h2><p>A single line with two integers.</p>
                <h2>Output</h2><p>One integer &mdash; the sum.</p>
                <h2>Example</h2>
                <pre class="sample">input
                2 3
                output
                5</pre>
                """,
                2000, 262144, false);
    }

    @Override
    public SubmitAckDto submit(SubmitRequest request) {
        long id = ids.incrementAndGet();
        String src = request.sourceCode().replace(" ", "");
        Verdict verdict = src.contains("a+b") ? Verdict.AC : cycle[cycleAt++ % cycle.length];

        history.add(0, new SubmissionHistoryDto(id, 1, "A", request.language(), null,
                -1, -1, System.currentTimeMillis(), 0, 10));

        timer.schedule(() -> {
            Consumer<VerdictEventDto> c = onVerdict;
            if (c != null) {
                c.accept(new VerdictEventDto(id, request.problemId(), verdict,
                        verdict == Verdict.AC ? 42 : 1999,
                        verdict == Verdict.MLE ? 524288 : 3200,
                        verdict == Verdict.CE
                                ? "main.cpp:4:5: error: expected ';' before 'return'"
                                : null,
                        verdict == Verdict.AC ? -1 : 2));
            }
        }, 2, TimeUnit.SECONDS);

        return new SubmitAckDto(id, 1);
    }

    @Override
    public CustomRunResultDto runCustom(CustomRunRequest request) {
        return new CustomRunResultDto(true, "",
                "(mock) echoed input:\n" + request.input(), "", 12, false);
    }

    @Override
    public List<SubmissionHistoryDto> mySubmissions(long contestId, boolean all) {
        return List.copyOf(history);
    }

    @Override
    public SubmissionSourceDto submissionSource(long submissionId) {
        return new SubmissionSourceDto(submissionId, "A", "alice", Language.CPP17,
                Verdict.AC,
                "#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;"
                        + "std::cout<<a+b<<\"\\n\";}",
                null, System.currentTimeMillis());
    }

    @Override
    public SubmissionTestsDto submissionTests(long submissionId) {
        // Mock mode shows the feature switched on, with the fail-fast shape:
        // everything up to the failure, then the test that caused it.
        return new SubmissionTestsDto(submissionId, true, 2, 5, List.of(
                new TestCaseResultDto(1, Verdict.AC, 12, 2048, "2 3\n", "5\n", "5\n", false),
                new TestCaseResultDto(2, Verdict.AC, 11, 2048, "-5 5\n", "0\n", "0\n", false),
                new TestCaseResultDto(3, Verdict.WA, 14, 2048,
                        "1000000000 1000000000\n", "2000000000\n", "-294967296\n", false)),
                null);
    }

    @Override
    public LeaderboardDto leaderboard() {
        return fakeBoard();
    }

    @Override
    public List<AttemptSummaryDto> problemAttempts(long contestId, String username, String problemCode) {
        long now = System.currentTimeMillis();
        return List.of(
                new AttemptSummaryDto(9001, Language.CPP17, Verdict.AC, 44, now - 60_000,
                        -1, 3, 3),
                new AttemptSummaryDto(9000, Language.CPP17, Verdict.WA, 39, now - 300_000,
                        2, 1, 3));
    }

    @Override
    public void connectVerdicts(Consumer<VerdictEventDto> onVerdict) {
        this.onVerdict = onVerdict;
    }

    @Override
    public void connectContestState(long contestId, Consumer<ContestStateDto> onState) {
        timer.scheduleAtFixedRate(
                () -> onState.accept(currentContest()), 2, 10, TimeUnit.SECONDS);
    }

    /** Ticks every 5 s instead of 30 so UI work doesn't involve waiting. */
    @Override
    public void connectLeaderboard(long contestId, Consumer<LeaderboardDto> onUpdate) {
        timer.scheduleAtFixedRate(
                () -> onUpdate.accept(fakeBoard()), 1, 5, TimeUnit.SECONDS);
    }

    /**
     * Enough rows and shapes to exercise every cell state in UIF-13: a first
     * solve (dark green) in each column, ordinary accepts with and without
     * prior attempts, a tried-and-failed cell, a compile-error-only cell, and
     * columns nobody has touched.
     */
    private LeaderboardDto fakeBoard() {
        List<String> codes = List.of("A", "B", "C");
        List<LeaderboardRowDto> rows = List.of(
                row(1, "bob", "Bob", 3, 178,
                        ac("A", 0, 12, false), ac("B", 1, 44, true), ac("C", 2, 82, true)),
                row(2, "alice", "Alice", 2, 96,
                        ac("A", 0, 16, false), ac("B", 2, 40, false), tried("C", 3)),
                row(3, "carol", "Carol", 1, 21,
                        ac("A", 1, 1, true), ce("B", 2), untouched("C")));
        return new LeaderboardDto(1, false, System.currentTimeMillis(), codes, rows);
    }

    private static LeaderboardRowDto row(int rank, String user, String display,
                                         int solved, long penalty, LeaderboardCellDto... cells) {
        return new LeaderboardRowDto(rank, user, display, solved, penalty, List.of(cells));
    }

    /** Accepted at {@code atMinutes}, with a few seconds of jitter so the clock reads real. */
    private static LeaderboardCellDto ac(String code, int failed, long atMinutes, boolean first) {
        return new LeaderboardCellDto(code, true, failed, atMinutes,
                atMinutes * 60 + (atMinutes % 47), first, 0);
    }

    private static LeaderboardCellDto tried(String code, int failed) {
        return new LeaderboardCellDto(code, false, failed, -1, -1, false, 0);
    }

    private static LeaderboardCellDto untouched(String code) {
        return new LeaderboardCellDto(code, false, 0, -1, -1, false, 0);
    }

    /** BR-04: compile errors alone never count as a reject, but still show as touched. */
    private static LeaderboardCellDto ce(String code, int ceAttempts) {
        return new LeaderboardCellDto(code, false, 0, -1, -1, false, ceAttempts);
    }

    @Override
    public boolean ping() {
        return true;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public void dropConnection() {
        // Mock mode has no socket to drop; it is always "connected".
    }

    @Override
    public byte[] problemStatementPdf(long id) throws ApiException {
        // Mock problems are HTML, so nothing should ever ask for this.
        throw new ApiException(404, "No PDF statement in mock mode");
    }

    @Override
    public List<AnnouncementDto> announcements(long contestId) {
        return List.copyOf(fakeAnnouncements);
    }

    @Override
    public List<ClarificationDto> clarifications(long contestId) {
        return List.copyOf(fakeClarifications);
    }

    @Override
    public List<MaterialDto> materials(long contestId) {
        return List.copyOf(fakeMaterials);
    }

    @Override
    public byte[] materialBytes(long materialId) {
        return ("Mock material #" + materialId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public ClarificationDto askClarification(Long problemId, long contestId, String question,
                                             boolean isPublic) {
        ClarificationDto asked = new ClarificationDto(
                ids.incrementAndGet(),
                problemId == null ? null : "A", problemId == null ? null : "Two Sum",
                question, null, System.currentTimeMillis(), -1, null, isPublic, false);
        fakeClarifications.add(0, asked);
        // Answer it on a timer so the "answered" path is demoable without a server.
        // Mock mode has no admin console to click Approve in, so the canned
        // answer arrives pre-approved — otherwise the demo path would dead-end
        // exactly where the real approval gate is meant to require a real admin.
        timer.schedule(() -> {
            fakeClarifications.remove(asked);
            fakeClarifications.add(0, new ClarificationDto(
                    asked.id(), asked.problemCode(), asked.problemTitle(), asked.question(),
                    "(mock) Yes — read the constraints again.",
                    asked.askedAtMs(), System.currentTimeMillis(), null, asked.isPublic(), true));
            Runnable r = onClarifications;
            if (r != null) {
                r.run();
            }
        }, 4, TimeUnit.SECONDS);
        return asked;
    }

    @Override
    public boolean clarificationPrivacyAllowed(long contestId) {
        return true;   // mock mode always offers the private option
    }

    @Override
    public void connectAnnouncements(long contestId, Consumer<AnnouncementDto> onAnnouncement) {
        // Fire one shortly after connecting so the popup can be eyeballed.
        timer.schedule(() -> {
            AnnouncementDto a = new AnnouncementDto(ids.incrementAndGet(), contestId,
                    "Clarification for everyone: in problem B, assume "
                            + "1 &le; <i>n</i> &le; 10<sup>5</sup>.",
                    System.currentTimeMillis());
            fakeAnnouncements.add(0, a);
            onAnnouncement.accept(a);
        }, 6, TimeUnit.SECONDS);
    }

    @Override
    public void connectClarifications(long contestId, Runnable onChanged) {
        this.onClarifications = onChanged;
    }

    @Override
    public void connectMaterials(long contestId, Runnable onChanged) {
        // Nothing changes the canned list on its own; the tab is still fully
        // browsable/downloadable without a real push.
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
