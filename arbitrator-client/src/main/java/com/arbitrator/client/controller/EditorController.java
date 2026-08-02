package com.arbitrator.client.controller;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import com.arbitrator.common.enums.Language;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;

/**
 * Right panel (SRS §4.2): RichTextFX CodeArea with line numbers,
 * keyword highlighting for the three languages, language selector,
 * submit button and a char/line status bar.
 */
public class EditorController {

    private static final Pattern CPP_KEYWORDS = keywords(Set.of(
            "int", "long", "double", "float", "char", "bool", "void", "auto",
            "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "return", "struct", "class", "public", "private",
            "const", "static", "using", "namespace", "include", "true", "false"));

    private static final Pattern JAVA_KEYWORDS = keywords(Set.of(
            "int", "long", "double", "float", "char", "boolean", "void",
            "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "return", "class", "public", "private", "protected",
            "static", "final", "new", "import", "package", "true", "false", "null"));

    private static final Pattern PY_KEYWORDS = keywords(Set.of(
            "def", "return", "if", "elif", "else", "for", "while", "break",
            "continue", "import", "from", "as", "class", "print", "input",
            "in", "not", "and", "or", "True", "False", "None", "lambda", "range"));

    @FXML private VBox editorBox;
    @FXML private ComboBox<Language> languageBox;
    @FXML private Button submitButton;
    @FXML private Button fullscreenButton;
    @FXML private Label countLabel;
    @FXML private TextArea customInput;
    @FXML private TextArea customOutput;
    @FXML private Button runButton;
    @FXML private Label runStatus;

    /** Four spaces, never a tab: a literal tab renders 8 wide and misaligns. */
    private static final String INDENT = "    ";
    private static final int DEFAULT_FONT_SIZE = 13;
    private static final int MIN_FONT_SIZE = 9;
    private static final int MAX_FONT_SIZE = 28;

    /** Characters auto-closed as a pair. */
    private static final Map<String, String> PAIRS = Map.of(
            "(", ")", "[", "]", "{", "}", "\"", "\"", "'", "'");

    private CodeArea codeArea;
    private int fontSize = DEFAULT_FONT_SIZE;
    private BiConsumer<Language, String> submitHandler;
    private Runnable onFullscreen;
    private BiConsumer<Language, String> runHandler;

    @FXML
    private void initialize() {
        codeArea = new CodeArea();
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.getStyleClass().add("code-area");
        VBox.setVgrow(codeArea, javafx.scene.layout.Priority.ALWAYS);
        editorBox.getChildren().add(1, codeArea);   // between toolbar and status bar

        languageBox.setItems(FXCollections.observableArrayList(Language.values()));
        languageBox.getSelectionModel().select(Language.CPP17);

        codeArea.textProperty().addListener((obs, old, text) -> {
            updateCount(text);
            codeArea.setStyleSpans(0, highlight(text));
        });
        languageBox.valueProperty().addListener((obs, old, lang) ->
                codeArea.setStyleSpans(0, highlight(codeArea.getText())));

        installEditingBehaviour();
        updateCount("");
    }

