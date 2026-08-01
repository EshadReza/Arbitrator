package com.arbitrator.client.controller;

import com.arbitrator.client.app.AppState;
import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.common.dto.LoginResponse;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * UIF-01..04: logo, connection status dot, Enter-to-login, inline error that
 * never clears the username, 200 ms fade on success (in SceneRouter).
 */
public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Label statusLabel;
    @FXML private Circle statusDot;
    @FXML private Button loginButton;

    private final AppState state = AppState.get();

    @FXML
    private void initialize() {
        errorLabel.setVisible(false);
        statusDot.setFill(Color.GRAY);
        statusLabel.setText(state.serverConfig().hostPort());
        pingAsync();
    }

    /** Wired to both the button and the password field's onAction (UIF-02). */
    @FXML
    private void onLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        if (username.isEmpty() || password.isEmpty()) {
            showError(SceneRouter.bundle().getString("login.error.empty"));
            return;
        }
        loginButton.setDisable(true);
        errorLabel.setVisible(false);

        runAsync(() -> {
            try {
                LoginResponse session = state.api().login(username, password);
                state.setSession(session);
                // The picker chooses the contest and then routes onward; it
                // skips itself when only one contest is joinable.
                Platform.runLater(SceneRouter::showContestPicker);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    // UIF-03: error below the fields, username preserved
                    showError(e.getMessage() == null
                            ? SceneRouter.bundle().getString("login.error.generic")
                            : e.getMessage());
                    passwordField.clear();
                    loginButton.setDisable(false);
                });
            }
        });
    }

    @FXML
    private void onGoToRegister() {
        SceneRouter.showRegister();
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
