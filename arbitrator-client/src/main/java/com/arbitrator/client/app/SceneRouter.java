package com.arbitrator.client.app;

import java.io.IOException;
import java.util.ResourceBundle;

import javafx.animation.FadeTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Loads FXML scenes and owns the single Stage. All strings come from the
 *  resource bundle (I18N-02). */
public final class SceneRouter {

    private static Stage stage;
    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle("i18n.messages");

    private SceneRouter() {
    }

    public static void init(Stage primaryStage) {
        stage = primaryStage;
    }

    public static void showLogin() {
        setScene(load("/fxml/login.fxml"), false);
    }

    /** FR-01 self-registration. */
    public static void showRegister() {
        setScene(load("/fxml/register.fxml"), false);
    }

    /** Contest chooser; auto-skips itself when only one contest is joinable. */
    public static void showContestPicker() {
        setScene(load("/fxml/contest-picker.fxml"), false);
    }

    /** UIF-04: fade into the main view over 200 ms. */
    public static void showMain() {
        Parent root = load("/fxml/main.fxml");
        setScene(root, true);
        FadeTransition fade = new FadeTransition(Duration.millis(200), root);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    public static ResourceBundle bundle() {
        return BUNDLE;
    }

    /**
     * Alert/TextInputDialog/Dialog each open in their own Scene, which does
     * NOT inherit the main window's stylesheet — left alone they render in
     * plain default JavaFX styling regardless of app theme, the one place
     * the old-Windows-dialog look still showed through. Call this on every
     * dialog the app creates, right after building its DialogPane.
     */
    public static void styleDialog(DialogPane pane) {
        pane.getStylesheets().add(
                SceneRouter.class.getResource("/css/arbitrator.css").toExternalForm());
        pane.getStyleClass().add("root");
        if (AppState.get().darkMode()) {
            pane.getStyleClass().add("dark");
        }
    }

    private static Parent load(String fxml) {
        try {
            FXMLLoader loader = new FXMLLoader(SceneRouter.class.getResource(fxml), BUNDLE);
            return loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load " + fxml, e);
        }
    }

    private static void setScene(Parent root, boolean maximized) {
        Scene scene = new Scene(root, 1280, 768);
        scene.getStylesheets().add(
                SceneRouter.class.getResource("/css/arbitrator.css").toExternalForm());
        applyTheme(scene);
        stage.setScene(scene);
        stage.setMaximized(maximized);
    }

    /**
     * Light and dark share one stylesheet; the palette is swapped by a single
     * style class on the root, so every rule themes without duplication.
     */
    public static void applyTheme(Scene scene) {
        if (scene == null) {
            return;
        }
        var classes = scene.getRoot().getStyleClass();
        classes.remove("dark");
        if (AppState.get().darkMode()) {
            classes.add("dark");
        }
    }
}
