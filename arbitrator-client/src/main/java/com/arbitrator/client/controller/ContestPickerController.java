package com.arbitrator.client.controller;

import java.util.List;

import com.arbitrator.client.app.AppState;
import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.common.enums.ContestState;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Contest picker shown after login. Several contests can run at once, so the
 * student chooses which to enter rather than being dropped into whichever one
 * the server happened to consider "current".
 *
 * Always shown, even for a single contest — it doubles as the "which contest
 * am I entering?" confirmation, and auto-skipping made Switch Contest look
 * like it had done nothing.
 */
public class ContestPickerController {

    /**
     * How often the list re-reads the server. The picker is shown before any
     * contest is joined, so there is no STOMP session to push onto yet — a
     * short poll is the only way the screen can notice that the instructor
     * opened a lobby. One tiny request every few seconds costs nothing on a
     * LAN, and it removes the sign-out/sign-in dance that was previously the
     * only way to see a new contest.
     */
    private static final Duration POLL_INTERVAL = Duration.seconds(3);

    @FXML private ListView<ContestSummaryDto> contestList;
    @FXML private Label errorLabel;
    @FXML private Button enterButton;
    @FXML private Button refreshButton;

    private final AppState state = AppState.get();
    private Timeline poller;
    /** Guards against overlapping polls when the server is slow to answer. */
    private volatile boolean loading;

    @FXML
    private void initialize() {
        errorLabel.setVisible(false);
        enterButton.setDisable(true);
        contestList.setCellFactory(v -> new ContestCell());
        contestList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> enterButton.setDisable(sel == null));
        contestList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && selected() != null) {
                onEnter();
            }
        });

        poller = new Timeline(new KeyFrame(POLL_INTERVAL, e -> load()));
        poller.setCycleCount(Animation.INDEFINITE);
        poller.play();

        // A controller has no "screen closed" callback, so hang the shutdown
        // off the scene: leaving the picker detaches the node, and a timeline
        // left running would keep polling for the rest of the session.
        contestList.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                stopPolling();
            }
        });

        load();
    }

    @FXML
    private void onRefresh() {
        load();
    }

    private void stopPolling() {
        if (poller != null) {
            poller.stop();
        }
    }

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        refreshButton.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                List<ContestSummaryDto> contests = state.api().contests();
                Platform.runLater(() -> apply(contests));
            } catch (Exception e) {
                Platform.runLater(() -> showError(e.getMessage() == null
                        ? SceneRouter.bundle().getString("picker.error")
                        : e.getMessage()));
            } finally {
                loading = false;
                Platform.runLater(() -> refreshButton.setDisable(false));
            }
        }, "picker-io");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Replaces the list in place. The selection is re-established by contest
     * id rather than by index, so a poll landing while the student is choosing
     * does not move the highlight out from under them.
     */
    private void apply(List<ContestSummaryDto> contests) {
        if (contests.isEmpty()) {
            contestList.getItems().clear();
            showError(SceneRouter.bundle().getString("picker.none"));
            return;
        }
        errorLabel.setVisible(false);

        ContestSummaryDto previous = selected();
        // Always show the list, even with one contest: it is also the "which
        // contest am I in?" screen, and skipping it made switching contests
        // feel like it had silently failed.
        contestList.setItems(FXCollections.observableArrayList(contests));
        contests.stream()
                .filter(c -> previous != null && c.id() == previous.id())
                .findFirst()
                .ifPresentOrElse(c -> contestList.getSelectionModel().select(c),
                        () -> contestList.getSelectionModel().selectFirst());
    }

    @FXML
    private void onEnter() {
        ContestSummaryDto sel = selected();
        if (sel != null) {
            enter(sel);
        }
    }

    @FXML
    private void onLogout() {
        stopPolling();
        state.setSession(null);
        SceneRouter.showLogin();
    }

    private void enter(ContestSummaryDto contest) {
        stopPolling();
        enterButton.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                state.setContest(state.api().contest(contest.id()));
                Platform.runLater(SceneRouter::showMain);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError(e.getMessage());
                    enterButton.setDisable(false);
                    // The student is still on the picker, so it must keep
                    // watching — the contest may become joinable a moment later.
                    poller.play();
                });
            }
        }, "picker-enter");
        worker.setDaemon(true);
        worker.start();
    }

    private ContestSummaryDto selected() {
        return contestList.getSelectionModel().getSelectedItem();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }

    /** Title, state badge, problem count and duration on one line. */
    private static final class ContestCell extends ListCell<ContestSummaryDto> {

        @Override
        protected void updateItem(ContestSummaryDto item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label title = new Label(item.title());
            title.getStyleClass().add("contest-title");

            Label meta = new Label(item.problemCount() + " problems  ·  "
                    + item.durationMinutes() + " min");
            meta.getStyleClass().add("subtitle");

            VBox left = new VBox(2, title, meta);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label badge = new Label(item.state().name());
            badge.getStyleClass().add(item.state() == ContestState.PAUSED
                    ? "badge-paused" : "badge-solved");

            HBox row = new HBox(10, left, spacer, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
