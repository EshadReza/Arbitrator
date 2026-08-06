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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
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

    private static final String TESTS_TITLE = "Test cases";

    private CodeArea sourceView;
    private TitledPane testsPane;
    private VBox testsBox;
    private final AppState state = AppState.get();

    @FXML
    private void initialize() {
        sourceView = new CodeArea();
        sourceView.setEditable(false);
        // Same fixed-width gutter as the editor, for the same reason.
        sourceView.setParagraphGraphicFactory(CodeAreaGutter.factory(sourceView));
        sourceView.getStyleClass().add("code-area");
        VBox.setVgrow(sourceView, Priority.ALWAYS);
        sourceBox.getChildren().add(sourceView);

        // Collapsed by default: the code is what you came for, the tests are
        // what you open when the verdict surprised you.
        testsBox = new VBox(10);
        testsBox.setPadding(new Insets(10));
        ScrollPane scroll = new ScrollPane(testsBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(240);
        testsPane = new TitledPane(TESTS_TITLE, scroll);
        testsPane.setExpanded(false);
        sourceBox.getChildren().add(testsPane);

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

    /**
     * Source is fetched lazily per row rather than carried in the history list,
     * which is polled on every verdict and would otherwise ship every blob.
     */
    private void showSource(SubmissionHistoryDto row) {
        if (row == null) {
            headerLabel.setText("");
            sourceView.replaceText("");
            testsBox.getChildren().clear();
            testsPane.setText(TESTS_TITLE);
            return;
        }
        loadTests(row.id());
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

    /**
     * The tests behind this verdict, when the instructor allows it.
     *
     * Judging stops at the first failure (BR-05), so what comes back is every
     * test that passed plus the single one that did not — the rest of the
     * hidden set is never sent and cannot be inferred from what is.
     */
    private void loadTests(long submissionId) {
        testsBox.getChildren().clear();
        testsPane.setText(TESTS_TITLE + " — loading…");

        Thread worker = new Thread(() -> {
            try {
                SubmissionTestsDto dto = state.api().submissionTests(submissionId);
                Platform.runLater(() -> {
                    // A late response for a row the user already left would
                    // otherwise show one submission's tests under another's code.
                    var current = table.getSelectionModel().getSelectedItem();
                    if (current == null || current.id() != submissionId) {
                        return;
                    }
                    applyTests(dto);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    testsPane.setText(TESTS_TITLE);
                    testsBox.getChildren().setAll(hint("Could not load tests: " + e.getMessage()));
                });
            }
        }, "tests-io");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyTests(SubmissionTestsDto dto) {
        testsPane.setText("%s — %d of %d passed".formatted(
                TESTS_TITLE, dto.passedCount(), dto.totalTestCases()));

        if (!dto.visible()) {
            testsBox.getChildren().setAll(hint(
                    "Your instructor has not enabled test viewing for this contest."));
            return;
        }
        if (dto.tests().isEmpty()) {
            testsBox.getChildren().setAll(hint(
                    "No tests ran for this submission — a compilation error stops before the first one."));
            return;
        }
        dto.tests().forEach(t -> testsBox.getChildren().add(testCard(t)));
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

        table.getColumns().setAll(List.of(when, problem, lang, verdict, tests, time, mem));
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
