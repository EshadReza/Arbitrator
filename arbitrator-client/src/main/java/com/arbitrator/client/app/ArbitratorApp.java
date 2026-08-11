package com.arbitrator.client.app;

import javafx.application.Application;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Entry point. Must reach the login screen in < 5 s on a 4 GB HDD machine
 * (NFR-P05) — so nothing heavy happens before the first scene shows;
 * the server ping runs on a background thread from LoginController.
 */
public class ArbitratorApp extends Application {

    @Override
    public void start(Stage stage) {
        Fonts.loadAll();       // before any scene, so the very first one is themed correctly
        SceneRouter.init(stage);
        SceneRouter.showLogin();
        stage.setTitle("Arbitrator");
        // §3.3.1's 1280x768 is the minimum *supported* resolution, but a hard
        // min larger than the actual screen (small/scaled Ubuntu displays)
        // forced an oversized, partly off-screen window with no way to
        // resize it back down — clamp to whatever's actually usable.
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setMinWidth(Math.min(1280, bounds.getWidth()));
        stage.setMinHeight(Math.min(768, bounds.getHeight()));
        stage.show();
    }

    @Override
    public void stop() {
        AppState.get().shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
