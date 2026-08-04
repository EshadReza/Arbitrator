package com.arbitrator.client.net;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.common.dto.CustomRunRequest;
import com.arbitrator.common.dto.CustomRunResultDto;
import com.arbitrator.common.dto.LeaderboardCellDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionSourceDto;
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

    @Override
    public LoginResponse login(String username, String password) {
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
                        now - 15 * 60_000, now + 105 * 60_000, 120, 3, true),
                new ContestSummaryDto(2, "Practice Round (MOCK)", ContestState.PAUSED,
                        now - 60 * 60_000, now + 30 * 60_000, 90, 5, true));
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
                2000, 262144);
    }

    @Override
    public SubmitAckDto submit(SubmitRequest request) {
        long id = ids.incrementAndGet();
        String src = request.sourceCode().replace(" ", "");
        Verdict verdict = src.contains("a+b") ? Verdict.AC : cycle[cycleAt++ % cycle.length];

        history.add(0, new SubmissionHistoryDto(id, "A", request.language(), null,
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
    public List<SubmissionHistoryDto> mySubmissions() {
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
    public LeaderboardDto leaderboard() {
        return fakeBoard();
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

    /** Enough rows and shapes to exercise every cell state in UIF-13. */
    private LeaderboardDto fakeBoard() {
        List<String> codes = List.of("A", "B", "C");
        List<LeaderboardRowDto> rows = List.of(
                row(1, "bob", "Bob", 3, 178,
                        cell("A", true, 0, 12), cell("B", true, 1, 44), cell("C", true, 2, 82)),
                row(2, "alice", "Alice", 2, 96,
                        cell("A", true, 0, 16), cell("B", true, 2, 40), cell("C", false, 3, -1)),
                row(3, "carol", "Carol", 1, 21,
                        cell("A", true, 1, 1), cell("B", false, 0, -1), cell("C", false, 0, -1)));
        return new LeaderboardDto(1, false, System.currentTimeMillis(), codes, rows);
    }

    private static LeaderboardRowDto row(int rank, String user, String display,
                                         int solved, long penalty, LeaderboardCellDto... cells) {
        return new LeaderboardRowDto(rank, user, display, solved, penalty, List.of(cells));
    }

    private static LeaderboardCellDto cell(String code, boolean solved, int failed, long at) {
        return new LeaderboardCellDto(code, solved, failed, at);
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
    public void close() {
        timer.shutdownNow();
    }
}
