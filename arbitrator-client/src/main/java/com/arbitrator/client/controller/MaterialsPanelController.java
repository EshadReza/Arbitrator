package com.arbitrator.client.controller;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.arbitrator.client.app.AppState;
import com.arbitrator.common.dto.MaterialDto;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * FR-07 sibling, contestant side: every downloadable material — slides,
 * PDFs, any file the instructor uploaded — for this contest.
 *
 * Unlike announcements or a PDF problem statement, there is nothing to
 * render in-app: a material is opaque bytes the client just saves wherever
 * the student points a save dialog. No WebView, no theme-baked HTML.
 */
public class MaterialsPanelController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

    @FXML private VBox listBox;
    @FXML private Label countLabel;

    private final AppState state = AppState.get();

    @FXML
    private void initialize() {
        refresh();
    }

    /** Safe from any thread; called on the materials-changed push too. */
    public void refresh() {
        Thread worker = new Thread(() -> {
            try {
                long contestId = state.contest() == null ? -1 : state.contest().contestId();
                List<MaterialDto> items = state.api().materials(contestId);
                Platform.runLater(() -> apply(items));
            } catch (Exception e) {
                Platform.runLater(() -> countLabel.setText("Could not load materials"));
            }
        }, "materials-io");
        worker.setDaemon(true);
        worker.start();
    }

    private void apply(List<MaterialDto> items) {
        listBox.getChildren().clear();
        countLabel.setText(items.isEmpty() ? "No materials yet"
                : items.size() + (items.size() == 1 ? " file" : " files"));
        items.forEach(m -> listBox.getChildren().add(row(m)));
    }

    private VBox row(MaterialDto m) {
        Label name = new Label(m.filename());
        name.setWrapText(true);

        Label meta = new Label(humanSize(m.sizeBytes()) + "  ·  "
                + STAMP.format(Instant.ofEpochMilli(m.uploadedAtMs())));
        meta.getStyleClass().add("subtitle");

        VBox info = new VBox(2, name, meta);
        HBox.setHgrow(info, Priority.ALWAYS);

        Button download = new Button("⬇ Download");
        download.getStyleClass().add("view-code-button");
        download.setOnAction(e -> download(m, download));

        HBox line = new HBox(10, info, download);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(line);
        card.getStyleClass().addAll("box", "list-card");
        card.setPadding(new Insets(10));
        return card;
    }

    private void download(MaterialDto m, Button trigger) {
        Window window = trigger.getScene() != null ? trigger.getScene().getWindow() : null;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save " + m.filename());
        chooser.setInitialFileName(m.filename());
        File target = chooser.showSaveDialog(window);
        if (target == null) {
            return;
        }

        trigger.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                byte[] bytes = state.api().materialBytes(m.id());
                Files.write(target.toPath(), bytes);
                Platform.runLater(() -> trigger.setDisable(false));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    trigger.setDisable(false);
                    new Alert(AlertType.ERROR,
                            "Could not download " + m.filename() + ": " + e.getMessage())
                            .showAndWait();
                });
            }
        }, "material-download");
        worker.setDaemon(true);
        worker.start();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
