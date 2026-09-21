/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.controller;

import java.util.regex.Pattern;

import com.arbitrator.client.app.AppState;
import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.security.AccountPasswordPolicy;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * FR-01 registration (EARS: unique username + password of at least eight
 * characters, without whitespace/common choices -> 201 Created). The account is always a STUDENT; admins are
 * provisioned server-side, so nobody can self-register into contest control.
 *
 * The server validates all of this too — these checks exist to give an
 * instant, specific message rather than a round trip for an obvious typo.
 */
public class RegisterController {

    private static final int MAX_DISPLAY_NAME_LENGTH = 128;
    private static final Pattern STUDENT_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    @FXML private TextField usernameField;
    @FXML private TextField displayNameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private Label errorLabel;
    @FXML private Button registerButton;
    @FXML private Button themeButton;

    private final AppState state = AppState.get();

    @FXML
    private void initialize() {
        errorLabel.setVisible(false);
        if (themeButton != null) {
            themeButton.setText(state.darkMode() ? "☼" : "☾");
        }
    }

    @FXML
    private void onToggleTheme() {
        boolean dark = !state.darkMode();
        state.setDarkMode(dark);
        SceneRouter.applyTheme(usernameField.getScene());
        if (themeButton != null) {
            themeButton.setText(dark ? "☼" : "☾");
        }
    }

    @FXML
    private void onRegister() {
        String username = usernameField.getText().trim();
        String displayName = displayNameField.getText().trim();
        String password = passwordField.getText();
        String confirm = confirmField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showError(SceneRouter.bundle().getString("register.error.empty"));
            return;
        }
        if (!STUDENT_ID_PATTERN.matcher(username).matches()) {
            showError(SceneRouter.bundle().getString("register.error.username.invalid"));
            return;
        }
        if (displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_LENGTH
                || displayName.codePoints().anyMatch(Character::isISOControl)) {
            showError(SceneRouter.bundle().getString("register.error.display.invalid"));
            return;
        }
        String passwordError = AccountPasswordPolicy.validationError(password);
        if (passwordError != null) {
            showError(passwordError);
            return;
        }
        if (!password.equals(confirm)) {
            showError(SceneRouter.bundle().getString("register.error.mismatch"));
            confirmField.clear();
            return;
        }

        registerButton.setDisable(true);
        errorLabel.setVisible(false);

        Thread worker = new Thread(() -> {
            try {
                LoginResponse session = state.api().register(
                        username,
                        displayName.isEmpty() ? username : displayName,
                        password);
                state.setSession(session);                  // registered = logged in
                Platform.runLater(SceneRouter::showContestPicker);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError(e.getMessage() == null
                            ? SceneRouter.bundle().getString("register.error.generic")
                            : e.getMessage());
                    registerButton.setDisable(false);
                });
            }
        }, "register-io");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onBackToLogin() {
        SceneRouter.showLogin();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }
}
