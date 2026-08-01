package com.labjudge.client.app;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * Entry point. Must reach the login screen in < 5 s on a 4 GB HDD machine
 * (NFR-P05) — so nothing heavy happens before the first scene shows;
 * the server ping runs on a background thread from LoginController.
 */
public class LabJudgeApp extends Application {

    @Override
    public void start(Stage stage) {
        SceneRouter.init(stage);
        SceneRouter.showLogin();
        stage.setTitle("LabJudge");
        stage.setMinWidth(1280);    // §3.3.1 minimum supported resolution
        stage.setMinHeight(768);
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
