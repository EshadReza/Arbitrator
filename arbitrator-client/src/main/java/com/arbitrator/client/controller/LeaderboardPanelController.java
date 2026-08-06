package com.arbitrator.client.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.arbitrator.client.app.AppState;
import com.arbitrator.common.dto.LeaderboardCellDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.LeaderboardRowDto;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

/**
 * Standings view (UIF-13..16), laid out like an ICPC scoreboard.
 *
 * Each problem column carries its own header stat — how many contestants have
 * solved it out of how many have tried — so the board doubles as a read on
 * which problems are landing and which are walls, without anyone tallying
 * columns by eye.
 *
 * Cell colour is the whole language of the grid:
 *   dark green  the first accepted solution to that problem in the contest
 *   light green any other accepted solution
 *   pink        attempted and still unsolved, with the attempt count
 *   blank       never submitted to
 *
 * Problem columns are built at runtime because the count varies per contest,
 * and are pinned to a fixed width (min == pref == max). The participant column
 * is then the only one that moves: it stretches to swallow whatever space is
 * left, and stops at a floor once there are enough problems to overflow, at
 * which point the table scrolls sideways. The constrained resize policies do
 * the opposite of what a scoreboard needs here — they refuse to scroll and
 * simply clip, so with ten problems the last column silently could not be
 * reached at all.
 */
public class LeaderboardPanelController {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final PseudoClass SELF = PseudoClass.getPseudoClass("self");

    /** Wide enough for HH:MM:SS at 11px, narrow enough that 6 fit inside 1280 (§4.8). */
    private static final double PROBLEM_MIN_WIDTH = 92;
    /** Past this a three-problem board stops looking like a grid and starts looking empty. */
    private static final double PROBLEM_MAX_WIDTH = 150;

    /** Below this a name is unreadable, so the table scrolls sideways instead. */
    private static final double PARTICIPANT_MIN_WIDTH = 170;
    private static final double PARTICIPANT_MAX_WIDTH = 320;

    private static final double RANK_WIDTH = 56;
    private static final double SOLVE_WIDTH = 64;
    private static final double PENALTY_WIDTH = 84;

    /** Rank, participant, solve, penalty — everything before the problems. */
    private static final int FIXED_COLUMNS = 4;

    /** Room for the vertical scrollbar, so the rightmost column is never clipped. */
    private static final double SCROLLBAR_ALLOWANCE = 18;

    /**
     * Every row this tall, always — never the JavaFX default of "as tall as
     * this row's own content needs," which is what made the grid look
     * cramped and, worse, uneven: a row with a two-line cell (an accepted
     * time plus its attempt count) was taller than the empty row beside it.
     * ~2.3x the unstyled default (roughly 26px for 13px text).
     */
    private static final double ROW_HEIGHT = 60;

    @FXML private TableView<LeaderboardRowDto> table;
    @FXML private Label freezeBanner;
    @FXML private Label updatedLabel;

    private final AppState state = AppState.get();
    private List<String> currentColumns = List.of();
    /** Header "solved / tried" labels, one per problem column, in column order. */
    private final List<Label> columnStats = new ArrayList<>();
    /** The one column that flexes; see the class comment. */
    private TableColumn<LeaderboardRowDto, String> participant;
    /** Recomputed on resize and whenever the problem set changes. */
    private DoubleBinding problemWidth;
    private DoubleBinding participantWidth;

