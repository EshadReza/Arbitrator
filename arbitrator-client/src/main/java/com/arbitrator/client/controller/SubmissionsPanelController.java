package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.fxmisc.richtext.CodeArea;

import com.arbitrator.client.app.AppState;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.dto.SubmissionTestsDto;
import com.arbitrator.common.dto.TestCaseResultDto;
import com.arbitrator.common.enums.Verdict;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * FR-16 / UIF-12: the student's own submission history.
 *
 * The list is the whole tab; a submission's source and judgement protocol open
 * on demand from its own row into a dialog. They used to occupy a docked pane
 * under the table permanently, which spent half the tab on one submission's
 * detail even while someone was scanning the history for a different one — and
 * the detail it showed was whichever row happened to be selected, not
 * necessarily the one being looked at.
 *
 * Source is a CodeArea rather than a TextArea so indentation, blank lines and
 * tabs survive exactly as submitted. Only the viewer's own submissions are
 * reachable; LRR-02 forbids exposing one student's code to another during a
 * contest.
 */
public class SubmissionsPanelController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @FXML private TableView<SubmissionHistoryDto> table;
    @FXML private Label countLabel;

    private final AppState state = AppState.get();

    @FXML
    private void initialize() {
        table.setPlaceholder(new Label("You haven't submitted anything yet"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        buildColumns();
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
     * Selects one submission, refreshing first if it isn't in the table yet —
     * a verdict banner can be clicked before the history poll has caught up.
     */
    public void selectSubmission(long submissionId) {
        var existing = table.getItems().stream()
                .filter(r -> r.id() == submissionId).findFirst();
        if (existing.isPresent()) {
            table.getSelectionModel().select(existing.get());
            table.scrollTo(existing.get());
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                List<SubmissionHistoryDto> rows = state.api().mySubmissions();
                Platform.runLater(() -> {
                    table.setItems(FXCollections.observableArrayList(rows));
                    rows.stream().filter(r -> r.id() == submissionId).findFirst()
                            .ifPresent(r -> {
                                table.getSelectionModel().select(r);
                                table.scrollTo(r);
                            });
                });
            } catch (Exception ignored) {
                // the periodic refresh will pick it up shortly
            }
        }, "history-select");
        worker.setDaemon(true);
        worker.start();
    }

    // --- the detail dialog --------------------------------------------------

    /**
     * One submission, in full: the code as submitted, then the tests it
     * reached. Both are fetched when the dialog opens rather than carried in
     * the history list, which is polled on every verdict and would otherwise
     * ship every source blob to redraw a table.
     */
    private void openDetail(SubmissionHistoryDto row) {
        CodeArea source = new CodeArea();
        source.setEditable(false);
        source.setParagraphGraphicFactory(CodeAreaGutter.factory(source));
        source.getStyleClass().add("code-area");
        source.setPrefHeight(360);
        source.replaceText("loading source…");
        VBox.setVgrow(source, Priority.ALWAYS);

        Label testsHeading = new Label("Judgement protocol");
        testsHeading.getStyleClass().add("box-head");
        testsHeading.setMaxWidth(Double.MAX_VALUE);

        VBox testsBox = new VBox(10);
        testsBox.setPadding(new Insets(10));
        testsBox.getChildren().add(hint("loading tests…"));

        ScrollPane testsScroll = new ScrollPane(testsBox);
        testsScroll.setFitToWidth(true);
        testsScroll.setPrefHeight(260);

        Label header = new Label("#%d  ·  %s  ·  %s  ·  %s  ·  %s%s".formatted(
                row.id(),
                row.problemCode(),
                row.language().display(),
                row.verdict() == null ? "judging…" : row.verdict().label(),
                STAMP.format(Instant.ofEpochMilli(row.submittedAtMs())),
                row.execTimeMs() >= 0 ? "  ·  " + row.execTimeMs() + " ms" : ""));
        header.getStyleClass().add("subtitle");
        header.setPadding(new Insets(0, 0, 6, 0));

        VBox content = new VBox(8, header, source, testsHeading, testsScroll);
        content.setPadding(new Insets(12));
        content.setPrefWidth(940);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Submission #" + row.id());
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (table.getScene() != null) {
            dialog.initOwner(table.getScene().getWindow());
            // Inherit the theme, or a dark-mode user gets a white dialog.
            dialog.getDialogPane().getStylesheets()
                    .setAll(table.getScene().getStylesheets());
            dialog.getDialogPane().getStyleClass().add("root");
            if (state.darkMode()) {
                dialog.getDialogPane().getStyleClass().add("dark");
            }
        }

        loadSource(row.id(), source);
        loadTests(row.id(), testsBox);

        dialog.showAndWait();
    }

    private void loadSource(long id, CodeArea target) {
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

    /**
     * The tests behind this verdict, when the instructor allows it.
     *
     * Judging stops at the first failure (BR-05), so what comes back is every
     * test that passed plus the single one that did not — the rest of the
     * hidden set is never sent and cannot be inferred from what is.
     */
    private void loadTests(long submissionId, VBox target) {
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

    private void applyTests(SubmissionTestsDto dto, VBox target) {
        Label summary = new Label("%d of %d passed".formatted(
                dto.passedCount(), dto.totalTestCases()));
        summary.getStyleClass().add("subtitle");

        if (!dto.visible()) {
            target.getChildren().setAll(summary, hint(
                    "Your instructor has not enabled test viewing for this contest."));
            return;
        }
        // A checker-graded problem accepts more than one valid output, so the
        // server sends a verdict sentence instead of the data (see
        // SubmissionService.tests); showing "expected" next to "yours" there
        // would misstate what was actually graded.
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

    /** One test: its verdict line, then the input and the expected output. */
    private static VBox testCard(TestCaseResultDto t) {
        Label title = new Label("Test %d".formatted(t.index()));
        title.setStyle("-fx-font-weight: bold;");

        Label verdict = new Label(t.verdict() == null ? "—" : t.verdict().name());
        verdict.getStyleClass().add(t.verdict() == null ? "v-pending" : "v-" + t.verdict().name());

        Label timing = new Label(t.execTimeMs() >= 0 ? t.execTimeMs() + " ms" : "");
        timing.getStyleClass().add("subtitle");

        HBox head = new HBox(10, title, verdict, timing);

        // Your output beside the expected one: a WA is only informative when you
        // can see the two side by side.
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
        verdict.setPrefWidth(160);

        TableColumn<SubmissionHistoryDto, String> tests = new TableColumn<>("Tests Passed");
        tests.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().totalTestCases() > 0 ? c.getValue().passedTestCount() + "/" + c.getValue().totalTestCases() + " passed" : "—"));
        tests.setPrefWidth(100);

        TableColumn<SubmissionHistoryDto, String> time = new TableColumn<>("Time used");
        time.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().execTimeMs() >= 0 ? c.getValue().execTimeMs() + " ms" : "—"));
        time.setPrefWidth(95);

        TableColumn<SubmissionHistoryDto, String> mem = new TableColumn<>("Memory");
        mem.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().peakMemoryKb() > 0 ? c.getValue().peakMemoryKb() + " KB" : "—"));
        mem.setPrefWidth(95);

        TableColumn<SubmissionHistoryDto, Void> open = new TableColumn<>("");
        open.setSortable(false);
        open.setCellFactory(c -> new OpenCell());
        open.setPrefWidth(110);
        open.setMinWidth(110);
        open.setMaxWidth(110);

        table.getColumns().setAll(List.of(when, problem, lang, verdict, tests, time, mem, open));
    }

    /** The per-row entry point into {@link #openDetail}. */
    private final class OpenCell extends TableCell<SubmissionHistoryDto, Void> {

        private final Button button = new Button("View code");

        OpenCell() {
            button.getStyleClass().add("btn-small");
            button.setOnAction(e -> {
                SubmissionHistoryDto row = getTableRow() == null ? null : getTableRow().getItem();
                if (row != null) {
                    openDetail(row);
                }
            });
        }

        @Override
        protected void updateItem(Void unused, boolean empty) {
            super.updateItem(unused, empty);
            setGraphic(empty || getTableRow() == null || getTableRow().getItem() == null
                    ? null : button);
        }
    }

    /** Same colour language as the verdict banner (UIF-10/NFR-U02). */
    private static final class VerdictCell
            extends TableCell<SubmissionHistoryDto, Verdict> {

        @Override
        protected void updateItem(Verdict verdict, boolean empty) {
            super.updateItem(verdict, empty);
            // Drop any previous verdict class; cells are recycled while scrolling.
            getStyleClass().removeIf(c -> c.startsWith("v-"));
            if (empty) {
                setText(null);
                return;
            }
            if (verdict == null) {
                setText("judging…");
                getStyleClass().add("v-pending");
                return;
            }
            setText(verdict.name() + " — " + verdict.label());
            getStyleClass().add("v-" + verdict.name());
        }
    }
}
