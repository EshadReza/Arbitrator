package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.arbitrator.client.app.AppState;
import com.arbitrator.common.dto.SubmissionHistoryDto;
import com.arbitrator.common.enums.Verdict;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

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

    /** Default Modena row height (24px) * 1.75 — rows were too cramped to scan quickly. */
    private static final double ROW_HEIGHT = 42;

    @FXML private TableView<SubmissionHistoryDto> table;
    @FXML private Label countLabel;
    @FXML private CheckBox currentContestOnly;

    private final AppState state = AppState.get();

    @FXML
    private void initialize() {
        table.setPlaceholder(new Label("You haven't submitted anything yet"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(ROW_HEIGHT);
        table.setRowFactory(tv -> {
            TableRow<SubmissionHistoryDto> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                // This history list is read-only — a click just marks "I
                // looked here" — so the blank area below the last row (an
                // empty virtualized row) drops the highlight instead of
                // leaving it stuck on whatever was clicked last.
                if (row.isEmpty()) {
                    table.getSelectionModel().clearSelection();
                } else if (e.getClickCount() == 2) {
                    openDetail(row.getItem());
                }
            });
            return row;
        });
        buildColumns();
        refresh();
    }

    /** Drops the row highlight — called when the Submissions tab loses focus. */
    public void clearSelection() {
        table.getSelectionModel().clearSelection();
    }

    @FXML
    private void onToggleCurrentContestOnly() {
        refresh();
    }

    /** True when the tick is off — every contest, not just this one. */
    private boolean showAllContests() {
        return !currentContestOnly.isSelected();
    }

    /** Safe from any thread; called after every verdict so the list stays live. */
    public void refresh() {
        Thread worker = new Thread(() -> {
            try {
                List<SubmissionHistoryDto> rows =
                        state.api().mySubmissions(state.contest().contestId(), showAllContests());
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
                List<SubmissionHistoryDto> rows =
                        state.api().mySubmissions(state.contest().contestId(), showAllContests());
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
     * reached. Delegates to {@link SubmissionDetailDialog}, shared with the
     * standings-box drill-down so both entry points look identical.
     */
    private void openDetail(SubmissionHistoryDto row) {
        SubmissionDetailDialog.open(
                table.getScene() == null ? null : table.getScene().getWindow(),
                row.id(), row.problemCode(), row.language(), row.verdict(),
                row.submittedAtMs(), row.execTimeMs());
    }

    private void buildColumns() {
        TableColumn<SubmissionHistoryDto, String> when = new TableColumn<>("Time");
        when.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                STAMP.format(Instant.ofEpochMilli(c.getValue().submittedAtMs()))));
        when.setPrefWidth(90);

        // "<contestId> - <code>", not just the bare code: with "Current Contest"
        // unticked, this table can hold rows from several contests at once, and
        // problem codes are only unique WITHIN a contest — two different
        // contests each have their own "A". Without the contest number, a
        // student comparing two "A" rows has no way to tell them apart.
        TableColumn<SubmissionHistoryDto, String> problem = new TableColumn<>("Problem");
        problem.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().contestId() + " - " + c.getValue().problemCode()));
        problem.setPrefWidth(100);

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
        open.setPrefWidth(140);
        open.setMinWidth(140);
        open.setMaxWidth(140);

        table.getColumns().setAll(List.of(when, problem, lang, verdict, tests, time, mem, open));
    }

    /** The per-row entry point into {@link #openDetail}. */
    private final class OpenCell extends TableCell<SubmissionHistoryDto, Void> {

        private final Button button = new Button("View code");

        OpenCell() {
            button.getStyleClass().add("view-code-button");
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
