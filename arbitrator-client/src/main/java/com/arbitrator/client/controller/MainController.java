package com.arbitrator.client.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.arbitrator.client.app.AppState;
import com.arbitrator.client.app.DraftStore;
import com.arbitrator.client.app.PdfStatementRenderer;
import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.client.net.JudgeApi;
import com.arbitrator.client.net.JudgeApi.ApiException;
import com.arbitrator.common.dto.ProblemDetailDto;
import com.arbitrator.common.dto.ProblemSummaryDto;
import com.arbitrator.common.dto.SubmitAckDto;
import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Language;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.scene.web.WebView;
import javafx.util.Duration;

/**
 * Main contest view (SRS §4.2/4.3/4.4).
 *
 * The countdown is driven by contest state pushed from the server (FR-06), not
 * by a purely local timer — otherwise pausing, extending or ending the contest
 * on the server changes nothing on screen.
 */
public class MainController {

    @FXML private StackPane rootStack;
    /** Everything except the overlay layer; this is what zoom scales. */
    @FXML private BorderPane contentRoot;
    @FXML private Label zoomLabel;
    @FXML private TabPane tabs;
    @FXML private Label contestTitleLabel;
    @FXML private Label contestNumberLabel;
    @FXML private Label timerLabel;
    @FXML private Label userAvatar;
    @FXML private Label userLabel;
    @FXML private Label connLabel;
    @FXML private Button changeServerButton;
    @FXML private Button themeButton;

    @FXML private SplitPane workspaceSplit;
    @FXML private VBox problemPane;
    @FXML private VBox statementPane;
    @FXML private ListView<ProblemSummaryDto> problemList;
    @FXML private Label problemHeader;
    @FXML private WebView statementView;
    @FXML private Button statementFsButton;
    @FXML private Button collapseButton;
    @FXML private Label layoutHint;

    // injected from <fx:include fx:id="..."> in main.fxml
    @FXML private EditorController editorPanelController;
    @FXML private LeaderboardPanelController leaderboardPanelController;
    @FXML private SubmissionsPanelController submissionsPanelController;
    @FXML private AnnouncementsPanelController announcementsPanelController;
    @FXML private MaterialsPanelController materialsPanelController;
    @FXML private ClarificationsPanelController clarificationsPanelController;

    private final AppState state = AppState.get();
    private Timeline timer;
    private Timeline watchdog;
    private FadeTransition pulse;
    private boolean ended;

    /** Guards against the watchdog stacking reconnect attempts. */
    private volatile boolean reconnecting;

    /**
     * Consecutive missed watchdog ticks (item 7). A single miss is often just
     * a heartbeat blip that resolves itself within a second or two; flipping
     * the visible "offline" banner on the very first one made the label
     * flicker on every minor hiccup. Only the second consecutive miss in a
     * row (~8s of genuinely being down) updates what the student sees.
     */
    private int missedTicks;

    /**
     * Per-problem editor drafts, kept for the session. Switching problems must
     * not discard work — losing code mid-contest is unforgivable.
     */
    private final Map<Long, Draft> drafts = new HashMap<>();
    private long currentProblemId = -1;

    /** Problem id + statement currently painted, so identical HTML is not reloaded. */
    private String loadedStatementKey;

    private record Draft(String code, Language language) { }

    /** Panes hidden by a fullscreen or collapse toggle, in their original order. */
    private List<Node> workspacePanes;
    private boolean problemListHidden;
    private Node fullscreenPane;

    @FXML
    private void initialize() {
        contestTitleLabel.setText(state.contest().title());
        contestNumberLabel.setText("#" + state.contest().contestId());
        userLabel.setText(state.session().displayName());
        String initial = state.session().displayName().isBlank() ? "?"
                : state.session().displayName().substring(0, 1).toUpperCase();
        userAvatar.setText(initial);
        workspacePanes = List.copyOf(workspaceSplit.getItems());

        problemList.setCellFactory(v -> new ProblemCell());
        problemList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> {
                    // A background refresh (every ~10s heartbeat or after a verdict)
                    // replaces the ListView's items, which briefly clears and then
                    // re-establishes the selection even when it's the same problem.
                    // Without this guard that spurious reselect calls restoreDraft()
                    // -> setCode() -> moveTo(end), yanking the caret every refresh.
                    if (sel == null || sel.id() == currentProblemId) {
                        return;
                    }
                    if (currentProblemId != -1) {
                        stashDraft(currentProblemId);
                    }
                    currentProblemId = sel.id();
                    restoreDraft(sel.id());
                    loadProblem(sel.id());
                });

        // A WebView paints its own opaque white backdrop behind the page, so a
        // dark statement needs the fill changed too — page CSS alone can't fix it.
        applyWebViewFill();
        editorPanelController.setSubmitHandler(this::submit);
        editorPanelController.setFullscreenHandler(this::toggleEditorFullscreen);
        editorPanelController.setRunHandler(this::runCustom);
        // The board asks what to offer in its problem picker; it must never
        // keep its own copy, or it will drift from what is actually released.
        clarificationsPanelController.setProblemSupplier(
                () -> List.copyOf(problemList.getItems()));
        themeButton.setText(state.darkMode() ? "☼ Light mode" : "☾ Dark mode");

