package com.arbitrator.client.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.arbitrator.client.app.AppState;
import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Language;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.util.Duration;

/**
 * Main contest view (SRS §4.2/4.3/4.4).
 *
 * The countdown is driven by contest state pushed from the server (FR-06), not
 * by a purely local timer — otherwise pausing, extending or ending the contest
 * on the server changes nothing on screen.
 */
public class MainController {

    @FXML private StackPane rootStack;
    @FXML private TabPane tabs;
    @FXML private Label contestTitleLabel;
    @FXML private Label timerLabel;
    @FXML private Label userLabel;
    @FXML private Label connLabel;
    @FXML private Button themeButton;

    @FXML private SplitPane workspaceSplit;
    @FXML private VBox problemPane;
    @FXML private VBox statementPane;
    @FXML private ListView<ProblemSummaryDto> problemList;
    @FXML private Label problemHeader;
    @FXML private WebView statementView;
    @FXML private Button statementFsButton;
    @FXML private Button collapseButton;
    @FXML private Label layoutHint;

    // injected from <fx:include fx:id="..."> in main.fxml
    @FXML private EditorController editorPanelController;
    @FXML private LeaderboardPanelController leaderboardPanelController;
    @FXML private SubmissionsPanelController submissionsPanelController;

    private final AppState state = AppState.get();
    private Timeline timer;
    private Timeline watchdog;
    private FadeTransition pulse;
    private boolean ended;

    /** Guards against the watchdog stacking reconnect attempts. */
    private volatile boolean reconnecting;

    /**
     * Per-problem editor drafts, kept for the session. Switching problems must
     * not discard work — losing code mid-contest is unforgivable.
     */
    private final Map<Long, Draft> drafts = new HashMap<>();
    private long currentProblemId = -1;

    private record Draft(String code, Language language) { }

    /** Panes hidden by a fullscreen or collapse toggle, in their original order. */
    private List<Node> workspacePanes;
    private boolean problemListHidden;
    private Node fullscreenPane;

    @FXML
    private void initialize() {
        contestTitleLabel.setText(state.contest().title());
        userLabel.setText(state.session().displayName());
        workspacePanes = List.copyOf(workspaceSplit.getItems());

        problemList.setCellFactory(v -> new ProblemCell());
        problemList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> {
                    // A background refresh (every ~10s heartbeat or after a verdict)
                    // replaces the ListView's items, which briefly clears and then
                    // re-establishes the selection even when it's the same problem.
                    // Without this guard that spurious reselect calls restoreDraft()
                    // -> setCode() -> moveTo(end), yanking the caret every refresh.
                    if (sel == null || sel.id() == currentProblemId) {
                        return;
                    }
                    if (currentProblemId != -1) {
                        stashDraft(currentProblemId);
                    }
                    currentProblemId = sel.id();
                    restoreDraft(sel.id());
                    loadProblem(sel.id());
                });

        // A WebView paints its own opaque white backdrop behind the page, so a
        // dark statement needs the fill changed too — page CSS alone can't fix it.
        applyWebViewFill();
        editorPanelController.setSubmitHandler(this::submit);
        editorPanelController.setFullscreenHandler(this::toggleEditorFullscreen);
        editorPanelController.setRunHandler(this::runCustom);
        themeButton.setText(state.darkMode() ? "☀" : "🌙");

        startTimer();
        connectLive();
        startConnectionWatchdog();
        refreshProblems(true);

