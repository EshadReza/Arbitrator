/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.arbitrator.client.app.AppState;
import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.common.dto.ClarificationDto;
import com.arbitrator.common.dto.ProblemSummaryDto;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/**
 * The clarification board, contestant side.
 *
 * Public by default: every question and answer that isn't marked private is
 * *meant* to reach everyone, which is the point of a clarification — a
 * private answer would be an advantage handed to one team. Who asked is not
 * shown on a public question, and is not even sent: the server withholds the
 * name from this view rather than trusting the UI to hide it.
 *
 * A public one doesn't actually reach the rest of the class the instant it's
 * answered, though — the instructor has to approve it first. The asker still
 * sees their own answer immediately either way, same as a private one, with a
 * note if the class can't see it yet — see the "awaiting approval" branch in
 * {@link #card}.
 *
 * A question the asker marks private is between them and the instructor —
 * this list still shows it to them (so asking privately isn't a black hole
 * with no way to check the answer), but nobody else's private questions ever
 * appear here at all.
 */
public class ClarificationsPanelController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Set by MainController — the panel does not own the problem list. */
    private java.util.function.Supplier<List<ProblemSummaryDto>> problemSupplier = List::of;

    @FXML private VBox listBox;
    @FXML private Label countLabel;

    private final AppState state = AppState.get();

    /** Cached from the last refresh(); onAsk() reads it, never blocks the FX thread for it. */
    private volatile boolean privateAllowed = true;

    @FXML
    private void initialize() {
        refresh();
    }

    public void setProblemSupplier(java.util.function.Supplier<List<ProblemSummaryDto>> supplier) {
        this.problemSupplier = supplier;
    }

    /** Safe from any thread; called on the clarification push too. */
    public void refresh() {
        Thread worker = new Thread(() -> {
            try {
                long contestId = state.contest() == null ? -1 : state.contest().contestId();
                List<ClarificationDto> items = state.api().clarifications(contestId);
                try {
                    // A stumble here shouldn't sink the whole refresh — fall back
                    // to whatever was last known rather than losing the list too.
                    privateAllowed = state.api().clarificationPrivacyAllowed(contestId);
                } catch (Exception ignored) {
                    // keep the previous value
                }
                Platform.runLater(() -> apply(items));
            } catch (Exception e) {
                Platform.runLater(() -> countLabel.setText("Could not load clarifications"));
            }
        }, "clarifications-io");
        worker.setDaemon(true);
        worker.start();
    }

    private void apply(List<ClarificationDto> items) {
        listBox.getChildren().clear();
        long answered = items.stream().filter(ClarificationDto::answered).count();
        countLabel.setText(items.isEmpty()
                ? "No clarifications yet"
                : items.size() + " asked · " + answered + " answered");
        items.forEach(c -> listBox.getChildren().add(card(c)));
    }

    private VBox card(ClarificationDto c) {
        String scope = c.problemCode() == null
                ? "General" : c.problemCode() + ". " + c.problemTitle();
        Label header = new Label(scope + "   ·   " + STAMP.format(Instant.ofEpochMilli(c.askedAtMs())));
        header.getStyleClass().add("subtitle");

        Label question = new Label(c.question());
        question.setWrapText(true);
        question.getStyleClass().add("clarify-question");

        VBox card = new VBox(5, header, question);
        card.getStyleClass().addAll("box", "list-card");
        card.setPadding(new Insets(10));

        // Only ever true for the asker's own entries — everyone else's private
        // questions never reach this list in the first place (server-filtered),
        // so seeing this badge at all already means "this one is mine."
        if (!c.isPublic()) {
            card.getStyleClass().add("private");
            Label privateBadge = new Label("Private — only you and the instructor see this");
            privateBadge.getStyleClass().add("clarify-private-badge");
            card.getChildren().add(1, privateBadge);
        }

        if (c.answered()) {
            Label answerLabel = new Label("Answer");
            answerLabel.getStyleClass().add("clarify-answer-label");
            Label answer = new Label(c.answer());
            answer.setWrapText(true);
            answer.getStyleClass().add("clarify-answer");
            card.getChildren().addAll(answerLabel, answer);
            // Only ever reachable for the caller's own entry — anyone else's
            // unapproved public clarification never reaches this list at all
            // (server-filtered, same as a private one), so getting this far
            // with isPublic()&&!approved() means "you're seeing this early."
            if (c.isPublic() && !c.approved()) {
                Label pendingApproval = new Label(
                        "Awaiting the instructor's approval to appear publicly");
                pendingApproval.getStyleClass().add("clarify-pending");
                pendingApproval.setWrapText(true);
                card.getChildren().add(pendingApproval);
            }
        } else {
            Label pending = new Label("Awaiting an answer");
            pending.getStyleClass().add("clarify-pending");
            card.getChildren().add(pending);
        }
        return card;
    }

    /**
     * "Ask for clarification": pick what it is about, then write the question.
     * The problem list comes from the main view so this cannot drift out of
     * step with what is actually released.
     */
    @FXML
    private void onAsk() {
        List<ProblemSummaryDto> problems = problemSupplier.get();

        ChoiceBox<String> scope = new ChoiceBox<>();
        List<Long> ids = new ArrayList<>();
        scope.getItems().add("General — about the contest");
        ids.add(null);
        for (ProblemSummaryDto p : problems) {
            scope.getItems().add(p.code() + ". " + p.title());
            ids.add(p.id());
        }
        scope.getSelectionModel().select(0);

        TextArea question = new TextArea();
        question.setPromptText("What is unclear?");
        question.setWrapText(true);
        question.setPrefRowCount(6);

        // Off (public) by default: that keeps the common case — everyone
        // benefits from the answer — the path that requires no extra thought.
        // Omitted entirely when the instructor has turned the option off for
        // this contest (V61) — offering a choice the server will silently
        // override would just be confusing, not merely redundant.
        CheckBox privateBox = privateAllowed
                ? new CheckBox("Keep this private (only you and the instructor see it)") : null;

        Label status = new Label();
        status.getStyleClass().add("error");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        VBox content = new VBox(8,
                new Label("This is about"), scope,
                new Label("Your question"), question);
        if (privateBox != null) {
            content.getChildren().add(privateBox);
        }
        content.getChildren().add(status);
        content.setPadding(new Insets(12));
        content.setPrefWidth(460);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Ask for clarification");
        dialog.setHeaderText(privateAllowed
                ? "Public questions and answers are visible to everyone, but never who asked. "
                        + "Private ones are just between you and the instructor."
                : "Questions and answers are visible to everyone, but never who asked.");
        dialog.getDialogPane().setContent(content);
        ButtonType sendType = new ButtonType("Send", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(sendType, ButtonType.CANCEL);
        SceneRouter.styleDialog(dialog.getDialogPane());
        if (listBox.getScene() != null) {
            dialog.initOwner(listBox.getScene().getWindow());
        }

        // Send is intercepted rather than left to close the dialog on its own:
        // the request can fail (most importantly, the instructor turning
        // private clarifications off between opening this dialog and clicking
        // Send — V61) and when it does, the asker needs to see why, with their
        // question still sitting right there to fix and retry, not silently
        // sent some other way or thrown away behind a dialog that already
        // closed.
        Node sendButton = dialog.getDialogPane().lookupButton(sendType);
        sendButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            String text = question.getText();
            if (text == null || text.isBlank()) {
                showStatus(status, "Write your question before sending it.");
                return;
            }
            Long problemId = ids.get(scope.getSelectionModel().getSelectedIndex());
            boolean askPublic = privateBox == null || !privateBox.isSelected();
            sendButton.setDisable(true);
            status.setVisible(false);
            status.setManaged(false);

            Thread worker = new Thread(() -> {
                try {
                    long contestId = state.contest() == null ? -1 : state.contest().contestId();
                    state.api().askClarification(problemId, contestId, text, askPublic);
                    Platform.runLater(() -> {
                        dialog.setResult(sendType);
                        dialog.close();
                        refresh();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        sendButton.setDisable(false);
                        showStatus(status, e.getMessage() == null || e.getMessage().isBlank()
                                ? "Could not send — try again." : e.getMessage());
                    });
                }
            }, "clarify-ask");
            worker.setDaemon(true);
            worker.start();
        });

        dialog.showAndWait();
    }

    private static void showStatus(Label status, String text) {
        status.setText(text);
        status.setVisible(true);
        status.setManaged(true);
    }
}
