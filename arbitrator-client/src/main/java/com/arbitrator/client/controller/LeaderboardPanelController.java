package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.arbitrator.client.app.AppState;
import com.arbitrator.common.dto.LeaderboardCellDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

/**
 * Standings view (UIF-13..16).
 *
 * Problem columns are built at runtime because the count varies per contest.
 * Column widths are fixed and narrow so 6 problems still fit inside 1280 px
 * without horizontal scrolling — the acceptance criterion in §4.8.
 */
public class LeaderboardPanelController {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @FXML private TableView<LeaderboardRowDto> table;
    @FXML private Label freezeBanner;
    @FXML private Label updatedLabel;

    private final AppState state = AppState.get();
    private List<String> currentColumns = List.of();

    @FXML
    private void initialize() {
        freezeBanner.setVisible(false);
        freezeBanner.setManaged(false);
        table.setPlaceholder(new Label("No submissions yet"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // UIF-15: the viewer's own row is highlighted for quick self-identification.
        table.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(LeaderboardRowDto row, boolean empty) {
                super.updateItem(row, empty);
                boolean mine = !empty && row != null && state.session() != null
                        && row.username().equals(state.session().username());
                pseudoClassStateChanged(
                        javafx.css.PseudoClass.getPseudoClass("self"), mine);
            }
        });

        buildFixedColumns();
    }

    /** Safe to call from any thread. */
    public void update(LeaderboardDto board) {
        Platform.runLater(() -> apply(board));
    }

    private void apply(LeaderboardDto board) {
        if (!board.problemCodes().equals(currentColumns)) {
            currentColumns = List.copyOf(board.problemCodes());
            rebuildProblemColumns(currentColumns);
        }
        table.setItems(FXCollections.observableArrayList(board.rows()));

        // UIF-16: amber banner while frozen; judging continues underneath.
        freezeBanner.setVisible(board.frozen());
        freezeBanner.setManaged(board.frozen());

        // UIF-14: "Last Updated" below the table.
        updatedLabel.setText("Last updated "
                + CLOCK.format(Instant.ofEpochMilli(board.lastUpdatedMs())));
    }

    private void buildFixedColumns() {
        TableColumn<LeaderboardRowDto, String> rank = new TableColumn<>("#");
        rank.setCellValueFactory(c ->
                new ReadOnlyObjectWrapper<>(String.valueOf(c.getValue().rank())));
        rank.setPrefWidth(48);

        TableColumn<LeaderboardRowDto, String> who = new TableColumn<>("Who");
        who.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().displayName()));
        who.setPrefWidth(200);

        TableColumn<LeaderboardRowDto, String> solved = new TableColumn<>("=");
        solved.setCellValueFactory(c ->
                new ReadOnlyObjectWrapper<>(String.valueOf(c.getValue().solved())));
        solved.setPrefWidth(56);

        TableColumn<LeaderboardRowDto, String> penalty = new TableColumn<>("Penalty");
        penalty.setCellValueFactory(c ->
                new ReadOnlyObjectWrapper<>(String.valueOf(c.getValue().penaltyMinutes())));
        penalty.setPrefWidth(80);

        table.getColumns().setAll(List.of(rank, who, solved, penalty));
    }

    private void rebuildProblemColumns(List<String> codes) {
        // keep the four fixed columns, replace the rest
        table.getColumns().remove(4, table.getColumns().size());

        for (int i = 0; i < codes.size(); i++) {
            final int index = i;
            TableColumn<LeaderboardRowDto, LeaderboardCellDto> col =
                    new TableColumn<>(codes.get(i));
            col.setPrefWidth(74);
            col.setSortable(false);
            col.setCellValueFactory(c -> {
                List<LeaderboardCellDto> cells = c.getValue().cells();
                return new ReadOnlyObjectWrapper<>(
                        index < cells.size() ? cells.get(index) : null);
            });
            col.setCellFactory(c -> new ProblemCell());
            table.getColumns().add(col);
        }
    }

    /** Codeforces cell language: "+" / "+2" green with the minute, "-3" red. */
    private static final class ProblemCell extends TableCell<LeaderboardRowDto, LeaderboardCellDto> {

        @Override
        protected void updateItem(LeaderboardCellDto cell, boolean empty) {
            super.updateItem(cell, empty);
            getStyleClass().removeAll("cell-solved", "cell-failed");

            if (empty || cell == null) {
                setText(null);
                return;
            }
            if (cell.solved()) {
                String attempts = cell.failedAttempts() > 0
                        ? "+" + cell.failedAttempts() : "+";
                setText(attempts + "\n" + cell.solvedAtMinutes());
                getStyleClass().add("cell-solved");
            } else if (cell.failedAttempts() > 0) {
                setText("-" + cell.failedAttempts());
                getStyleClass().add("cell-failed");
            } else {
                setText(null);
            }
        }
    }
}
