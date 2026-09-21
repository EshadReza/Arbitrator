/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

import com.arbitrator.common.dto.AnnouncementDto;

import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.util.Duration;

/**
 * FR-07: an announcement, shown as a panel that slides in from the top and
 * stays until the contestant acknowledges it.
 *
 * Deliberately not a toast and not a centre-screen dialog. A toast can be
 * missed, and missing an announcement is exactly the failure this feature
 * exists to prevent — so it is dismissed only by pressing OK. It comes from the
 * top because that is where this app already puts things demanding attention
 * (the verdict banner), and because a panel dropping over the timer is far
 * harder to ignore than one appearing under the cursor.
 *
 * The body is rendered in a WebView, the same as a problem statement, so
 * mathematics written as HTML — {@code n &le; 10<sup>9</sup>} — arrives looking
 * like mathematics. A scrim behind it swallows clicks, so the contestant cannot
 * keep typing underneath and never notice the announcement at all.
 */
public final class AnnouncementPopup {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    /**
     * Announcements can arrive together — the instructor posts twice, or a
     * client reconnects. Showing them stacked would bury the first, so they
     * queue and appear one at a time, each acknowledged in turn.
     */
    private static final Deque<AnnouncementDto> QUEUE = new ArrayDeque<>();
    private static boolean showing;

    private AnnouncementPopup() {
    }

    public static void show(StackPane overlay, AnnouncementDto announcement, boolean dark) {
        QUEUE.addLast(announcement);
        if (!showing) {
            showNext(overlay, dark);
        }
    }

    private static void showNext(StackPane overlay, boolean dark) {
        AnnouncementDto a = QUEUE.pollFirst();
        if (a == null) {
            showing = false;
            return;
        }
        showing = true;

        Label title = new Label("Announcement");
        title.getStyleClass().add("announce-title");

        Label when = new Label(STAMP.format(Instant.ofEpochMilli(a.createdAtMs())));
        when.getStyleClass().add("announce-time");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(10, title, spacer, when);
        head.setAlignment(Pos.CENTER_LEFT);

        String bg = dark ? "#232a36" : "#ffffff";
        WebView body = new WebView();
        body.getEngine().setJavaScriptEnabled(false);
        body.setPrefHeight(190);
        // Belt-and-suspenders: an inline -fx-background-color paints from the
        // node's very first frame, so there's no gap between this WebView
        // entering the scene and loadContent() actually finishing where it
        // could show through with the wrong (or WebKit's default) colour —
        // see AnnouncementsPanelController's identical fix for the same gap.
        body.setStyle("-fx-background-color: " + bg + ";");
        body.setPageFill(Color.web(bg));
        body.getEngine().loadContent(wrap(a.body(), dark));

        Button ok = new Button("OK");
        ok.setDefaultButton(true);
        ok.getStyleClass().add("announce-ok");

        HBox actions = new HBox(ok);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox panel = new VBox(10, head, body, actions);
        panel.getStyleClass().add("announce-panel");
        panel.setPadding(new Insets(16));
        panel.setMaxWidth(680);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(panel, Pos.TOP_CENTER);
        StackPane.setMargin(panel, new Insets(0, 0, 0, 0));

        // The scrim is what makes "only closes on OK" true rather than merely
        // encouraged: it covers the app and eats every click aimed past it.
        StackPane scrim = new StackPane(panel);
        scrim.getStyleClass().add("announce-scrim");
        scrim.setOnMouseClicked(e -> e.consume());

        overlay.getChildren().add(scrim);

        TranslateTransition drop = new TranslateTransition(Duration.millis(260), panel);
        panel.setTranslateY(-260);
        drop.setToY(0);
        drop.play();

        ok.setOnAction(e -> {
            overlay.getChildren().remove(scrim);
            showNext(overlay, dark);        // whatever queued behind it
        });
        ok.requestFocus();
    }

    /** Same theming approach as a problem statement, so HTML math looks right. */
    private static String wrap(String html, boolean dark) {
        String bg = dark ? "#232a36" : "#ffffff";
        String fg = dark ? "#e6eaf0" : "#1c2430";
        return """
                <html><head><meta charset="utf-8"><style>
                  html, body { background: %s !important; color: %s !important;
                               font-family: -apple-system, "Segoe UI", sans-serif;
                               font-size: 14px; line-height: 1.55; margin: 0; padding: 4px 2px; }
                  *, p, li, b, i, em, strong, sup, sub, code { color: %s !important; }
                  code, pre { font-family: ui-monospace, monospace; }
                </style></head><body>""".formatted(bg, fg, fg)
                + (html == null ? "" : html) + "</body></html>";
    }
}
