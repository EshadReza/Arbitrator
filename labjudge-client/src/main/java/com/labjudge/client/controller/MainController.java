package com.labjudge.client.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.labjudge.client.app.AppState;
import com.labjudge.common.dto.ProblemDetailDto;
import com.labjudge.common.dto.ProblemSummaryDto;
import com.labjudge.common.dto.SubmitAckDto;
import com.labjudge.common.dto.SubmitRequest;
import com.labjudge.common.enums.Language;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.util.Duration;

/**
 * Main contest view (SRS §4.2/4.3/4.4): problem list left, statement center,
 * editor right, authoritative timer on top, toast + verdict banner overlays.
 */
public class MainController {

    @FXML private StackPane rootStack;
    @FXML private Label contestTitleLabel;
    @FXML private Label timerLabel;
    @FXML private Label userLabel;
    @FXML private Label connLabel;
    @FXML private ListView<ProblemSummaryDto> problemList;
    @FXML private Label problemHeader;
    @FXML private WebView statementView;

    // injected from <fx:include fx:id="..."> in main.fxml
    @FXML private EditorController editorPanelController;
    @FXML private LeaderboardPanelController leaderboardPanelController;

    private final AppState state = AppState.get();
    private Timeline timer;
    private FadeTransition pulse;
    private boolean ended;

    /** Guards against the watchdog stacking reconnect attempts. */
    private volatile boolean reconnecting;

    /**
     * Per-problem editor drafts, kept for the session. Switching problems must
     * not discard work — losing code mid-contest is unforgivable, and the SRS
     * expects the editor to be a working surface (§4.2), not a scratch pad.
     */
    private final Map<Long, Draft> drafts = new HashMap<>();

    private record Draft(String code, Language language) { }

    @FXML
    private void initialize() {
        contestTitleLabel.setText(state.contest().title());
        userLabel.setText(state.session().displayName());
        connLabel.setText("●");

        problemList.setCellFactory(v -> new ProblemCell());
        problemList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> {
                    // Stash the outgoing problem's work before switching, so a
                    // student can move between problems without losing code.
                    if (old != null) {
                        stashDraft(old.id());
                    }
                    if (sel != null) {
                        restoreDraft(sel.id());
                        loadProblem(sel.id());
                    }
                });

        editorPanelController.setSubmitHandler(this::submit);

        startTimer();
        connectLive();
        startConnectionWatchdog();
        refreshProblems(true);

        // NFR-U04: Ctrl+Enter submits without touching the mouse
        Platform.runLater(() -> rootStack.getScene().getAccelerators().put(
                new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN),
                editorPanelController::fireSubmit));
    }

    // --- problems -------------------------------------------------------

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

    /** Codeforces-flavoured statement styling, inline so WebView needs no assets. */
    private static String wrapStatement(String html) {
        return """
                <html><head><style>
                  body { font-family: Verdana, Arial, sans-serif; font-size: 13px;
                         color: #222; margin: 14px; }
                  h1 { font-size: 17px; margin-bottom: 2px; }
                  h2 { font-size: 14px; margin-top: 16px; }
                  .limits { color: #666; font-size: 11px; margin-bottom: 12px; }
                  pre.sample { border: 1px solid #b9b9b9; background: #f7f7f7;
                               padding: 8px; font-family: monospace; }
                </style></head><body>""" + html + "</body></html>";
    }

    // --- submit (FR-09, UIF-08/09) ---------------------------------------

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
                Platform.runLater(() ->
                        toast(bundle("submit.queued").replace("{n}",
                                String.valueOf(ack.queuePosition()))));
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

    // --- live channel: verdicts (FR-15) + standings (FR-17) ----------------

    /**
     * Opens the push channel and subscribes both topics. Idempotent — the
     * watchdog calls it again after a drop, and the adapter reuses a live
     * session rather than opening a second socket.
     */
    private void connectLive() {
        async(() -> {
            try {
                state.api().connectVerdicts(event -> Platform.runLater(() -> {
                    VerdictBanner.show(rootStack, event);
                    refreshProblems(false);   // badge update (§4.2)
                }));
                state.api().connectLeaderboard(state.contest().contestId(),
                        board -> leaderboardPanelController.update(board));
                // Paint standings immediately rather than waiting for a push.
                leaderboardPanelController.update(state.api().leaderboard());
                Platform.runLater(() -> setConnected(true));
            } catch (Exception e) {
                Platform.runLater(() -> setConnected(false));
            } finally {
                reconnecting = false;
            }
        });
    }

    /**
     * NFR-R03: a dropped connection must be visible and must recover without
     * the student restarting anything. Silent failure is the worst outcome —
     * verdicts and standings just stop while the app looks perfectly healthy.
     */
    private void startConnectionWatchdog() {
        Timeline watchdog = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
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

    // --- per-problem drafts -------------------------------------------------

    private void stashDraft(long problemId) {
        drafts.put(problemId, new Draft(
                editorPanelController.getCode(),
                editorPanelController.getLanguage()));
    }

    private void restoreDraft(long problemId) {
        Draft draft = drafts.get(problemId);
        editorPanelController.setCode(draft == null ? "" : draft.code());
        if (draft != null) {
            editorPanelController.setLanguage(draft.language());
        }
    }

    // --- timer (FR-06, UIF-05/06/07) --------------------------------------

    private void startTimer() {
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        tick();
    }

    private void tick() {
        long remaining = state.contest().endTimeMs() - state.serverNowMs();
        if (remaining <= 0) {
            timerLabel.setText(bundle("timer.ended"));   // UIF-07
            timerLabel.getStyleClass().remove("timer-critical");
            stopPulse();
            if (!ended) {
                ended = true;
                editorPanelController.setSubmitEnabled(false);
            }
            timer.stop();
            return;
        }
        long h = remaining / 3_600_000;
        long m = (remaining / 60_000) % 60;
        long s = (remaining / 1000) % 60;
        timerLabel.setText(String.format("%02d:%02d:%02d", h, m, s));

        if (remaining < 5 * 60_000) {                    // UIF-06
            if (!timerLabel.getStyleClass().contains("timer-critical")) {
                timerLabel.getStyleClass().add("timer-critical");
                pulse = new FadeTransition(Duration.millis(500), timerLabel);
                pulse.setFromValue(1.0);
                pulse.setToValue(0.5);
                pulse.setAutoReverse(true);
                pulse.setCycleCount(Animation.INDEFINITE);
                pulse.play();
            }
        }
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.stop();
            timerLabel.setOpacity(1.0);
        }
    }

    // --- helpers ----------------------------------------------------------

    /** UIF-08: non-blocking toast, bottom-right, 3 s. */
    private void toast(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("toast");
        StackPane.setAlignment(label, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(label, new javafx.geometry.Insets(0, 20, 20, 0));
        rootStack.getChildren().add(label);
        PauseTransition stay = new PauseTransition(Duration.seconds(3));
        stay.setOnFinished(e -> rootStack.getChildren().remove(label));
        stay.play();
    }

    private static String bundle(String key) {
        return com.labjudge.client.app.SceneRouter.bundle().getString(key);
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