        Platform.runLater(this::installShortcuts);
    }

    private void installShortcuts() {
        var accelerators = rootStack.getScene().getAccelerators();
        // NFR-U04: everything reachable without a mouse.
        accelerators.put(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN),
                editorPanelController::fireSubmit);
        accelerators.put(new KeyCodeCombination(KeyCode.D, KeyCombination.CONTROL_DOWN),
                this::onToggleTheme);
        accelerators.put(new KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN),
                this::onToggleProblemList);
        accelerators.put(new KeyCodeCombination(KeyCode.F11),
                this::onToggleStatementFullscreen);
        accelerators.put(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN),
                this::onRefresh);
    }

    // --- layout: collapse and fullscreen -----------------------------------

    /** Hides the problem list so the statement and editor get the width. */
    @FXML
    private void onToggleProblemList() {
        if (fullscreenPane != null) {
            return;                       // fullscreen already governs the layout
        }
        problemListHidden = !problemListHidden;
        rebuildWorkspace();
        collapseButton.setText(problemListHidden ? "◨ Show problem list" : "◧ Hide problem list");
    }

    @FXML
    private void onToggleStatementFullscreen() {
        toggleFullscreen(statementPane, statementFsButton);
    }

    /** Called by the editor panel's own fullscreen button. */
    public void toggleEditorFullscreen() {
        toggleFullscreen(workspacePanes.get(2), editorPanelController.fullscreenButton());
    }

    private void toggleFullscreen(Node pane, Button button) {
        boolean entering = fullscreenPane != pane;
        fullscreenPane = entering ? pane : null;
        rebuildWorkspace();

        statementFsButton.setText("⛶");
        editorPanelController.fullscreenButton().setText("⛶");
        if (entering && button != null) {
            button.setText("⤢");          // click again to restore
        }
        layoutHint.setText(entering ? "Fullscreen — press again or Esc to restore" : "");
        collapseButton.setDisable(entering);
    }

    /** Single place that decides which panes are in the SplitPane. */
    private void rebuildWorkspace() {
        if (fullscreenPane != null) {
            workspaceSplit.getItems().setAll(fullscreenPane);
            return;
        }
        workspaceSplit.getItems().setAll(workspacePanes.stream()
                .filter(p -> !(problemListHidden && p == problemPane))
                .toList());
        workspaceSplit.setDividerPositions(problemListHidden
                ? new double[] { 0.5 }
                : new double[] { 0.20, 0.60 });
    }

    private void applyWebViewFill() {
        statementView.setPageFill(state.darkMode()
                ? Color.web("#1c222c") : Color.web("#ffffff"));
    }

    // --- session actions ------------------------------------------------------

    /**
     * Re-pulls everything from the server. Push channels normally keep the UI
     * current, but a manual refresh means a missed update never requires
     * restarting the application.
     */
    @FXML
    private void onRefresh() {
        submissionsPanelController.refresh();
        async(() -> {
            try {
                long id = state.contest().contestId();
                // Contest state first: it decides whether problems are released
                // at all, so fetching problems before it can show a stale list.
                var contestState = state.api().contest(id);
                onContestState(contestState);
                if (contestState.state().releasesProblems()) {
                    List<ProblemSummaryDto> items = state.api().problems();
                    Platform.runLater(() -> applyProblems(items, false));
                }
                var board = state.api().leaderboard();
                leaderboardPanelController.update(board);
                Platform.runLater(() -> toast(bundle("main.refreshed")));
            } catch (Exception e) {
                Platform.runLater(() -> toast("Refresh failed: " + e.getMessage()));
            }
        });
    }

    /** Back to the picker, keeping the session — same user, different contest. */
    @FXML
    private void onSwitchContest() {
        stopClocks();
        SceneRouter.showContestPicker();
    }

    @FXML
    private void onSignOut() {
        stopClocks();
        state.setSession(null);
        state.setContestCleared();
        SceneRouter.showLogin();
    }

    /** Timers keep firing against a dead scene otherwise. */
    private void stopClocks() {
        if (timer != null) {
            timer.stop();
        }
        if (watchdog != null) {
            watchdog.stop();
        }
        stopPulse();
    }

    // --- theme --------------------------------------------------------------

    @FXML
    private void onToggleTheme() {
        boolean dark = !state.darkMode();
        state.setDarkMode(dark);
        SceneRouter.applyTheme(rootStack.getScene());
        applyWebViewFill();
        themeButton.setText(dark ? "☀" : "🌙");
        // Re-render the statement so its inline CSS matches the new theme.
        var selected = problemList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            loadProblem(selected.id());
        }
    }

    // --- problems -----------------------------------------------------------

    private void refreshProblems(boolean selectFirst) {
        async(() -> {
            try {
                List<ProblemSummaryDto> items = state.api().problems();
                Platform.runLater(() -> applyProblems(items, selectFirst));
            } catch (Exception e) {
                Platform.runLater(() -> toast("Could not load problems: " + e.getMessage()));
            }
        });
    }

    /** Applies a problem list, keeping the current selection where possible. */
    private void applyProblems(List<ProblemSummaryDto> items, boolean selectFirst) {
        var selected = problemList.getSelectionModel().getSelectedItem();
        problemList.getItems().setAll(items);
        if (items.isEmpty()) {
            return;
        }
        if (selectFirst || selected == null) {
            problemList.getSelectionModel().select(0);
            return;
        }
        items.stream().filter(p -> p.id() == selected.id()).findFirst()
                .ifPresentOrElse(p -> problemList.getSelectionModel().select(p),
                        () -> problemList.getSelectionModel().select(0));
    }

    private void loadProblem(long id) {
        async(() -> {
            try {
                ProblemDetailDto detail = state.api().problem(id);
                Platform.runLater(() -> {
                    problemHeader.setText(detail.code() + ". " + detail.title()
                            + "   —   " + detail.timeLimitMs() + " ms / "
                            + detail.memoryLimitKb() / 1024 + " MB");
                    statementView.getEngine().loadContent(wrapStatement(detail.statementHtml()));
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        statementView.getEngine().loadContent("<p>Problem unavailable</p>"));
            }
        });
    }

    /** Statement styling follows the app theme, inline so WebView needs no assets. */
    private String wrapStatement(String html) {
        boolean dark = state.darkMode();
        String bg = dark ? "#1c222c" : "#ffffff";
        String fg = dark ? "#e6eaf0" : "#1c2430";
        String muted = dark ? "#9aa5b4" : "#6b7684";
        String sampleBg = dark ? "#232a36" : "#f5f7fa";
        String border = dark ? "#313a49" : "#d6dbe3";
        String link = dark ? "#7fb4ff" : "#2f6fd0";
        return """
                <html><head><meta charset="utf-8"><style>
                  html, body { color: %s !important; background: %s !important; }
                  body { font-family: -apple-system, "Segoe UI", sans-serif; font-size: 14px;
                         margin: 18px; line-height: 1.55; }
                  /* Uploaded statements are whole HTML documents carrying their own
                     <style> block — typically light-theme colours such as
                     `code{background:#f3f4f6}`. That block appears *after* this one
                     in the document, so without !important it wins on source order
                     and leaves white boxes; combined with the forced light text
                     below that is white-on-white and completely unreadable.
                     Everything the uploaded sheet can set has to be reclaimed. */
                  *, h1, h2, h3, h4, h5, h6, p, li, td, th, div, span, b, i, em, strong,
                  code, pre, blockquote, sup, sub, table, caption { color: %s !important; }
                  /* Wildcard first, then the specific rules below out-specify it
                     (both !important, so specificity decides the winner). */
                  * { background: transparent !important; background-color: transparent !important; }
                  a { color: %s !important; }
                  h1 { font-size: 19px; margin: 0 0 4px; }
                  h2 { font-size: 15px; margin-top: 20px; }
                  h1, h2, h3, h4, h5, h6 { border-color: %s !important; }
                  .limits { color: %s !important; font-size: 12px; margin-bottom: 14px; }
                  pre.sample, pre, code, kbd, samp, tt {
                                    border: 1px solid %s !important;
                                    background: %s !important;
                                    background-color: %s !important;
                                    padding: 10px; border-radius: 6px;
                                    font-family: ui-monospace, monospace;
                                    overflow-x: auto; }
                  /* Inline code sits inside a sentence — full block padding there
                     produced the tall white chips seen in the statement body. */
                  code { padding: 1px 5px; }
                  pre code { padding: 0; border: 0 !important; background: transparent !important; }
                  table { border-collapse: collapse; }
                  td, th { border: 1px solid %s !important; padding: 4px 8px; }
                  th { background: %s !important; }
                </style></head><body>""".formatted(
                        fg, bg, fg, link, border, muted, border, sampleBg, sampleBg,
                        border, sampleBg)
                + html + "</body></html>";
    }

    // --- per-problem drafts --------------------------------------------------

    private void stashDraft(long problemId) {
        drafts.put(problemId, new Draft(
                editorPanelController.getCode(), editorPanelController.getLanguage()));
    }

    private void restoreDraft(long problemId) {
        Draft draft = drafts.get(problemId);
        if (draft != null) {
            editorPanelController.setLanguage(draft.language());
        }
        editorPanelController.setCode(draft == null ? "" : draft.code());
    }

    // --- submit (FR-09, UIF-08/09) -------------------------------------------

    private void submit(Language language, String source) {
        ProblemSummaryDto problem = problemList.getSelectionModel().getSelectedItem();
        if (problem == null || ended) {
            return;
        }
        if (source == null || source.isBlank()) {
            toast(bundle("submit.empty"));
            return;
        }
        editorPanelController.setSubmitEnabled(false);
        async(() -> {
            try {
                SubmitAckDto ack = state.api().submit(
                        new SubmitRequest(problem.id(), language, source));
                Platform.runLater(() -> toast(bundle("submit.queued")
                        .replace("{n}", String.valueOf(ack.queuePosition()))));
            } catch (Exception e) {
                Platform.runLater(() -> toast(e.getMessage()));
            } finally {
                Platform.runLater(() -> {
                    if (!ended) {
                        editorPanelController.setSubmitEnabled(true);
                    }
                });
            }
        });
    }

    /**
     * Runs the current code against the student's own input. Never judged and
     * never stored, so it costs no attempt and no penalty.
     */
    private void runCustom(Language language, String source) {
        ProblemSummaryDto problem = problemList.getSelectionModel().getSelectedItem();
        if (problem == null) {
            editorPanelController.showRunResult("", "Pick a problem first");
            return;
        }
        String input = editorPanelController.getCustomInput();
        async(() -> {
            try {
                var r = state.api().runCustom(new com.arbitrator.common.dto.CustomRunRequest(
                        problem.id(), language, source, input));
                Platform.runLater(() -> {
                    if (!r.compiled()) {
                        editorPanelController.showRunResult(r.compilerOutput(),
                                "compile error");
                    } else {
                        String body = r.stdout();
                        if (!r.stderr().isBlank()) {
                            body += "\n--- stderr ---\n" + r.stderr();
                        }
                        editorPanelController.showRunResult(body,
                                r.timedOut() ? "timed out" : r.execTimeMs() + " ms");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        editorPanelController.showRunResult("", e.getMessage()));
            }
        });
    }

    // --- live channel --------------------------------------------------------

    private void connectLive() {
        async(() -> {
            try {
                state.api().connectVerdicts(event -> Platform.runLater(() -> {
                    // Clicking the banner jumps to Submissions and selects that
                    // run, so "what exactly did I send?" is one click away.
                    VerdictBanner.show(rootStack, event,
                            () -> showSubmission(event.submissionId()));
                    refreshProblems(false);              // badge update (§4.2)
                    submissionsPanelController.refresh(); // FR-16 history stays live
                }));
                long contestId = state.contest().contestId();
                state.api().connectLeaderboard(contestId,
                        board -> leaderboardPanelController.update(board));
                // FR-06: the server owns the clock and tells us when it changes.
                state.api().connectContestState(contestId, this::onContestState);
                leaderboardPanelController.update(state.api().leaderboard());
                Platform.runLater(() -> setConnected(true));
            } catch (Exception e) {
                Platform.runLater(() -> setConnected(false));
            } finally {
                reconnecting = false;
            }
        });
    }

    /** Applies a pushed contest state: clock, pause, freeze and end (FR-06). */
    private void onContestState(com.arbitrator.common.dto.ContestStateDto contestState) {
        Platform.runLater(() -> {
            ContestState previous = state.contest() == null ? null : state.contest().state();
            state.setContest(contestState);
            contestTitleLabel.setText(contestState.title());

            boolean released = contestState.state().releasesProblems();
            boolean transitioned = previous != contestState.state();

            // Refresh on EVERY push, not only on a state change: uploading a
            // problem does not change the state, and relying on transitions
            // meant one missed push left the screen stale until sign-out.
            if (released) {
                refreshProblems(transitioned);
            } else {
                problemList.getItems().clear();
                problemHeader.setText("");
                statementView.getEngine().loadContent(waitingPage());
            }
            if (transitioned) {
                toast(contestState.state() == ContestState.ACTIVE
                        ? bundle("contest.started") : bundle("contest.changed")
                                .replace("{s}", contestState.state().name()));
            }
            if (contestState.state() != ContestState.ENDED) {
                ended = false;
                if (timer != null && timer.getStatus() != Animation.Status.RUNNING) {
                    timer.play();
                }
            }
            tick();
        });
    }

    private void startConnectionWatchdog() {
        watchdog = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
            boolean live = state.api().isLive();
            setConnected(live);
            if (!live && !reconnecting) {
                reconnecting = true;
                connectLive();
            }
        }));
        watchdog.setCycleCount(Animation.INDEFINITE);
        watchdog.play();
    }

    private void setConnected(boolean live) {
        connLabel.setText(live ? "● live" : "● offline — reconnecting");
        connLabel.getStyleClass().setAll(live ? "conn-ok" : "conn-lost");
    }

    /** Opens the Submissions tab and selects one run. */
    private void showSubmission(long submissionId) {
        tabs.getSelectionModel().select(2);
        submissionsPanelController.selectSubmission(submissionId);
    }

    /** Shown in the statement pane while the contest sits in its lobby. */
    private String waitingPage() {
        boolean dark = state.darkMode();
        return """
                <html><head><meta charset="utf-8"><style>
                  body { font-family: -apple-system, "Segoe UI", sans-serif;
                         background: %s; color: %s; display: flex; height: 90vh;
                         align-items: center; justify-content: center; text-align: center; }
                  h2 { font-weight: 600; }
                  p { color: %s; }
                </style></head><body><div>
                  <h2>Waiting for the contest to start</h2>
                  <p>Problems appear here the moment your instructor starts it.</p>
                </div></body></html>"""
                .formatted(dark ? "#1c222c" : "#ffffff",
                        dark ? "#e6eaf0" : "#1c2430",
                        dark ? "#9aa5b4" : "#6b7684");
    }

    // --- timer (FR-06, UIF-05/06/07) -----------------------------------------

    private void startTimer() {
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        tick();
    }

    private void tick() {
        ContestState contestState = state.contest().state();

        if (contestState == ContestState.LOBBY) {
            // Doors open, clock not started: no countdown, nothing submittable.
            timerLabel.setText(bundle("timer.waiting"));
            timerLabel.getStyleClass().setAll("timer", "timer-waiting");
            stopPulse();
            setSubmissionsAllowed(false);
            return;
        }
        if (contestState == ContestState.PAUSED) {
            timerLabel.setText(bundle("timer.paused"));
            timerLabel.getStyleClass().setAll("timer", "timer-paused");
            stopPulse();
            setSubmissionsAllowed(false);
            return;
        }
        if (contestState == ContestState.ENDED) {
            timerLabel.setText(bundle("timer.ended"));        // UIF-07
            timerLabel.getStyleClass().setAll("timer", "timer-ended");
            stopPulse();
            setSubmissionsAllowed(false);
            return;
        }

        long remaining = state.contest().endTimeMs() - state.serverNowMs();
        if (remaining <= 0) {
            timerLabel.setText(bundle("timer.ended"));
            timerLabel.getStyleClass().setAll("timer", "timer-ended");
            stopPulse();
            setSubmissionsAllowed(false);
            return;
        }

        setSubmissionsAllowed(true);
        long h = remaining / 3_600_000;
        long m = (remaining / 60_000) % 60;
        long s = (remaining / 1000) % 60;
        timerLabel.setText(String.format("%02d:%02d:%02d", h, m, s));

        if (remaining < 5 * 60_000) {                          // UIF-06
            if (!timerLabel.getStyleClass().contains("timer-critical")) {
                timerLabel.getStyleClass().setAll("timer", "timer-critical");
                pulse = new FadeTransition(Duration.millis(500), timerLabel);
                pulse.setFromValue(1.0);
                pulse.setToValue(0.5);
                pulse.setAutoReverse(true);
                pulse.setCycleCount(Animation.INDEFINITE);
                pulse.play();
            }
        } else {
            timerLabel.getStyleClass().setAll("timer");
            stopPulse();
        }
    }

    private void setSubmissionsAllowed(boolean allowed) {
        ended = !allowed;
        editorPanelController.setSubmitEnabled(allowed);
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.stop();
            pulse = null;
            timerLabel.setOpacity(1.0);
        }
    }

    // --- helpers --------------------------------------------------------------

    /** UIF-08: non-blocking toast, bottom-right, 3 s. */
    private void toast(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("toast");
        label.setWrapText(true);
        label.setMaxWidth(420);
        StackPane.setAlignment(label, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(label, new Insets(0, 20, 20, 0));
        rootStack.getChildren().add(label);
        PauseTransition stay = new PauseTransition(Duration.seconds(3));
        stay.setOnFinished(e -> rootStack.getChildren().remove(label));
        stay.play();
    }

    private static String bundle(String key) {
        return SceneRouter.bundle().getString(key);
    }

    private static void async(Runnable task) {
        Thread t = new Thread(task, "main-io");
        t.setDaemon(true);
        t.start();
    }

    /** Left-panel cell: code + title + status badge (§4.2). */
    private static final class ProblemCell extends ListCell<ProblemSummaryDto> {

        @Override
        protected void updateItem(ProblemSummaryDto item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label name = new Label(item.code() + ". " + item.title());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(6, name, spacer);
            row.setAlignment(Pos.CENTER_LEFT);

            if (item.solved()) {
                Label badge = new Label("✓");
                badge.getStyleClass().add("badge-solved");
                row.getChildren().add(badge);
            } else if (item.failedAttempts() > 0) {
                Label badge = new Label("-" + item.failedAttempts());
                badge.getStyleClass().add("badge-failed");
                row.getChildren().add(badge);
            }
            setGraphic(row);
        }
    }
}
