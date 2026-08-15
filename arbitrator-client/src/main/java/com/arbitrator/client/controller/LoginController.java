package com.arbitrator.client.controller;

import com.arbitrator.client.app.AppState;
import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.client.net.JudgeApi.ApiException;
import com.arbitrator.common.dto.LoginResponse;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * UIF-01..04: logo, connection status dot, Enter-to-login, inline error that
 * never clears the username, 200 ms fade on success (in SceneRouter).
 */
public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField serverAddressField;
    @FXML private Label errorLabel;
    @FXML private Label statusLabel;
    @FXML private Circle statusDot;
    @FXML private Button themeButton;
    @FXML private Button loginButton;
    @FXML private StackPane heroPane;
    @FXML private ImageView heroImage;

    private final AppState state = AppState.get();

    @FXML
    private void initialize() {
        errorLabel.setVisible(false);
        statusDot.setFill(Color.GRAY);
        if (serverAddressField != null) {
            serverAddressField.setText(state.serverConfig().hostPort());
            serverAddressField.focusedProperty().addListener((obs, oldV, newV) -> {
                if (!newV) {
                    state.serverConfig().updateHostPort(serverAddressField.getText());
                    pingAsync();
                }
            });
        }
        themeButton.setText(state.darkMode() ? "☼" : "☾");
        pingAsync();

        // The server field is first in traversal order, so JavaFX would
        // otherwise hand it initial keyboard focus on scene show — and
        // select-all's its whole text, as it does for any TextField that
        // gains focus via traversal rather than a click. Username is what
        // someone actually wants to type first; claim focus after the
        // scene's own initial-focus pass has already run.
        Platform.runLater(() -> usernameField.requestFocus());

        // Rounds the hero pane's corners by clipping it, rather than relying
        // on -fx-background-radius (which JavaFX does not apply to
        // BackgroundImage fills) — so a real photo dropped into heroImage
        // later is masked exactly like the placeholder gradient is now.
        if (heroPane != null) {
            Rectangle clip = new Rectangle();
            clip.setArcWidth(56);
            clip.setArcHeight(56);
            clip.widthProperty().bind(heroPane.widthProperty());
            clip.heightProperty().bind(heroPane.heightProperty());
            heroPane.setClip(clip);
        }
        // heroImage deliberately gets no fitWidth/fitHeight wiring here.
        // ImageView.isResizable() is true (has been since JavaFX 8) and
        // preserveRatio="false" is already set in the FXML, so the StackPane
        // parent already resizes it to fill the pane on every layout pass —
        // no extra code needed. An earlier version bound (and, when that was
        // found to be the culprit, later listened for) heroPane's own
        // width/height to drive heroImage's fitWidth/fitHeight explicitly.
        // Confirmed live with a repro harness (drove stage.setFullScreen(true)
        // then false, exactly what F11 does, and diffed computed widths
        // before/after): EITHER form — bind() or an addListener() doing the
        // identical assignment — froze heroPane at its fullscreen-era width
        // forever after exiting fullscreen, starving the card column next to
        // it down to a sliver with no further resize ever correcting it. A
        // Rectangle clip bound the same way (see above) does NOT trigger it —
        // isolated with the same harness — so the bug is specific to an
        // ImageView's fit size reacting to its own containing pane's size
        // while that pane is itself mid layout. Letting StackPane's own
        // native resize() handling do it sidesteps the whole reactive-binding
        // class of bug rather than finding a safe way to hand-roll it.
    }

    /** Wired to both the button and the password field's onAction (UIF-02). */
    @FXML
    private void onLogin() {
        if (serverAddressField != null) {
            state.serverConfig().updateHostPort(serverAddressField.getText().trim());
        }
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        if (username.isEmpty() || password.isEmpty()) {
            showError(SceneRouter.bundle().getString("login.error.empty"));
            return;
        }
        loginButton.setDisable(true);
        errorLabel.setVisible(false);
        attemptLogin(username, password, false);
    }

    /**
     * @param force see {@link com.arbitrator.client.net.JudgeApi#login}. A 409
     *              here (only possible when {@code force} was false) means
     *              this account is already logged in elsewhere — item 5's
     *              confirm-to-kick flow, not a credentials error.
     */
    private void attemptLogin(String username, String password, boolean force) {
        runAsync(() -> {
            try {
                LoginResponse session = state.api().login(username, password, force);
                state.setSession(session);
                // The picker chooses the contest and then routes onward; it
                // skips itself when only one contest is joinable.
                Platform.runLater(SceneRouter::showContestPicker);
            } catch (ApiException e) {
                if (e.status() == 409) {
                    Platform.runLater(() -> confirmKickOtherSession(username, password));
                    return;
                }
                Platform.runLater(() -> {
                    // UIF-03: error below the fields, username preserved
                    showError(e.getMessage() == null
                            ? SceneRouter.bundle().getString("login.error.generic")
                            : e.getMessage());
                    passwordField.clear();
                    loginButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError(SceneRouter.bundle().getString("login.error.generic"));
                    passwordField.clear();
                    loginButton.setDisable(false);
                });
            }
        });
    }

    private void confirmKickOtherSession(String username, String password) {
        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle("Already logged in");
        alert.setHeaderText(null);
        alert.setContentText(
                "This account is already logged in elsewhere. Disconnect that session and continue?");
        SceneRouter.styleDialog(alert.getDialogPane());
        alert.showAndWait().ifPresentOrElse(button -> {
            if (button == ButtonType.OK) {
                attemptLogin(username, password, true);
            } else {
                loginButton.setDisable(false);
            }
        }, () -> loginButton.setDisable(false));
    }

    @FXML
    private void onGoToRegister() {
        SceneRouter.showRegister();
    }

    @FXML
    private void onToggleTheme() {
        boolean dark = !state.darkMode();
        state.setDarkMode(dark);
        SceneRouter.applyTheme(usernameField.getScene());
        themeButton.setText(dark ? "☼" : "☾");
    }
    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }

    /** UIF-01: green dot = reachable, red = not. Never blocks the FX thread. */
    private void pingAsync() {
        runAsync(() -> {
            boolean up = state.api().ping();
            Platform.runLater(() ->
                    statusDot.setFill(up ? Color.web("#1a7a1a") : Color.web("#c0392b")));
        });
    }

    private static void runAsync(Runnable task) {
        Thread t = new Thread(task, "login-io");
        t.setDaemon(true);
        t.start();
    }
}
