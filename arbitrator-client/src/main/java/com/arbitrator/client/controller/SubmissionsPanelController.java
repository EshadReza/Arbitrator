package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import com.arbitrator.client.app.AppState;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.enums.Verdict;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * FR-16 / UIF-12: the student's own submission history.
 *
 * Selecting a row loads that submission's source into a read-only editor pane
 * (UIF-12's wording) — a CodeArea rather than a TextArea, so indentation,
 * blank lines and tabs survive exactly as submitted. Only the viewer's own
 * submissions are reachable; LRR-02 forbids exposing one student's code to
 * another during a contest.
 */
public class SubmissionsPanelController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @FXML private TableView<SubmissionHistoryDto> table;
    @FXML private VBox sourceBox;
    @FXML private Label headerLabel;
    @FXML private Label countLabel;

    private CodeArea sourceView;
    private final AppState state = AppState.get();

    @FXML
    private void initialize() {
        sourceView = new CodeArea();
        sourceView.setEditable(false);
        sourceView.setParagraphGraphicFactory(LineNumberFactory.get(sourceView));
        sourceView.getStyleClass().add("code-area");
        VBox.setVgrow(sourceView, Priority.ALWAYS);
        sourceBox.getChildren().add(sourceView);

        table.setPlaceholder(new Label("You haven't submitted anything yet"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        buildColumns();

        table.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> showSource(sel));

        refresh();
    }

    /** Safe from any thread; called after every verdict so the list stays live. */
    public void refresh() {
        Thread worker = new Thread(() -> {
            try {
                List<SubmissionHistoryDto> rows = state.api().mySubmissions();
                Platform.runLater(() -> {
                    var selected = table.getSelectionModel().getSelectedItem();
                    table.setItems(FXCollections.observableArrayList(rows));
                    countLabel.setText(rows.size() + (rows.size() == 1
                            ? " submission" : " submissions"));
                    if (selected != null) {
                        rows.stream().filter(r -> r.id() == selected.id()).findFirst()
                                .ifPresent(r -> table.getSelectionModel().select(r));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> countLabel.setText("Could not load history"));
            }
        }, "history-io");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Source is fetched lazily per row rather than carried in the history list,
     * which is polled on every verdict and would otherwise ship every blob.
     */
    private void showSource(SubmissionHistoryDto row) {
        if (row == null) {
            headerLabel.setText("");
            sourceView.replaceText("");
            return;
        }
        headerLabel.setText("#%d  ·  %s  ·  %s  ·  %s  ·  %s%s".formatted(
                row.id(),
                row.problemCode(),
                row.language().display(),
                row.verdict() == null ? "judging…" : row.verdict().label(),
                STAMP.format(Instant.ofEpochMilli(row.submittedAtMs())),
                row.execTimeMs() >= 0 ? "  ·  " + row.execTimeMs() + " ms" : ""));

        sourceView.replaceText("loading source…");
        long id = row.id();

        Thread worker = new Thread(() -> {
            try {
                var src = state.api().submissionSource(id);
                String body = src.sourceCode() == null ? "(no source stored)" : src.sourceCode();
                // Compiler output matters most on a CE, so append it (FR-20).
                String text = src.compilerOutput() == null || src.compilerOutput().isBlank()
                        ? body
                        : body + "\n\n/* ---- compiler output ----\n"
                                + src.compilerOutput() + "\n*/";
                Platform.runLater(() -> {
                    // Ignore a late response for a row the user already left.
                    var current = table.getSelectionModel().getSelectedItem();
                    if (current != null && current.id() == id) {
                        sourceView.replaceText(text);
                        sourceView.moveTo(0);
                        sourceView.requestFollowCaret();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        sourceView.replaceText("Could not load source: " + e.getMessage()));
            }
        }, "source-io");
        worker.setDaemon(true);
        worker.start();
    }

    private void buildColumns() {
        TableColumn<SubmissionHistoryDto, String> when = new TableColumn<>("Time");
        when.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                STAMP.format(Instant.ofEpochMilli(c.getValue().submittedAtMs()))));
        when.setPrefWidth(90);

        TableColumn<SubmissionHistoryDto, String> problem = new TableColumn<>("Problem");
        problem.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().problemCode()));
        problem.setPrefWidth(80);

        TableColumn<SubmissionHistoryDto, String> lang = new TableColumn<>("Language");
        lang.setCellValueFactory(c ->
                new ReadOnlyObjectWrapper<>(c.getValue().language().display()));
        lang.setPrefWidth(110);

        TableColumn<SubmissionHistoryDto, Verdict> verdict = new TableColumn<>("Verdict");
        verdict.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().verdict()));
        verdict.setCellFactory(c -> new VerdictCell());
        verdict.setPrefWidth(180);

        TableColumn<SubmissionHistoryDto, String> time = new TableColumn<>("Time used");
        time.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().execTimeMs() >= 0 ? c.getValue().execTimeMs() + " ms" : "—"));
        time.setPrefWidth(95);

        TableColumn<SubmissionHistoryDto, String> mem = new TableColumn<>("Memory");
        mem.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().peakMemoryKb() > 0 ? c.getValue().peakMemoryKb() + " KB" : "—"));
        mem.setPrefWidth(95);

        table.getColumns().setAll(List.of(when, problem, lang, verdict, time, mem));
    }

    /** Same colour language as the verdict banner (UIF-10/NFR-U02). */
    private static final class VerdictCell
            extends TableCell<SubmissionHistoryDto, Verdict> {

        @Override
        protected void updateItem(Verdict verdict, boolean empty) {
            super.updateItem(verdict, empty);
            getStyleClass().removeAll("cell-solved", "cell-failed");
            if (empty || verdict == null) {
                setText(empty ? null : "judging…");
                return;
            }
            setText(verdict.name() + " — " + verdict.label());
            getStyleClass().add(verdict == Verdict.AC ? "cell-solved" : "cell-failed");
        }
    }
}
