package com.arbitrator.client.controller;

import java.util.List;
import java.util.Optional;

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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
    @FXML private Button themeButton;

    private final AppState state = AppState.get();
    private Timeline poller;
    /** Guards against overlapping polls when the server is slow to answer. */
    private volatile boolean loading;

    @FXML
    private void initialize() {
        errorLabel.setVisible(false);
        enterButton.setDisable(true);
        if (themeButton != null) {
            themeButton.setText(state.darkMode() ? "☀" : "🌙");
        }
        contestList.setCellFactory(v -> new ContestCell());
        contestList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> enterButton.setDisable(sel == null));
        contestList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && selected() != null) {
                onEnter();
            }
        });

        poller = new Timeline(new KeyFrame(POLL_INTERVAL, e -> load(false)));
        poller.setCycleCount(Animation.INDEFINITE);
        poller.play();

        // A controller has no "screen closed" callback, so hang the shutdown
        // off the scene: leaving the picker detaches the node, and a timeline
        // left running would keep polling for the rest of the session.
        contestList.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                stopPolling();
            } else {
                installEnterToJoin(scene);
            }
        });

        load(false);
    }

    /**
     * Enter must join the selected contest, full stop — not whatever control
     * happens to hold keyboard focus. {@code enterButton} is {@code
     * defaultButton="true"}, but that only fires when nothing else claims the
     * key first, and a focused {@code Button} (theme toggle, refresh) always
     * consumes Enter for its own action before the scene's default-button
     * fallback ever runs. An event FILTER on the scene intercepts it during
     * the capturing phase, ahead of any button's own handling, so this wins
     * regardless of focus.
     */
    private void installEnterToJoin(javafx.scene.Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && selected() != null) {
                onEnter();
                e.consume();
            }
        });
    }

    @FXML
    private void onRefresh() {
        load(true);
    }

    @FXML
    private void onToggleTheme() {
        boolean dark = !state.darkMode();
        state.setDarkMode(dark);
        SceneRouter.applyTheme(contestList.getScene());
        if (themeButton != null) {
            themeButton.setText(dark ? "☀" : "🌙");
        }
    }

    private void stopPolling() {
        if (poller != null) {
            poller.stop();
        }
    }

    /**
     * @param manual true for an explicit click on the Refresh button, which
     *               dims it for the round trip so a second click can't queue
     *               up behind the first. The background poller passes false
     *               — it fires every 3s (POLL_INTERVAL), and dimming the
     *               button on every one of those silent ticks was the "goes
     *               blank every couple of seconds" flicker: nothing the
     *               student did, just the routine poll being shown as if it
     *               were a loading state. The {@link #loading} guard already
     *               prevents overlapping requests regardless of this flag.
     */
    private void load(boolean manual) {
        if (loading) {
            return;
        }
        loading = true;
        if (manual) {
            refreshButton.setDisable(true);
        }
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
                if (manual) {
                    Platform.runLater(() -> refreshButton.setDisable(false));
                }
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

        // ContestSummaryDto is a record, so List.equals() is a real
        // structural comparison — every poll (every 3s) landing here with an
        // unchanged list would otherwise still call setItems(), which clears
        // and repopulates the ListView (and drops the selection for a frame)
        // even though nothing actually changed. That's the blank flash.
        if (contests.equals(contestList.getItems())) {
            return;
        }

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
        if (sel == null) {
            return;
        }
        if (sel.passwordProtected()) {
            promptPassword(sel.title()).ifPresent(pw -> enter(sel, pw));
        } else {
            enter(sel, "");
        }
    }

    /**
     * Blocking (showAndWait) password prompt for a protected contest. Empty
     * when the student cancels — the caller just does nothing in that case,
     * leaving them on the picker.
     */
    private Optional<String> promptPassword(String contestTitle) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(SceneRouter.bundle().getString("picker.password.title"));

        Label lock = new Label("🔒");
        lock.setStyle("-fx-font-size: 30px;");
        Label titleLabel = new Label(contestTitle);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: 700;");
        Label hint = new Label(SceneRouter.bundle().getString("picker.password.hint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-opacity: .75;");

        PasswordField field = new PasswordField();
        field.setPromptText(SceneRouter.bundle().getString("picker.password.prompt"));
        field.setPrefWidth(280);
        field.setStyle("-fx-font-size: 13px; -fx-padding: 10 12 10 12;");

        VBox header = new VBox(4, lock, titleLabel);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(4, 4, 12, 4));

        VBox content = new VBox(14, header, hint, field);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(6, 18, 10, 18));
        content.setPrefWidth(320);
        dialog.getDialogPane().setContent(content);

        ButtonType enterType = new ButtonType(
                SceneRouter.bundle().getString("picker.enter"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(enterType, ButtonType.CANCEL);
        SceneRouter.styleDialog(dialog.getDialogPane());
        if (contestList.getScene() != null) {
            dialog.initOwner(contestList.getScene().getWindow());
        }
        // Enter in the field submits, matching every other password field in the app.
        field.setOnAction(e -> ((Button) dialog.getDialogPane().lookupButton(enterType)).fire());
        dialog.setResultConverter(bt -> bt == enterType ? field.getText() : null);
        Platform.runLater(field::requestFocus);

        return dialog.showAndWait();
    }

    @FXML
    private void onLogout() {
        stopPolling();
        state.setSession(null);
        SceneRouter.showLogin();
    }

    private void enter(ContestSummaryDto contest, String password) {
        stopPolling();
        enterButton.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                var contestState = state.api().contest(contest.id(), password);
                state.setContest(contestState);
                // Remembered so MainController's own refresh of this same
                // endpoint doesn't have to re-prompt — see AppState.contestPassword().
                state.setContestPassword(password);
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
            Label title = new Label((item.passwordProtected() ? "🔒 " : "") + item.title());
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
