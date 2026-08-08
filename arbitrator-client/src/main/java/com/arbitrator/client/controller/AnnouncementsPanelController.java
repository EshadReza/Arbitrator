package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.arbitrator.client.app.AppState;
import com.arbitrator.common.dto.AnnouncementDto;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;

/**
 * FR-07, contestant side: every announcement made in this contest.
 *
 * The popup is easy to dismiss and then wonder what it said, so the same text
 * has to live somewhere permanent. Each entry is its own small WebView because
 * announcement bodies are HTML — the mathematics has to render here exactly as
 * it did in the popup.
 */
public class AnnouncementsPanelController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @FXML private VBox listBox;
    @FXML private Label countLabel;

    private final AppState state = AppState.get();

    @FXML
    private void initialize() {
        refresh();
    }

    /** Safe from any thread; called on the announcement push too. */
    public void refresh() {
        Thread worker = new Thread(() -> {
            try {
                long contestId = state.contest() == null ? -1 : state.contest().contestId();
                List<AnnouncementDto> items = state.api().announcements(contestId);
                Platform.runLater(() -> apply(items));
            } catch (Exception e) {
                Platform.runLater(() -> countLabel.setText("Could not load announcements"));
            }
        }, "announcements-io");
        worker.setDaemon(true);
        worker.start();
    }

    private void apply(List<AnnouncementDto> items) {
        listBox.getChildren().clear();
        countLabel.setText(items.isEmpty() ? "No announcements yet"
                : items.size() + (items.size() == 1 ? " announcement" : " announcements"));
        items.forEach(a -> listBox.getChildren().add(card(a)));
    }

    private VBox card(AnnouncementDto a) {
        Label when = new Label(STAMP.format(Instant.ofEpochMilli(a.createdAtMs())));
        when.getStyleClass().add("subtitle");

        boolean dark = state.darkMode();
        String bg = dark ? "#1c222c" : "#ffffff";
        WebView body = new WebView();
        body.setPrefHeight(120);
        // Belt-and-suspenders: setPageFill only paints once the engine has a
        // page to lay out, and the node's own background can otherwise show
        // through — briefly, but visibly — for the instant between the
        // WebView existing in the scene and loadContent() finishing. An
        // inline -fx-background-color has no such gap; it's the node's paint
        // from the very first frame, regardless of theme-CSS cascade timing.
        body.setStyle("-fx-background-color: " + bg + ";");
        body.setPageFill(Color.web(bg));
        body.getEngine().loadContent(wrap(a.body(), dark));

        VBox card = new VBox(4, when, body);
        card.getStyleClass().addAll("box", "list-card");
        card.setPadding(new javafx.geometry.Insets(10));
        return card;
    }

    private static String wrap(String html, boolean dark) {
        String bg = dark ? "#1c222c" : "#ffffff";
        String fg = dark ? "#e6eaf0" : "#1c2430";
        return """
                <html><head><meta charset="utf-8"><style>
                  html, body { background: %s !important; color: %s !important;
                               font-family: -apple-system, "Segoe UI", sans-serif;
                               font-size: 14px; line-height: 1.5; margin: 0; padding: 2px; }
                  *, p, li, b, i, em, strong, sup, sub, code { color: %s !important; }
                  code, pre { font-family: ui-monospace, monospace; }
                </style></head><body>""".formatted(bg, fg, fg)
                + (html == null ? "" : html) + "</body></html>";
    }
}