    @FXML
    private void initialize() {
        freezeBanner.setVisible(false);
        freezeBanner.setManaged(false);
        table.setPlaceholder(new Label("No submissions yet"));
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        // Fixed, not auto: without this every row sizes to its own tallest
        // cell, so a two-line "accepted" cell stretched its whole row while
        // the empty cells beside it stayed short — the grid line for that
        // participant visibly zig-zagged instead of running straight across.
        table.setFixedCellSize(ROW_HEIGHT);

        // UIF-15: the viewer's own row is highlighted for quick self-identification.
        table.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(LeaderboardRowDto row, boolean empty) {
                super.updateItem(row, empty);
                boolean mine = !empty && row != null && state.session() != null
                        && row.username().equals(state.session().username());
                pseudoClassStateChanged(SELF, mine);
            }
        });

        problemWidth = Bindings.createDoubleBinding(
                this::computeProblemWidth, table.widthProperty(), table.getColumns());
        participantWidth = Bindings.createDoubleBinding(
                this::computeParticipantWidth, table.widthProperty(), table.getColumns());

        buildFixedColumns();
    }

    /** What is left for the flexible columns once the fixed ones have had theirs. */
    private double available() {
        return table.getWidth() - RANK_WIDTH - SOLVE_WIDTH - PENALTY_WIDTH - SCROLLBAR_ALLOWANCE;
    }

    private int problemCount() {
        return Math.max(0, table.getColumns().size() - FIXED_COLUMNS);
    }

    /**
     * Problem columns share the slack the participant column does not claim, so
     * a three-problem board still reads as a grid instead of three narrow strips
     * marooned beside a name column half the window wide.
     */
    private double computeProblemWidth() {
        int count = problemCount();
        if (count == 0) {
            return PROBLEM_MIN_WIDTH;
        }
        double share = (available() - PARTICIPANT_MAX_WIDTH) / count;
        return clamp(PROBLEM_MIN_WIDTH, share, PROBLEM_MAX_WIDTH);
    }

    private double computeParticipantWidth() {
        double used = problemCount() * computeProblemWidth();
        return clamp(PARTICIPANT_MIN_WIDTH, available() - used, PARTICIPANT_MAX_WIDTH);
    }

    private static double clamp(double min, double value, double max) {
        return Math.max(min, Math.min(max, value));
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
        paintColumnStats(board.rows());

        // UIF-16: amber banner while frozen; judging continues underneath.
        freezeBanner.setVisible(board.frozen());
        freezeBanner.setManaged(board.frozen());

        // UIF-14: "Last Updated" below the table.
        updatedLabel.setText("Last updated "
                + CLOCK.format(Instant.ofEpochMilli(board.lastUpdatedMs())));
    }

    /**
     * Counts are taken from the rows actually on screen, not from a separate
     * server tally, so the header can never disagree with the column beneath
     * it — including while frozen, when the board is deliberately showing less
     * than the judge knows.
     */
    private void paintColumnStats(List<LeaderboardRowDto> rows) {
        for (int i = 0; i < columnStats.size(); i++) {
            int accepted = 0;
            int tried = 0;
            for (LeaderboardRowDto row : rows) {
                if (i >= row.cells().size()) {
                    continue;
                }
                LeaderboardCellDto cell = row.cells().get(i);
                if (cell == null) {
                    continue;
                }
                if (cell.solved()) {
                    accepted++;
                    tried++;
                } else if (cell.failedAttempts() > 0) {
                    tried++;
                }
            }
            columnStats.get(i).setText(accepted + " / " + tried);
        }
    }

    private void buildFixedColumns() {
        TableColumn<LeaderboardRowDto, String> rank = new TableColumn<>("Rank");
        rank.setCellValueFactory(c ->
                new ReadOnlyObjectWrapper<>(String.valueOf(c.getValue().rank())));
        fix(rank, RANK_WIDTH);

        participant = new TableColumn<>("Participant");
        participant.setCellValueFactory(c ->
                new ReadOnlyObjectWrapper<>(c.getValue().displayName()));
        // Bound rather than set, so the width follows the table and the problem
        // count instead of being decided once at construction.
        pin(participant, participantWidth);

        TableColumn<LeaderboardRowDto, String> solved = new TableColumn<>("Solve");
        solved.setCellValueFactory(c ->
                new ReadOnlyObjectWrapper<>(String.valueOf(c.getValue().solved())));
        fix(solved, SOLVE_WIDTH);

        TableColumn<LeaderboardRowDto, String> penalty = new TableColumn<>("Penalty");
        penalty.setCellValueFactory(c ->
                new ReadOnlyObjectWrapper<>(String.valueOf(c.getValue().penaltyMinutes())));
        fix(penalty, PENALTY_WIDTH);

        // Order matters: FIXED_COLUMNS assumes the problems come after these.
        table.getColumns().setAll(List.of(rank, participant, solved, penalty));
    }

    private void rebuildProblemColumns(List<String> codes) {
        // keep the four fixed columns, replace the rest
        table.getColumns().remove(4, table.getColumns().size());
        columnStats.clear();

        for (int i = 0; i < codes.size(); i++) {
            final int index = i;
            TableColumn<LeaderboardRowDto, LeaderboardCellDto> col = new TableColumn<>();
            col.setGraphic(problemHeader(codes.get(i)));
            pin(col, problemWidth);
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

    /** Problem letter over its "solved / tried" tally, as one header graphic. */
    private VBox problemHeader(String code) {
        Label letter = new Label(code);
        letter.getStyleClass().add("standings-code");

        Label stat = new Label("0 / 0");
        stat.getStyleClass().add("standings-stat");
        columnStats.add(stat);

        VBox head = new VBox(letter, stat);
        head.setAlignment(Pos.CENTER);
        head.getStyleClass().add("standings-head");
        return head;
    }

    /** Pins a column to a constant width. */
    private static void fix(TableColumn<?, ?> column, double width) {
        column.setMinWidth(width);
        column.setPrefWidth(width);
        column.setMaxWidth(width);
    }

    /**
     * Pins a column to a computed width. All three properties are bound, not
     * just pref: leaving min and max loose lets the table hand a column
     * whatever is left over during layout, which is precisely the stretching
     * this grid must not do.
     */
    private static void pin(TableColumn<?, ?> column, DoubleBinding width) {
        column.minWidthProperty().bind(width);
        column.prefWidthProperty().bind(width);
        column.maxWidthProperty().bind(width);
    }

    /**
     * One problem cell: when it fell on the contest clock, and what it cost to
     * get there. Two labels rather than a "\n" string so the time and the
     * attempt count can be sized and coloured independently, and so the second
     * line can be dropped entirely (unmanaged) rather than left as a blank line
     * that pushes the time off centre.
     */
    private static final class ProblemCell
            extends TableCell<LeaderboardRowDto, LeaderboardCellDto> {

        private final Label headline = new Label();
        private final Label attempts = new Label();
        private final VBox box = new VBox(headline, attempts);

        ProblemCell() {
            headline.getStyleClass().add("standings-when");
            attempts.getStyleClass().add("standings-tries");
            box.setAlignment(Pos.CENTER);
            box.getStyleClass().add("standings-cell-box");
            setText(null);
        }

        @Override
        protected void updateItem(LeaderboardCellDto cell, boolean empty) {
            super.updateItem(cell, empty);
            getStyleClass().removeAll("cell-first", "cell-solved", "cell-failed");

            boolean nothing = empty || cell == null
                    || (!cell.solved() && cell.failedAttempts() == 0);
            if (nothing) {
                setGraphic(null);
                return;
            }

            if (cell.solved()) {
                headline.setText(elapsed(cell.solvedAtSeconds()));
                show(attempts, cell.failedAttempts() > 0
                        ? "(-" + cell.failedAttempts() + ")" : null);
                getStyleClass().add(cell.firstSolve() ? "cell-first" : "cell-solved");
            } else {
                headline.setText("(-" + cell.failedAttempts() + ")");
                show(attempts, null);
                getStyleClass().add("cell-failed");
            }
            setGraphic(box);
        }

        private static void show(Label label, String text) {
            label.setText(text == null ? "" : text);
            label.setVisible(text != null);
            label.setManaged(text != null);
        }

        /**
         * Elapsed contest time as H:MM:SS, hours uncapped — a lab contest runs
         * for hours but the same board is used for week-long practice sets,
         * where wrapping at 24 would quietly mislabel day two as day one.
         */
        private static String elapsed(long totalSeconds) {
            if (totalSeconds < 0) {
                return "—";
            }
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;
            return "%d:%02d:%02d".formatted(hours, minutes, seconds);
        }
    }
}
