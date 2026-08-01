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
                    if (old != null) {
                        stashDraft(old.id());
                    }
                    if (sel != null) {
                        restoreDraft(sel.id());
                        loadProblem(sel.id());
                    }
                });

        // A WebView paints its own opaque white backdrop behind the page, so a
        // dark statement needs the fill changed too — page CSS alone can't fix it.
        applyWebViewFill();
        editorPanelController.setSubmitHandler(this::submit);
        editorPanelController.setFullscreenHandler(this::toggleEditorFullscreen);
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
        refreshProblems(false);
        submissionsPanelController.refresh();
        async(() -> {
            try {
                long id = state.contest().contestId();
                var contestState = state.api().contest(id);
                onContestState(contestState);
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
                Platform.runLater(() -> {
                    var selected = problemList.getSelectionModel().getSelectedItem();
                    problemList.getItems().setAll(items);
                    if (selectFirst && !items.isEmpty()) {
                        problemList.getSelectionModel().select(0);
                    } else if (selected != null) {
                        items.stream().filter(p -> p.id() == selected.id()).findFirst()
                                .ifPresent(p -> problemList.getSelectionModel().select(p));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> toast("Could not load problems: " + e.getMessage()));
            }
        });
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
                  html, body { color: %s; background: %s; }
                  body { font-family: -apple-system, "Segoe UI", sans-serif; font-size: 14px;
                         margin: 18px; line-height: 1.55; }
                  /* Uploaded statements bring their own markup and often no colours;
                     without this they inherit the default black and vanish on dark. */
                  /* !important because uploaded statements bring their own markup
                     and sometimes their own colours, which would otherwise leave
                     black text on a dark background. */
                  *, h1, h2, h3, h4, h5, h6, p, li, td, th, div, span, b, i, em, strong,
                  code, pre, blockquote, sup, sub, table, caption { color: %s !important; }
                  a { color: %s; }
                  h1 { font-size: 19px; margin: 0 0 4px; }
                  h2 { font-size: 15px; margin-top: 20px; }
                  .limits { color: %s; font-size: 12px; margin-bottom: 14px; }
                  pre.sample, pre, code { border: 1px solid %s; background: %s; padding: 10px;
                                    border-radius: 6px; font-family: ui-monospace, monospace;
                                    overflow-x: auto; }
                  table { border-collapse: collapse; }
                  td, th { border: 1px solid %s; padding: 4px 8px; }
                </style></head><body>""".formatted(fg, bg, fg, link, muted, border, sampleBg, border)
                + html + "</body></html>";
    }

    // --- per-problem drafts --------------------------------------------------

    private void stashDraft(long problemId) {
        drafts.put(problemId, new Draft(
                editorPanelController.getCode(), editorPanelController.getLanguage()));
    }

    private void restoreDraft(long problemId) {
        Draft draft = drafts.get(problemId);
        editorPanelController.setCode(draft == null ? "" : draft.code());
        if (draft != null) {
            editorPanelController.setLanguage(draft.language());
        }
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

    // --- live channel --------------------------------------------------------

    private void connectLive() {
        async(() -> {
            try {
                state.api().connectVerdicts(event -> Platform.runLater(() -> {
                    VerdictBanner.show(rootStack, event);
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
            state.setContest(contestState);
            contestTitleLabel.setText(contestState.title());
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

    // --- timer (FR-06, UIF-05/06/07) -----------------------------------------

    private void startTimer() {
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        tick();
    }

    private void tick() {
        ContestState contestState = state.contest().state();

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
