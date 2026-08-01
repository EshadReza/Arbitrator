package com.arbitrator.client.controller;

import java.util.List;

import com.arbitrator.client.app.AppState;
import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.common.dto.ContestSummaryDto;
import com.arbitrator.common.enums.ContestState;

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

    @FXML private ListView<ContestSummaryDto> contestList;
    @FXML private Label errorLabel;
    @FXML private Button enterButton;

    private final AppState state = AppState.get();

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
        load();
    }

    private void load() {
        Thread worker = new Thread(() -> {
            try {
                List<ContestSummaryDto> contests = state.api().contests();
                Platform.runLater(() -> {
                    if (contests.isEmpty()) {
                        showError(SceneRouter.bundle().getString("picker.none"));
                        return;
                    }
                    // Always show the list, even with one contest: it is also
                    // the "which contest am I in?" screen, and skipping it made
                    // switching contests feel like it had silently failed.
                    contestList.setItems(FXCollections.observableArrayList(contests));
                    contestList.getSelectionModel().selectFirst();
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError(e.getMessage() == null
                        ? SceneRouter.bundle().getString("picker.error")
                        : e.getMessage()));
            }
        }, "picker-io");
        worker.setDaemon(true);
        worker.start();
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
        state.setSession(null);
        SceneRouter.showLogin();
    }

    private void enter(ContestSummaryDto contest) {
        enterButton.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                state.setContest(state.api().contest(contest.id()));
                Platform.runLater(SceneRouter::showMain);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError(e.getMessage());
                    enterButton.setDisable(false);
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
