/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.fxmisc.richtext.CodeArea;

import com.arbitrator.client.app.AppState;
import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.common.dto.SubmissionTestsDto;
import com.arbitrator.common.dto.TestCaseResultDto;
import com.arbitrator.common.enums.Language;
import com.arbitrator.common.enums.Verdict;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * One submission, in full: the code as submitted, then the tests it reached.
 * Shared by {@link SubmissionsPanelController} (a student's own history) and
 * {@link LeaderboardPanelController} (the standings-box drill-down, wired
 * only for the viewer's own row) so both entry points show the identical
 * detail view rather than two independently drifting copies.
 *
 * Ownership is not this class's job: {@code submissionSource}/{@code
 * submissionTests} are owner-gated server-side (LRR-02, 403 for non-owners)
 * regardless of who calls this method, so the real boundary lives there —
 * callers just decide whether to offer the click at all.
 */
final class SubmissionDetailDialog {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Same ladder as MainController's window zoom, reused for the code viewer's font size. */
    private static final int[] CODE_ZOOM_LEVELS = { 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200 };
    private static final double BASE_CODE_FONT_PX = 13;

    private static final AppState state = AppState.get();

    private SubmissionDetailDialog() {
    }

    static void open(Window owner, long id, String problemCode, Language language,
                      Verdict verdict, long submittedAtMs, long execTimeMs) {
        CodeArea source = new CodeArea();
        source.setEditable(false);
        source.setParagraphGraphicFactory(CodeAreaGutter.factory(source));
        source.getStyleClass().add("code-area");
        source.setPrefHeight(480);
        source.setMinHeight(160);
        source.replaceText("loading source…");

        Label testsHeading = new Label("Judgement protocol");
        testsHeading.getStyleClass().add("box-head");
        testsHeading.setMaxWidth(Double.MAX_VALUE);

        VBox testsBox = new VBox(10);
        testsBox.setPadding(new Insets(10));
        testsBox.getChildren().add(hint("loading tests…"));

        ScrollPane testsScroll = new ScrollPane(testsBox);
        testsScroll.setFitToWidth(true);
        VBox.setVgrow(testsScroll, Priority.ALWAYS);

        VBox testsSection = new VBox(6, testsHeading, testsScroll);
        testsSection.setMinHeight(120);

        SplitPane detailSplit = new SplitPane(source, testsSection);
        detailSplit.setOrientation(Orientation.VERTICAL);
        detailSplit.setDividerPositions(0.58);
        VBox.setVgrow(detailSplit, Priority.ALWAYS);

        Label header = new Label("#%d  ·  %s  ·  %s  ·  %s  ·  %s%s".formatted(
                id,
                problemCode,
                language.display(),
                verdict == null ? "judging…" : verdict.label(),
                STAMP.format(Instant.ofEpochMilli(submittedAtMs)),
                execTimeMs >= 0 ? "  ·  " + execTimeMs + " ms" : ""));
        header.getStyleClass().add("subtitle");
        header.setPadding(new Insets(0, 0, 6, 0));

        int[] zoomIndex = { 5 };
        Label zoomLabel = new Label("100%");
        zoomLabel.getStyleClass().add("zoom-value");
        Button zoomOutBtn = new Button("-");
        zoomOutBtn.getStyleClass().add("icon-button");
        Button zoomInBtn = new Button("+");
        zoomInBtn.getStyleClass().add("icon-button");
        Runnable applyCodeZoom = () -> {
            double px = BASE_CODE_FONT_PX * CODE_ZOOM_LEVELS[zoomIndex[0]] / 100.0;
            source.setStyle("-fx-font-size: " + px + "px;");
            zoomLabel.setText(CODE_ZOOM_LEVELS[zoomIndex[0]] + "%");
        };
        zoomOutBtn.setOnAction(e -> {
            zoomIndex[0] = Math.max(0, zoomIndex[0] - 1);
            applyCodeZoom.run();
        });
        zoomInBtn.setOnAction(e -> {
            zoomIndex[0] = Math.min(CODE_ZOOM_LEVELS.length - 1, zoomIndex[0] + 1);
            applyCodeZoom.run();
        });
        zoomLabel.setOnMouseClicked(e -> {
            zoomIndex[0] = 5;
            applyCodeZoom.run();
        });
        HBox zoomBar = new HBox(2, zoomOutBtn, zoomLabel, zoomInBtn);
        zoomBar.getStyleClass().add("zoom-bar");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(8, header, headerSpacer, zoomBar);

        VBox content = new VBox(8, headerRow, detailSplit);
        content.setPadding(new Insets(12));
        content.setPrefWidth(1180);
        content.setPrefHeight(760);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Submission #" + id);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        SceneRouter.styleDialog(dialog.getDialogPane());
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setOnShown(e -> {
            var scene = dialog.getDialogPane().getScene();
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.CONTROL_DOWN), zoomInBtn::fire);
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.ADD, KeyCombination.CONTROL_DOWN), zoomInBtn::fire);
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.MINUS, KeyCombination.CONTROL_DOWN), zoomOutBtn::fire);
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.SUBTRACT, KeyCombination.CONTROL_DOWN), zoomOutBtn::fire);
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.CONTROL_DOWN), () -> {
                        zoomIndex[0] = 5;
                        applyCodeZoom.run();
                    });
            scene.addEventFilter(ScrollEvent.SCROLL, se -> {
                if (se.isControlDown()) {
                    if (se.getDeltaY() > 0) {
                        zoomInBtn.fire();
                    } else if (se.getDeltaY() < 0) {
                        zoomOutBtn.fire();
                    }
                    se.consume();
                }
            });
        });

        loadSource(id, source);
        loadTests(id, testsBox);

        dialog.showAndWait();
    }

    private static void loadSource(long id, CodeArea target) {
        Thread worker = new Thread(() -> {
            try {
                var src = state.api().submissionSource(id);
                String body = src.sourceCode() == null ? "(no source stored)" : src.sourceCode();
                String text = src.compilerOutput() == null || src.compilerOutput().isBlank()
                        ? body
                        : body + "\n\n/* ---- compiler output ----\n"
                                + src.compilerOutput() + "\n*/";
                Platform.runLater(() -> {
                    target.replaceText(text);
                    target.moveTo(0);
                    target.requestFollowCaret();
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        target.replaceText("Could not load source: " + e.getMessage()));
            }
        }, "source-io");
        worker.setDaemon(true);
        worker.start();
    }

    private static void loadTests(long submissionId, VBox target) {
        Thread worker = new Thread(() -> {
            try {
                SubmissionTestsDto dto = state.api().submissionTests(submissionId);
                Platform.runLater(() -> applyTests(dto, target));
            } catch (Exception e) {
                Platform.runLater(() -> target.getChildren()
                        .setAll(hint("Could not load tests: " + e.getMessage())));
            }
        }, "tests-io");
        worker.setDaemon(true);
        worker.start();
    }

    private static void applyTests(SubmissionTestsDto dto, VBox target) {
        Label summary = new Label("%d of %d passed".formatted(
                dto.passedCount(), dto.totalTestCases()));
        summary.getStyleClass().add("subtitle");

        if (!dto.visible()) {
            target.getChildren().setAll(summary, hint(
                    "Your instructor has not enabled test viewing for this contest."));
            return;
        }
        if (dto.checkerSummary() != null) {
            target.getChildren().setAll(summary, hint(dto.checkerSummary()));
            return;
        }
        if (dto.tests().isEmpty()) {
            target.getChildren().setAll(summary, hint(
                    "No tests ran for this submission — a compilation error stops before the first one."));
            return;
        }
        target.getChildren().setAll(summary);
        dto.tests().forEach(t -> target.getChildren().add(testCard(t)));
    }

    private static VBox testCard(TestCaseResultDto t) {
        Label title = new Label("Test %d".formatted(t.index()));
        title.setStyle("-fx-font-weight: bold;");

        Label verdict = new Label(t.verdict() == null ? "—" : t.verdict().name());
        verdict.getStyleClass().add(t.verdict() == null ? "v-pending" : "v-" + t.verdict().name());

        Label timing = new Label(t.execTimeMs() >= 0 ? t.execTimeMs() + " ms" : "");
        timing.getStyleClass().add("subtitle");

        HBox head = new HBox(10, title, verdict, timing);

        VBox card = new VBox(6, head,
                new HBox(8,
                        labelled("Input", t.input()),
                        labelled("Expected output", t.expectedOutput()),
                        labelled("Your output", t.actualOutput() == null
                                ? "(not recorded for this submission)" : t.actualOutput())));
        card.getStyleClass().add("box");
        card.setPadding(new Insets(10));
        if (t.truncated()) {
            card.getChildren().add(hint("Shown truncated — the full test file is larger."));
        }
        return card;
    }

    private static VBox labelled(String caption, String body) {
        Label label = new Label(caption);
        label.getStyleClass().add("subtitle");

        TextArea area = new TextArea(body);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(4);
        area.getStyleClass().add("code-area");

        VBox box = new VBox(3, label, area);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("subtitle");
        label.setWrapText(true);
        return label;
    }
}