    /**
     * Editor ergonomics people expect from any IDE: a 4-space Tab, indentation
     * carried onto the next line (and one level deeper after an opening brace),
     * automatic bracket/quote closing, and Ctrl +/- / Ctrl+scroll zoom.
     *
     * CodeArea does none of this by itself — Tab inserts a literal tab, which
     * renders 8 wide, and Enter returns to column zero.
     */
    private void installEditingBehaviour() {
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB && !e.isControlDown()) {
                codeArea.replaceSelection(INDENT);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                autoIndentNewline();
                e.consume();
            } else if (e.isShortcutDown()
                    && (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.PLUS)) {
                zoom(+1);
                e.consume();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.MINUS) {
                zoom(-1);
                e.consume();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.DIGIT0) {
                fontSize = DEFAULT_FONT_SIZE;
                applyFontSize();
                e.consume();
            }
        });

        // Auto-close brackets and quotes, leaving the caret between the pair.
        codeArea.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            String closing = PAIRS.get(e.getCharacter());
            if (closing != null && codeArea.getSelectedText().isEmpty()) {
                int caret = codeArea.getCaretPosition();
                codeArea.insertText(caret, e.getCharacter() + closing);
                codeArea.moveTo(caret + 1);
                e.consume();
            }
        });

        codeArea.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown()) {
                zoom(e.getDeltaY() > 0 ? +1 : -1);
                e.consume();
            }
        });

        applyFontSize();
    }

    /** Enter keeps the current indentation, and adds one level after '{'. */
    private void autoIndentNewline() {
        int caret = codeArea.getCaretPosition();
        int paragraph = codeArea.getCurrentParagraph();
        String line = codeArea.getParagraph(paragraph).getText();

        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        String indent = " ".repeat(spaces);
        String trimmed = line.strip();
        if (trimmed.endsWith("{") || trimmed.endsWith(":")) {
            indent += INDENT;
        }
        codeArea.insertText(caret, "\n" + indent);
    }

    private void zoom(int steps) {
        // Math.clamp is Java 21+; this module targets 17.
        fontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, fontSize + steps));
        applyFontSize();
    }

    private void applyFontSize() {
        codeArea.setStyle("-fx-font-size: " + fontSize + "px;");
    }

    @FXML
    private void onSubmit() {
        if (submitHandler != null) {
            submitHandler.accept(languageBox.getValue(), codeArea.getText());
        }
    }

    /** The panel's fullscreen toggle; MainController owns the layout logic. */
    public Button fullscreenButton() {
        return fullscreenButton;
    }

    @FXML
    private void onToggleFullscreen() {
        if (onFullscreen != null) {
            onFullscreen.run();
        }
    }

    public void setFullscreenHandler(Runnable handler) {
        this.onFullscreen = handler;
    }

    /** MainController performs the call; this panel only owns the widgets. */
    public void setRunHandler(BiConsumer<Language, String> handler) {
        this.runHandler = handler;
    }

    @FXML
    private void onRunCustom() {
        if (runHandler != null) {
            runButton.setDisable(true);
            runStatus.setText(bundleOrDefault("editor.running", "running…"));
            customOutput.clear();
            runHandler.accept(languageBox.getValue(), codeArea.getText());
        }
    }

    public String getCustomInput() {
        return customInput.getText();
    }

    /** Shows the result of a custom run and re-enables the button. */
    public void showRunResult(String output, String status) {
        customOutput.setText(output);
        runStatus.setText(status);
        runButton.setDisable(false);
    }

    private static String bundleOrDefault(String key, String fallback) {
        try {
            return com.arbitrator.client.app.SceneRouter.bundle().getString(key);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Current editor contents — used to stash a per-problem draft. */
    public String getCode() {
        return codeArea.getText();
    }

    /** Restores a draft. Caret goes to the end so typing continues naturally. */
    public void setCode(String code) {
        codeArea.replaceText(code == null ? "" : code);
        codeArea.moveTo(codeArea.getLength());
    }

    public Language getLanguage() {
        return languageBox.getValue();
    }

    public void setLanguage(Language language) {
        if (language != null) {
            languageBox.getSelectionModel().select(language);
        }
    }

    /** Keyboard path for NFR-U04: Ctrl+Enter fires the same submit action. */
    public void fireSubmit() {
        if (!submitButton.isDisabled()) {
            onSubmit();
        }
    }

    /** MainController owns the actual network call. */
    public void setSubmitHandler(BiConsumer<Language, String> handler) {
        this.submitHandler = handler;
    }

    /** Disabled before start / after end (UIF-07) and while a submit is in flight. */
    public void setSubmitEnabled(boolean enabled) {
        submitButton.setDisable(!enabled);
    }

    public void requestFocus() {
        codeArea.requestFocus();
    }

    // ------------------------------------------------------------------

    private void updateCount(String text) {
        int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
        countLabel.setText(lines + " lines, " + text.length() + " chars");
    }

    private StyleSpans<java.util.Collection<String>> highlight(String text) {
        Pattern pattern = switch (languageBox.getValue() == null
                ? Language.CPP17 : languageBox.getValue()) {
            case CPP17 -> CPP_KEYWORDS;
            case JAVA17 -> JAVA_KEYWORDS;
            case PYTHON310 -> PY_KEYWORDS;
        };
        StyleSpansBuilder<java.util.Collection<String>> spans = new StyleSpansBuilder<>();
        Matcher m = pattern.matcher(text);
        int last = 0;
        while (m.find()) {
            spans.add(Set.of(), m.start() - last);
            spans.add(Set.of("keyword"), m.end() - m.start());
            last = m.end();
        }
        spans.add(Set.of(), text.length() - last);
        return spans.create();
    }

    private static Pattern keywords(Set<String> words) {
        return Pattern.compile("\\b(" + String.join("|", words) + ")\\b");
    }
}