        // Submissions/Standings selection is just "I clicked here," not a real
        // selection with any downstream effect — it must not survive leaving
        // the tab, or the next visit shows a stale highlight nobody chose.
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            submissionsPanelController.clearSelection();
            leaderboardPanelController.clearSelection();
        });

        startTimer();
        connectLive();
        startConnectionWatchdog();
        refreshProblems(true);

        Platform.runLater(this::installShortcuts);
    }

    /**
     * Zoom steps, browser-style. A fixed ladder rather than "current ± 0.1":
     * with a free-running delta, mixing Ctrl+scroll (a small step) and
     * Ctrl+plus (a larger one) leaves you on values like 1.05 that no number
     * of further steps ever lands back on exactly 1.0, so the UI could not be
     * returned to true 100% without the reset shortcut nobody knows about.
     * Every level here is reachable in both directions and 100 is one of them.
     */
    private static final int[] ZOOM_LEVELS = { 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200 };

    /** See the comment at its use in {@link #onContestState} for why this exists. */
    private static final long RESTART_TOLERANCE_MS = 3000;

    /** Index into {@link #ZOOM_LEVELS}; starts on 100%. */
    private int zoomIndex = 5;

    /** Applied to contentRoot with a 0,0 pivot so scaling grows right/down. */
    private final Scale zoomScale = new Scale(1, 1);

    private void changeZoom(int steps) {
        int next = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, zoomIndex + steps));
        if (next != zoomIndex) {
            zoomIndex = next;
            applyZoom();
        }
    }

    private void resetZoom() {
        zoomIndex = 5;                      // ZOOM_LEVELS[5] == 100
        applyZoom();
    }

    @FXML private void onZoomIn()    { changeZoom(+1); }
    @FXML private void onZoomOut()   { changeZoom(-1); }
    @FXML private void onZoomReset() { resetZoom(); }

    /**
     * A real zoom, not a visual one.
     *
     * The old implementation set scaleX/scaleY on the root. That is a render
     * transform: it stretches the finished picture without telling the layout
     * anything, so the content kept the size of the unzoomed window — zooming
     * out shrank the whole UI away from the window edges and left a border of
     * dead background around it, and zooming in pushed it past those edges
     * where it was simply clipped and unreachable. Exactly the "whitespace
     * outwards / doesn't fit anymore" this replaces.
     *
     * Scaling by z while laying the content out at (window / z) gives the
     * browser behaviour instead: everything is drawn z times larger AND the
     * content still occupies exactly the window, so it reflows into the space
     * rather than overflowing it. Panels re-divide, tables re-measure, and
     * nothing is left stranded off-screen.
     */
    private void applyZoom() {
        double z = ZOOM_LEVELS[zoomIndex] / 100.0;
        zoomScale.setX(z);
        zoomScale.setY(z);
        if (contentRoot != null && rootStack.getWidth() > 0) {
            // The compensating half: lay out in logical pixels that the scale
            // then multiplies back up to exactly the real window size.
            //
            // min AND max, not just pref: contentRoot's parent is a StackPane,
            // which resizes every child to fill itself and treats pref as a
            // suggestion it is free to ignore — which it did, so the content
            // stayed window-sized and the scale shrank it away from the edges.
            // Pinning min == pref == max leaves the StackPane no choice.
            double logicalWidth = rootStack.getWidth() / z;
            double logicalHeight = rootStack.getHeight() / z;
            contentRoot.setMinSize(logicalWidth, logicalHeight);
            contentRoot.setPrefSize(logicalWidth, logicalHeight);
            contentRoot.setMaxSize(logicalWidth, logicalHeight);
        }
        if (zoomLabel != null) {
            zoomLabel.setText(ZOOM_LEVELS[zoomIndex] + "%");
        }
    }

    /** Hooks the zoom transform up once the scene exists. */
    private void installZoom() {
        // Anchor top-left: a StackPane centres its children, so an
        // above-100% layout (wider than the window by design) would be
        // centred on negative coordinates and lose its left edge off-screen,
        // while the scale pivot below expands from 0,0.
        StackPane.setAlignment(contentRoot, Pos.TOP_LEFT);
        contentRoot.getTransforms().add(zoomScale);
        // Re-derive the logical size on every window resize, or the content
        // keeps the size computed for the previous window and the zoom stops
        // matching the frame.
        rootStack.widthProperty().addListener((o, a, b) -> applyZoom());
        rootStack.heightProperty().addListener((o, a, b) -> applyZoom());

        // F11 fullscreen (SceneRouter.init) is a Stage-level transition, not
        // a plain resize: some platforms fire rootStack's width/height
        // changes mid-animation, before the Stage has settled at its true
        // fullscreen (or restored windowed) size. Since applyZoom() cements
        // that instant's dimensions into contentRoot's min/pref/max, landing
        // on one of those transitional values froze the layout distorted —
        // reported as the app's own background gradient (rootStack sits
        // behind contentRoot) showing through where contentRoot no longer
        // covered the full window — reachable via the reported "zoom, F11,
        // zoom again" sequence, with no further resize event ever arriving
        // to correct it.
        //
        // A single Platform.runLater (one pulse later) was not enough: the
        // native OS fullscreen transition — sliding to/from a new Space on
        // macOS, similar animated transitions elsewhere — runs as a
        // multi-hundred-millisecond system animation, not a single JavaFX
        // pulse, so one runLater can itself land mid-animation. Re-applying
        // several times over a window comfortably longer than that
        // animation is the correction pass on top of the width/height
        // listeners above; each call is cheap (a resize computation, no
        // visible flicker) so over-correcting costs nothing.
        if (rootStack.getScene() != null && rootStack.getScene().getWindow() instanceof javafx.stage.Stage stage) {
            stage.fullScreenProperty().addListener((o, a, b) -> {
                for (long delayMs : new long[] {0, 80, 180, 350, 600}) {
                    PauseTransition pt = new PauseTransition(Duration.millis(delayMs));
                    pt.setOnFinished(ev -> applyZoom());
                    pt.play();
                }
            });
        }
        applyZoom();
    }

    private void installShortcuts() {
        var scene = rootStack.getScene();
        var accelerators = scene.getAccelerators();
        // NFR-U04: everything reachable without a mouse.
        accelerators.put(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN),
                editorPanelController::fireSubmit);
        accelerators.put(new KeyCodeCombination(KeyCode.D, KeyCombination.CONTROL_DOWN),
                this::onToggleTheme);
        accelerators.put(new KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN),
                this::onToggleProblemList);
        // F11 is now real OS-level window fullscreen, wired once at the Stage
        // level in SceneRouter.init() so it works on every screen, not just
        // here — see there for why. The statement/editor panel-collapse
        // "fullscreen" toggle below is unrelated and still reachable from its
        // own button (⛶), just no longer bound to F11.
        accelerators.put(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN),
                this::onRefresh);
        accelerators.put(new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.CONTROL_DOWN), () -> changeZoom(+1));
        accelerators.put(new KeyCodeCombination(KeyCode.ADD, KeyCombination.CONTROL_DOWN), () -> changeZoom(+1));
        accelerators.put(new KeyCodeCombination(KeyCode.MINUS, KeyCombination.CONTROL_DOWN), () -> changeZoom(-1));
        accelerators.put(new KeyCodeCombination(KeyCode.SUBTRACT, KeyCombination.CONTROL_DOWN), () -> changeZoom(-1));
        accelerators.put(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.CONTROL_DOWN), this::resetZoom);

        scene.setOnScroll(event -> {
            if (event.isControlDown()) {
                if (event.getDeltaY() > 0) {
                    changeZoom(+1);
                } else if (event.getDeltaY() < 0) {
                    changeZoom(-1);
                }
                event.consume();
            }
        });

        installZoom();
    }

    // --- layout: collapse and fullscreen -----------------------------------

    /** Hides the problem list so the statement and editor get the width. */
    @FXML
    private void onToggleProblemList() {
        if (fullscreenPane != null) {
            return;                       // fullscreen already governs the layout
        }
        problemListHidden = !problemListHidden;
        rebuildWorkspace();
        collapseButton.setText(problemListHidden ? "◨ Show problem list" : "◧ Hide problem list");
    }

    @FXML
    private void onToggleStatementFullscreen() {
        toggleFullscreen(statementPane, statementFsButton);
    }

    /** Called by the editor panel's own fullscreen button. */
    public void toggleEditorFullscreen() {
        toggleFullscreen(workspacePanes.get(2), editorPanelController.fullscreenButton());
    }

    private void toggleFullscreen(Node pane, Button button) {
        boolean entering = fullscreenPane != pane;
        fullscreenPane = entering ? pane : null;
        rebuildWorkspace();

        statementFsButton.setText("⛶");
        editorPanelController.fullscreenButton().setText("⛶");
        if (entering && button != null) {
            button.setText("⤢");          // click again to restore
        }
        layoutHint.setText(entering ? "Fullscreen — press again or Esc to restore" : "");
        collapseButton.setDisable(entering);
    }

    /** Single place that decides which panes are in the SplitPane. */
    private void rebuildWorkspace() {
        if (fullscreenPane != null) {
            workspaceSplit.getItems().setAll(fullscreenPane);
            return;
        }
        workspaceSplit.getItems().setAll(workspacePanes.stream()
                .filter(p -> !(problemListHidden && p == problemPane))
                .toList());
        workspaceSplit.setDividerPositions(problemListHidden
                ? new double[] { 0.5 }
                : new double[] { 0.20, 0.60 });
    }

    private void applyWebViewFill() {
        statementView.setPageFill(state.darkMode()
                ? Color.web("#1c222c") : Color.web("#ffffff"));
    }

    // --- session actions ------------------------------------------------------

    /**
     * Re-pulls everything from the server. Push channels normally keep the UI
     * current, but a manual refresh means a missed update never requires
     * restarting the application.
     */
    @FXML
    private void onRefresh() {
        submissionsPanelController.refresh();
        announcementsPanelController.refresh();
        materialsPanelController.refresh();
        async(() -> {
            try {
                long id = state.contest().contestId();
                // Contest state first: it decides whether problems are released
                // at all, so fetching problems before it can show a stale list.
                // Password (if any) is whatever unlocked this contest in the
                // picker — see AppState.contestPassword() — not re-prompted here.
                var contestState = state.api().contest(id, state.contestPassword());
                onContestState(contestState);
                if (contestState.state().releasesProblems()) {
                    List<ProblemSummaryDto> items = state.api().problems();
                    Platform.runLater(() -> applyProblems(items, false));
                }
                var board = state.api().leaderboard();
                leaderboardPanelController.update(board);
                Platform.runLater(() -> toast(bundle("main.refreshed")));
            } catch (Exception e) {
                Platform.runLater(() -> toast("Refresh failed: " + e.getMessage()));
            }
        });
    }

    /** Back to the picker, keeping the session — same user, different contest. */
    @FXML
    private void onSwitchContest() {
        stashDraft(currentProblemId);      // this screen is about to be rebuilt
        stopClocks();
        SceneRouter.showContestPicker();
    }

    @FXML
    private void onSignOut() {
        // Save BEFORE the session is cleared: the draft is filed under the
        // username, and afterwards there is no username to file it under.
        stashDraft(currentProblemId);
        stopClocks();

        // logout() must reach the server and be handled BEFORE dropConnection()
        // closes the WebSocket — PresenceTracker.signOut() (which is what makes
        // the instructor's console show DISCONNECTED right now, and releases the
        // single-session slot so the next sign-in never wrongly hits "already
        // logged in elsewhere") reads this session's live subscription, which
        // dropConnection() is what tears down. Running both on a background
        // thread, in that order, keeps the sequence deterministic instead of
        // racing a local socket close against a LAN round trip — and keeps a
        // slow/dead network from stalling the sign-out the user already asked
        // for; logout() failing is a lost nicety, not a reason to stay signed in.
        JudgeApi api = state.api();
        Thread worker = new Thread(() -> {
            try {
                api.logout();
            } catch (ApiException ignored) {
                // Best-effort — see JudgeApi.logout()'s javadoc. PresenceTracker's
                // own grace-period fallback still catches this via the socket
                // close below, just without the immediacy.
            }
            api.dropConnection();
            Platform.runLater(() -> {
                state.setSession(null);
                state.setContestCleared();
                SceneRouter.showLogin();
            });
        }, "sign-out");
        worker.setDaemon(true);
        worker.start();
    }

    /** Timers keep firing against a dead scene otherwise. */
    private void stopClocks() {
        if (timer != null) {
            timer.stop();
        }
        if (watchdog != null) {
            watchdog.stop();
        }
        stopPulse();
    }

    // --- theme --------------------------------------------------------------

    @FXML
    private void onToggleTheme() {
        boolean dark = !state.darkMode();
        state.setDarkMode(dark);
        SceneRouter.applyTheme(rootStack.getScene());
        applyWebViewFill();
        themeButton.setText(dark ? "☼ Light mode" : "☾ Dark mode");
        // Re-render the statement so its inline CSS matches the new theme.
        var selected = problemList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            loadProblem(selected.id());
        }
        // Same reason: each announcement card is a WebView whose background/
        // text colors are baked into the loaded HTML at render time (see
        // AnnouncementsPanelController.card()), not driven by theme CSS. Left
        // alone, a card rendered under one theme stays that color forever —
        // including after the top-bar Refresh button, which never touches
        // this panel either — so toggling light/dark leaves old cards as a
        // wrong-colored, near-unreadable block until a new announcement
        // happens to arrive and re-triggers a render.
        announcementsPanelController.refresh();
    }

    // --- problems -----------------------------------------------------------

    private void refreshProblems(boolean selectFirst) {
        async(() -> {
            try {
                List<ProblemSummaryDto> items = state.api().problems();
                Platform.runLater(() -> applyProblems(items, selectFirst));
            } catch (Exception e) {
                Platform.runLater(() -> toast("Could not load problems: " + e.getMessage()));
            }
        });
    }

    /** Applies a problem list, keeping the current selection where possible. */
    private void applyProblems(List<ProblemSummaryDto> items, boolean selectFirst) {
        // onContestState() calls this on EVERY push (every 10s by default),
        // not just when something actually changed. items.setAll() clears
        // and repopulates the ListView unconditionally, which drops the
        // selection for a frame and re-lays-out every cell — visible as the
        // list flashing/looking briefly empty on a steady 10s cadence, even
        // though nothing about it changed. ProblemSummaryDto is a record, so
        // List.equals() is a real content comparison, not identity — skip
        // the whole update when it would be a no-op. selectFirst still forces
        // through: a genuine state change (e.g. LOBBY -> ACTIVE re-releasing
        // problems after the list was just cleared) must always apply.
        if (!selectFirst && items.equals(problemList.getItems())) {
            return;
        }
        var selected = problemList.getSelectionModel().getSelectedItem();
        problemList.getItems().setAll(items);
        if (items.isEmpty()) {
            // Deleting the last problem leaves nothing selected, so the
            // selection listener never fires and the previous statement stayed
            // on screen — a problem the student could still read but no longer
            // submit to. Clear the panes explicitly.
            currentProblemId = -1;
            problemHeader.setText("");
            statementView.getEngine().loadContent(noProblemsPage());
            return;
        }
        if (selectFirst || selected == null) {
            problemList.getSelectionModel().select(0);
            return;
        }
        items.stream().filter(p -> p.id() == selected.id()).findFirst()
                .ifPresentOrElse(p -> problemList.getSelectionModel().select(p),
                        () -> problemList.getSelectionModel().select(0));
    }

    private void loadProblem(long id) {
        async(() -> {
            try {
                ProblemDetailDto detail = state.api().problem(id);

                // A PDF statement is fetched and rasterised here, off the FX
                // thread: PDFBox rendering several pages is far too slow to do
                // on it, and this method already runs in the background.
                String pdfHtml = null;
                if (detail.pdfStatement()) {
                    pdfHtml = PdfStatementRenderer.toHtml(
                            state.api().problemStatementPdf(id), state.darkMode());
                }
                final String rendered = pdfHtml;

                Platform.runLater(() -> {
                    problemHeader.setText(detail.code() + ". " + detail.title()
                            + "   —   " + detail.timeLimitMs() + " ms / "
                            + detail.memoryLimitKb() / 1024 + " MB");
                    // Reloading identical HTML would reset the scroll position
                    // on every 10 s state push, so only repaint on a real edit.
                    // The theme is part of the key because a PDF is rasterised
                    // per theme: toggling must force the repaint that unchanged
                    // content would otherwise skip.
                    String key = id + " " + state.darkMode() + " "
                            + (rendered != null ? "pdf:" + rendered.length()
                                                : detail.statementHtml());
                    if (!key.equals(loadedStatementKey)) {
                        loadedStatementKey = key;
                        statementView.getEngine().loadContent(rendered != null
                                ? rendered : wrapStatement(detail.statementHtml()));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    loadedStatementKey = null;
                    statementView.getEngine().loadContent("<p>Problem unavailable</p>");
                });
            }
        });
    }

    /** Statement styling follows the app theme, inline so WebView needs no assets. */
    private String wrapStatement(String html) {
        boolean dark = state.darkMode();
        String bg = dark ? "#1c222c" : "#ffffff";
        String fg = dark ? "#e6eaf0" : "#1c2430";
        String muted = dark ? "#9aa5b4" : "#6b7684";
        String sampleBg = dark ? "#232a36" : "#f5f7fa";
        String border = dark ? "#313a49" : "#d6dbe3";
        String link = dark ? "#7fb4ff" : "#2f6fd0";
        return """
                <html><head><meta charset="utf-8"><style>
                  html, body { color: %s !important; background: %s !important; }
                  body { font-family: -apple-system, "Segoe UI", sans-serif; font-size: 14px;
                         margin: 18px; line-height: 1.55; }
                  /* Uploaded statements are whole HTML documents carrying their own
                     <style> block — typically light-theme colours such as
                     `code{background:#f3f4f6}`. That block appears *after* this one
                     in the document, so without !important it wins on source order
                     and leaves white boxes; combined with the forced light text
                     below that is white-on-white and completely unreadable.
                     Everything the uploaded sheet can set has to be reclaimed. */
                  *, h1, h2, h3, h4, h5, h6, p, li, td, th, div, span, b, i, em, strong,
                  code, pre, blockquote, sup, sub, table, caption { color: %s !important; }
                  /* Wildcard first, then the specific rules below out-specify it
                     (both !important, so specificity decides the winner). */
                  * { background: transparent !important; background-color: transparent !important; }
                  a { color: %s !important; }
                  h1 { font-size: 19px; margin: 0 0 4px; }
                  h2 { font-size: 15px; margin-top: 20px; }
                  h1, h2, h3, h4, h5, h6 { border-color: %s !important; }
                  .limits { color: %s !important; font-size: 12px; margin-bottom: 14px; }
                  pre.sample, pre, code, kbd, samp, tt {
                                    border: 1px solid %s !important;
                                    background: %s !important;
                                    background-color: %s !important;
                                    padding: 10px; border-radius: 6px;
                                    font-family: ui-monospace, monospace;
                                    overflow-x: auto; }
                  /* Inline code sits inside a sentence — full block padding there
                     produced the tall white chips seen in the statement body. */
                  code { padding: 1px 5px; }
                  pre code { padding: 0; border: 0 !important; background: transparent !important; }
                  table { border-collapse: collapse; }
                  td, th { border: 1px solid %s !important; padding: 4px 8px; }
                  th { background: %s !important; }
                </style></head><body>""".formatted(
                        fg, bg, fg, link, border, muted, border, sampleBg, sampleBg,
                        border, sampleBg)
                + html + "</body></html>";
    }

    // --- per-problem drafts --------------------------------------------------

    private void stashDraft(long problemId) {
        if (problemId < 0) {
            return;
        }
        String code = editorPanelController.getCode();
        Language language = editorPanelController.getLanguage();
        drafts.put(problemId, new Draft(code, language));
        // Also to disk: the in-memory map dies with this screen, and signing
        // out or switching contest rebuilds it — which is how code that had
        // already been written, and even submitted, was being thrown away.
        DraftStore.save(username(), contestId(), problemId, language, code);
    }

    private void restoreDraft(long problemId) {
        Draft draft = drafts.get(problemId);
        if (draft == null) {
            // Nothing this session — fall back to what a previous session left.
            var stored = DraftStore.load(username(), contestId(), problemId);
            if (stored.isPresent()) {
                draft = new Draft(stored.get().code(), stored.get().language());
                drafts.put(problemId, draft);
            }
        }
        if (draft != null && draft.language() != null) {
            editorPanelController.setLanguage(draft.language());
        }
        editorPanelController.setCode(draft == null ? "" : draft.code());
    }

    private String username() {
        return state.session() == null ? null : state.session().username();
    }

    private long contestId() {
        return state.contest() == null ? -1 : state.contest().contestId();
    }

    /**
     * A restart is a new run: last run's code must not be sitting in the editor.
     *
     * The server already archives the previous run's submissions when a contest
     * starts, so carrying drafts across would contradict standings that begin
     * empty. A changed start instant is the signal — it is the only thing that
     * moves when a contest is restarted.
     */
    private void discardDraftsForNewRun() {
        drafts.clear();
        DraftStore.clearContest(username(), contestId());
        currentProblemId = -1;
        editorPanelController.setCode("");
    }

    // --- submit (FR-09, UIF-08/09) -------------------------------------------

    private void submit(Language language, String source) {
        ProblemSummaryDto problem = problemList.getSelectionModel().getSelectedItem();
        if (problem == null || ended) {
            return;
        }
        if (source == null || source.isBlank()) {
            toast(bundle("submit.empty"));
            return;
        }
        editorPanelController.setSubmitEnabled(false);
        // Submitted code is the last thing anyone can afford to lose, so it is
        // written out at the moment it is sent rather than at the next switch.
        DraftStore.save(username(), contestId(), problem.id(), language, source);
        async(() -> {
            try {
                SubmitAckDto ack = state.api().submit(
                        new SubmitRequest(problem.id(), language, source));
                Platform.runLater(() -> toast(bundle("submit.queued")
                        .replace("{n}", String.valueOf(ack.queuePosition()))));
            } catch (Exception e) {
                Platform.runLater(() -> toast(e.getMessage()));
            } finally {
                Platform.runLater(() -> {
                    if (!ended) {
                        editorPanelController.setSubmitEnabled(true);
                    }
                });
            }
        });
    }

    /**
     * Runs the current code against the student's own input. Never judged and
     * never stored, so it costs no attempt and no penalty.
     */
    private void runCustom(Language language, String source) {
        ProblemSummaryDto problem = problemList.getSelectionModel().getSelectedItem();
        if (problem == null) {
            editorPanelController.showRunResult("", "Pick a problem first");
            return;
        }
        String input = editorPanelController.getCustomInput();
        async(() -> {
            try {
                var r = state.api().runCustom(new com.arbitrator.common.dto.CustomRunRequest(
                        problem.id(), language, source, input));
                Platform.runLater(() -> {
                    if (!r.compiled()) {
                        editorPanelController.showRunResult(r.compilerOutput(),
                                "compile error");
                    } else {
                        String body = r.stdout();
                        if (!r.stderr().isBlank()) {
                            body += "\n--- stderr ---\n" + r.stderr();
                        }
                        editorPanelController.showRunResult(body,
                                r.timedOut() ? "timed out" : r.execTimeMs() + " ms");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        editorPanelController.showRunResult("", e.getMessage()));
            }
        });
    }

    // --- live channel --------------------------------------------------------

    private void connectLive() {
        async(() -> {
            try {
                state.api().connectVerdicts(event -> Platform.runLater(() -> {
                    // Clicking the banner jumps to Submissions and selects that
                    // run, so "what exactly did I send?" is one click away.
                    VerdictBanner.show(rootStack, event,
                            () -> showSubmission(event.submissionId()));
                    refreshProblems(false);              // badge update (§4.2)
                    submissionsPanelController.refresh(); // FR-16 history stays live
                }));
                long contestId = state.contest().contestId();
                // FR-07: an announcement pops immediately and lands in its tab.
                state.api().connectAnnouncements(contestId, a -> Platform.runLater(() -> {
                    AnnouncementPopup.show(rootStack, a, state.darkMode());
                    announcementsPanelController.refresh();
                }));
                // The board moved — re-read whichever view we are entitled to.
                state.api().connectClarifications(contestId,
                        () -> clarificationsPanelController.refresh());
                // FR-07 sibling: a material was uploaded or removed.
                state.api().connectMaterials(contestId,
                        () -> materialsPanelController.refresh());
                state.api().connectLeaderboard(contestId,
                        board -> leaderboardPanelController.update(board));
                // FR-06: the server owns the clock and tells us when it changes.
                state.api().connectContestState(contestId, this::onContestState);
                leaderboardPanelController.update(state.api().leaderboard());
                Platform.runLater(() -> setConnected(true));
            } catch (Exception e) {
                Platform.runLater(() -> setConnected(false));
            } finally {
                reconnecting = false;
            }
        });
    }

    /** Applies a pushed contest state: clock, pause, freeze and end (FR-06). */
    private void onContestState(com.arbitrator.common.dto.ContestStateDto contestState) {
        Platform.runLater(() -> {
            ContestState previous = state.contest() == null ? null : state.contest().state();
            long previousStart = state.contest() == null ? -1 : state.contest().startTimeMs();
            state.setContest(contestState);
            contestTitleLabel.setText(contestState.title());
            contestNumberLabel.setText("#" + contestState.contestId());

            // A different start instant means the contest was restarted, so
            // whatever is in the editor belongs to a run that no longer counts.
            //
            // RESTART_TOLERANCE_MS, not an exact !=: start_time is a plain
            // MySQL DATETIME (whole-second precision), but the very first
            // state this client saw came from the in-memory entity right
            // after start() set it via Instant.now() (millisecond precision).
            // ContestStatePublisher's 10s heartbeat re-reads the contest from
            // the DB, which has since truncated that value by up to 999ms —
            // a real field, a fake "restart": a contest that has been running
            // for hours doesn't drift back near its own start time again, so
            // a gap this small can only be that truncation, never a genuine
            // second run.
            long startDeltaMs = Math.abs(contestState.startTimeMs() - previousStart);
            if (previousStart > 0 && contestState.startTimeMs() > 0
                    && startDeltaMs > RESTART_TOLERANCE_MS) {
                discardDraftsForNewRun();
                toast("Contest restarted — the editor has been cleared for the new run");
            }

            boolean released = contestState.state().releasesProblems();
            boolean transitioned = previous != contestState.state();

            // Refresh on EVERY push, not only on a state change: uploading a
            // problem does not change the state, and relying on transitions
            // meant one missed push left the screen stale until sign-out.
            if (released) {
                refreshProblems(transitioned);
                // An instructor can edit a statement mid-contest, and the fix
                // is worthless if it only reaches people who re-select the
                // problem. loadProblem() no-ops when the text is unchanged, so
                // this costs one small request and never flickers the view.
                if (currentProblemId != -1) {
                    loadProblem(currentProblemId);
                }
            } else {
                problemList.getItems().clear();
                problemHeader.setText("");
                statementView.getEngine().loadContent(waitingPage());
            }
            if (transitioned) {
                toast(contestState.state() == ContestState.ACTIVE
                        ? bundle("contest.started") : bundle("contest.changed")
                                .replace("{s}", contestState.state().name()));
            }
            if (contestState.state() != ContestState.ENDED) {
                ended = false;
                if (timer != null && timer.getStatus() != Animation.Status.RUNNING) {
                    timer.play();
                }
            }
            tick();
        });
    }

    private void startConnectionWatchdog() {
        watchdog = new Timeline(new KeyFrame(Duration.seconds(4), e -> {
            boolean live = state.api().isLive();
            if (live) {
                missedTicks = 0;
                setConnected(true);
            } else {
                missedTicks++;
                // Reconnect attempts stay cheap/idempotent from the first
                // miss (connectLive() no-ops if already connecting/connected)
                // — only the visible banner waits for a second consecutive
                // miss, so a lone blip never flickers it.
                if (missedTicks >= 2) {
                    setConnected(false);
                }
                if (!reconnecting) {
                    reconnecting = true;
                    connectLive();
                }
            }
        }));
        watchdog.setCycleCount(Animation.INDEFINITE);
        watchdog.play();
    }

    private void setConnected(boolean live) {
        connLabel.setText(live ? "● live" : "● offline — reconnecting");
        connLabel.getStyleClass().setAll(live ? "conn-ok" : "conn-lost");
        // Only offered while offline: pointing a healthy client at a new server
        // mid-contest is not something to invite by leaving the button there.
        changeServerButton.setVisible(!live);
        changeServerButton.setManaged(!live);
    }

    /**
     * Repoint the client at a different server without restarting it.
     *
     * In a lab the usual reason nothing arrives is that the instructor's
     * machine has a different address than the one configured — a DHCP lease
     * moved, or the wrong number was read out. Making that fixable from the
     * screen already showing "offline" saves a restart and a re-login.
     */
    @FXML
    private void onChangeServer() {
        TextInputDialog dialog = new TextInputDialog(state.serverConfig().hostPort());
        dialog.setTitle("Change server address");
        dialog.setHeaderText("Address of the machine running Arbitrator");
        dialog.setContentText("host:port");
        dialog.initOwner(rootStack.getScene().getWindow());
        SceneRouter.styleDialog(dialog.getDialogPane());

        dialog.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(address -> {
            state.serverConfig().updateHostPort(address);
            // Drop the old socket explicitly. It may still claim to be
            // connected — a killed peer is not always noticed — and the
            // watchdog would then never dial the new address.
            state.api().dropConnection();
            setConnected(false);
            toast("Now trying " + state.serverConfig().hostPort());
            reconnecting = true;
            connectLive();
        });
    }

    /** Opens the Submissions tab and selects one run. */
    private void showSubmission(long submissionId) {
        tabs.getSelectionModel().select(2);
        submissionsPanelController.selectSubmission(submissionId);
    }

    /** Shown when the contest is running but holds no problems. */
    private String noProblemsPage() {
        return placeholderPage("No problems in this contest",
                "Your instructor has not added any yet.");
    }

    /** Shown in the statement pane while the contest sits in its lobby. */
    private String waitingPage() {
        return placeholderPage("Waiting for the contest to start",
                "Problems appear here the moment your instructor starts it.");
    }

    /** Empty-state page in the statement pane, themed like the statement itself. */
    private String placeholderPage(String heading, String detail) {
        boolean dark = state.darkMode();
        return """
                <html><head><meta charset="utf-8"><style>
                  body { font-family: -apple-system, "Segoe UI", sans-serif;
                         background: %s; color: %s; display: flex; height: 90vh;
                         align-items: center; justify-content: center; text-align: center; }
                  h2 { font-weight: 600; }
                  p { color: %s; }
                </style></head><body><div>
                  <h2>%s</h2>
                  <p>%s</p>
                </div></body></html>"""
                .formatted(dark ? "#1c222c" : "#ffffff",
                        dark ? "#e6eaf0" : "#1c2430",
                        dark ? "#9aa5b4" : "#6b7684",
                        heading, detail);
    }

    // --- timer (FR-06, UIF-05/06/07) -----------------------------------------

    private void startTimer() {
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        tick();
    }

    private void tick() {
        ContestState contestState = state.contest().state();

        if (contestState == ContestState.LOBBY) {
            long startRemaining = state.contest().startTimeMs() - state.serverNowMs();
            if (startRemaining > 0) {
                long h = startRemaining / 3_600_000;
                long m = (startRemaining / 60_000) % 60;
                long s = (startRemaining / 1000) % 60;
                timerLabel.setText(String.format("Starts in %02d:%02d:%02d", h, m, s));
            } else {
                timerLabel.setText(bundle("timer.waiting"));
                if (state.contest().startTimeMs() > 0 && !reconnecting) {
                    onRefresh();
                }
            }
            timerLabel.getStyleClass().setAll("timer", "timer-waiting");
            stopPulse();
            setSubmissionsAllowed(false);
            return;
        }
        if (contestState == ContestState.PAUSED) {
            timerLabel.setText(bundle("timer.paused"));
            timerLabel.getStyleClass().setAll("timer", "timer-paused");
            stopPulse();
            setSubmissionsAllowed(false);
            return;
        }
        if (contestState == ContestState.ENDED) {
            timerLabel.setText(bundle("timer.ended"));        // UIF-07
            timerLabel.getStyleClass().setAll("timer", "timer-ended");
            stopPulse();
            setSubmissionsAllowed(false);
            return;
        }

        long remaining = state.contest().endTimeMs() - state.serverNowMs();
        if (remaining <= 0) {
            timerLabel.setText(bundle("timer.ended"));
            timerLabel.getStyleClass().setAll("timer", "timer-ended");
            stopPulse();
            setSubmissionsAllowed(false);
            return;
        }

        setSubmissionsAllowed(true);
        long h = remaining / 3_600_000;
        long m = (remaining / 60_000) % 60;
        long s = (remaining / 1000) % 60;
        timerLabel.setText(String.format("%02d:%02d:%02d", h, m, s));

        if (remaining < 5 * 60_000) {                          // UIF-06
            if (!timerLabel.getStyleClass().contains("timer-critical")) {
                timerLabel.getStyleClass().setAll("timer", "timer-critical");
                pulse = new FadeTransition(Duration.millis(500), timerLabel);
                pulse.setFromValue(1.0);
                pulse.setToValue(0.5);
                pulse.setAutoReverse(true);
                pulse.setCycleCount(Animation.INDEFINITE);
                pulse.play();
            }
        } else {
            timerLabel.getStyleClass().setAll("timer");
            stopPulse();
        }
    }

    private void setSubmissionsAllowed(boolean allowed) {
        ended = !allowed;
        editorPanelController.setSubmitEnabled(allowed);
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.stop();
            pulse = null;
            timerLabel.setOpacity(1.0);
        }
    }

    // --- helpers --------------------------------------------------------------

    /** UIF-08: non-blocking toast, bottom-right, 3 s. */
    private void toast(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("toast");
        label.setWrapText(true);
        label.setMaxWidth(420);
        StackPane.setAlignment(label, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(label, new Insets(0, 20, 20, 0));
        rootStack.getChildren().add(label);
        PauseTransition stay = new PauseTransition(Duration.seconds(3));
        stay.setOnFinished(e -> rootStack.getChildren().remove(label));
        stay.play();
    }

    private static String bundle(String key) {
        return SceneRouter.bundle().getString(key);
    }

    private static void async(Runnable task) {
        Thread t = new Thread(task, "main-io");
        t.setDaemon(true);
        t.start();
    }

    /** Left-panel cell: code + title + status badge (§4.2). */
    private static final class ProblemCell extends ListCell<ProblemSummaryDto> {

        @Override
        protected void updateItem(ProblemSummaryDto item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label name = new Label(item.code() + ". " + item.title());
            name.getStyleClass().add("problem-list-name");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(6, name, spacer);
            row.setAlignment(Pos.CENTER_LEFT);

            if (item.solved()) {
                Label badge = new Label("✓");
                badge.getStyleClass().add("badge-solved");
                row.getChildren().add(badge);
            } else if (item.failedAttempts() > 0) {
                Label badge = new Label("-" + item.failedAttempts());
                badge.getStyleClass().add("badge-failed");
                row.getChildren().add(badge);
            }
            setGraphic(row);
        }
    }
}
