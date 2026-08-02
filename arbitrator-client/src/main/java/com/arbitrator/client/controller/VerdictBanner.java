package com.arbitrator.client.controller;

import com.arbitrator.common.dto.VerdictEventDto;
import com.arbitrator.common.enums.Verdict;

import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * UIF-10: verdict banner slides down from the top and stays 8 s. One CSS
 * class per verdict carries the SRS colour table (green/red/orange/purple).
 * UIF-11: a CE banner opens the scrollable monospace compiler log on click.
 */
public final class VerdictBanner {

    private VerdictBanner() {
    }

    public static void show(StackPane overlay, VerdictEventDto event) {
        show(overlay, event, null);
    }

    /** @param onClick opened when the banner is clicked (may be null). */
    public static void show(StackPane overlay, VerdictEventDto event, Runnable onClick) {
        String symbol = switch (event.verdict()) {
            case AC -> "✓ ";
            case WA -> "✗ ";
            case TLE -> "⏱ ";
            case MLE -> "💾 ";
            case CE -> "⚙ ";
            case RE -> "💥 ";
            case OLE -> "📄 ";
        };
        String text = symbol + event.verdict().label()
                + (event.execTimeMs() >= 0 ? "   " + event.execTimeMs() + " ms" : "")
                + (event.peakMemoryKb() > 0 ? "   " + event.peakMemoryKb() + " KB" : "")
                + (event.failedTestIndex() > 0 ? "   on test " + event.failedTestIndex() : "");

        Label label = new Label(text);
        label.getStyleClass().add("banner-text");

        HBox banner = new HBox(label);
        banner.setAlignment(Pos.CENTER);
        banner.setPadding(new Insets(10));
        banner.getStyleClass().addAll("verdict-banner", "banner-" + event.verdict().name());
        banner.setMaxHeight(44);
        banner.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(banner, Pos.TOP_CENTER);

        if (event.verdict() == Verdict.CE && event.compilerOutput() != null) {
            Label hint = new Label("  (click for the compiler log)");
            hint.getStyleClass().add("banner-text");
            banner.getChildren().add(hint);
            banner.setOnMouseClicked(e -> showCompilerLog(overlay, event.compilerOutput()));
        } else if (onClick != null) {
            Label hint = new Label("  (click to see your submission)");
            hint.getStyleClass().add("banner-text");
            banner.getChildren().add(hint);
            banner.setOnMouseClicked(e -> {
                overlay.getChildren().remove(banner);
                onClick.run();
            });
        }

        overlay.getChildren().add(banner);

        TranslateTransition slideIn = new TranslateTransition(Duration.millis(250), banner);
        banner.setTranslateY(-60);
        slideIn.setToY(0);
        slideIn.play();

        PauseTransition stay = new PauseTransition(Duration.seconds(8));
        stay.setOnFinished(e -> {
            TranslateTransition slideOut = new TranslateTransition(Duration.millis(250), banner);
            slideOut.setToY(-60);
            slideOut.setOnFinished(f -> overlay.getChildren().remove(banner));
            slideOut.play();
        });
        stay.play();
    }

    /** UIF-11: scrollable, monospace, max 4 KB (already truncated server-side). */
    private static void showCompilerLog(StackPane overlay, String log) {
        TextArea area = new TextArea(log);
        area.setEditable(false);
        area.getStyleClass().add("compiler-log");

        Button close = new Button("✕");
        BorderPane header = new BorderPane();
        header.getStyleClass().add("box-head");
        header.setLeft(new Label("Compilation Error"));
        header.setRight(close);

        BorderPane panel = new BorderPane(area);
        panel.setTop(header);
        panel.getStyleClass().add("box");
        panel.setMaxSize(700, 400);

        StackPane.setAlignment(panel, Pos.CENTER);
        overlay.getChildren().add(panel);
        close.setOnAction(e -> overlay.getChildren().remove(panel));
    }
}
