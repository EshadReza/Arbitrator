package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.arbitrator.client.app.AppState;
import com.arbitrator.common.dto.ClarificationDto;
import com.arbitrator.common.dto.ProblemSummaryDto;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
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
 * visible to everyone, which is the point of a clarification — a private
 * answer would be an advantage handed to one team. Who asked is not shown on
 * a public question, and is not even sent: the server withholds the name from
 * this view rather than trusting the UI to hide it.
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
        card.getStyleClass().add("box");
        card.setPadding(new Insets(10));

        // Only ever true for the asker's own entries — everyone else's private
        // questions never reach this list in the first place (server-filtered),
        // so seeing this badge at all already means "this one is mine."
        if (!c.isPublic()) {
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
        CheckBox privateBox = new CheckBox("Keep this private (only you and the instructor see it)");

        VBox content = new VBox(8,
                new Label("This is about"), scope,
                new Label("Your question"), question,
                privateBox);
        content.setPadding(new Insets(12));
        content.setPrefWidth(460);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Ask for clarification");
        dialog.setHeaderText("Public questions and answers are visible to everyone, "
                + "but never who asked. Private ones are just between you and the instructor.");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(
                new ButtonType("Send", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        if (listBox.getScene() != null) {
            dialog.initOwner(listBox.getScene().getWindow());
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
            return;
        }
        String text = question.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        Long problemId = ids.get(scope.getSelectionModel().getSelectedIndex());
        send(problemId, text, !privateBox.isSelected());
    }

    private void send(Long problemId, String question, boolean isPublic) {
        Thread worker = new Thread(() -> {
            try {
                long contestId = state.contest() == null ? -1 : state.contest().contestId();
                state.api().askClarification(problemId, contestId, question, isPublic);
                Platform.runLater(this::refresh);
            } catch (Exception e) {
                Platform.runLater(() -> countLabel.setText("Could not send: " + e.getMessage()));
            }
        }, "clarify-ask");
        worker.setDaemon(true);
        worker.start();
    }
}
