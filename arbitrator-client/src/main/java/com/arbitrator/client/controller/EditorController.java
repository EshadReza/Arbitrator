package com.arbitrator.client.controller;

import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

import com.arbitrator.client.app.SceneRouter;
import com.arbitrator.common.enums.Language;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Right panel (SRS §4.2): RichTextFX CodeArea with line numbers,
 * keyword highlighting for the three languages, language selector,
 * submit button and a char/line status bar.
 */
public class EditorController {

    // --- syntax highlighting: one combined named-group pattern per language,
    // in place of the single un-grouped keyword regex this replaced. Group
    // order in the alternation is significant — first match wins at a given
    // position, so KEYWORD/TYPE are tried before the catch-all FUNCTION so
    // "if(" or "int(" don't get misclassified as a function call.
    private static final String COMMENT_LINE_OR_BLOCK = "//[^\\n]*|/\\*[\\s\\S]*?\\*/";
    private static final String COMMENT_HASH = "#[^\\n]*";
    /** Double- or single-quoted, with backslash escapes — covers strings and, for cpp/java, char literals too. */
    private static final String STRING_LITERAL =
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'";
    private static final String NUMBER_LITERAL = "\\b\\d+(?:\\.\\d+)?[fFlLuU]*\\b";
    /** An identifier immediately followed by '(' — a function/method call or declaration. */
    private static final String FUNCTION_CALL = "\\b[A-Za-z_]\\w*(?=\\()";

    private static final Pattern CPP_PATTERN = highlightPattern(COMMENT_LINE_OR_BLOCK,
            Set.of("if", "else", "for", "while", "do", "switch", "case", "break",
                    "continue", "return", "struct", "class", "public", "private",
                    "const", "static", "using", "namespace", "include", "true", "false"),
            Set.of("int", "long", "double", "float", "char", "bool", "void", "auto",
                    "string", "size_t", "unsigned", "short"));

    private static final Pattern JAVA_PATTERN = highlightPattern(COMMENT_LINE_OR_BLOCK,
            Set.of("if", "else", "for", "while", "do", "switch", "case", "break",
                    "continue", "return", "class", "public", "private", "protected",
                    "static", "final", "new", "import", "package", "true", "false", "null"),
            Set.of("int", "long", "double", "float", "char", "boolean", "void",
                    "String", "Integer", "Long", "Double", "Boolean", "Object",
                    "List", "Map", "Set"));

    private static final Pattern PY_PATTERN = highlightPattern(COMMENT_HASH,
            Set.of("def", "return", "if", "elif", "else", "for", "while", "break",
                    "continue", "import", "from", "as", "class", "in", "not", "and",
                    "or", "True", "False", "None", "lambda"),
            // print/input/range etc. are left unclassified here on purpose —
            // FUNCTION_CALL already colors them like any other call, which is
            // how VSCode treats Python builtins too (not as keywords).
            Set.of("int", "float", "str", "bool", "list", "dict", "tuple", "set"));
    private static final Map<Language, String> TEMPLATES = Map.of(
            Language.CPP17, """
                    #include <iostream>
                    using namespace std;

                    int main() {

                        return 0;
                    }
                    """,
            Language.JAVA17, """
                    public class Main {
                        public static void main(String[] args) {

                        }
                    }
                    """,
            Language.PYTHON310, """
                    def main():
                        pass


                    if __name__ == "__main__":
                        main()
                    """
    );
    @FXML private VBox editorBox;
    @FXML private VBox codeHost;
    @FXML private TitledPane customPane;
    @FXML private ComboBox<Language> languageBox;
    @FXML private Button uploadButton;
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
        codeArea.setParagraphGraphicFactory(CodeAreaGutter.factory(codeArea));
        codeArea.getStyleClass().add("code-area");
        VBox.setVgrow(codeArea, javafx.scene.layout.Priority.ALWAYS);
        codeHost.getChildren().add(codeArea);

        // A collapsed TitledPane must only claim its header row, or the outer
        // SplitPane's last-dragged divider position leaves it stretched with
        // dead space below the header once the student collapses it again.
        customPane.setMaxHeight(Region.USE_PREF_SIZE);
        customPane.expandedProperty().addListener((obs, was, expanded) ->
                customPane.setMaxHeight(expanded ? Double.MAX_VALUE : Region.USE_PREF_SIZE));

        languageBox.setItems(FXCollections.observableArrayList(Language.values()));
        languageBox.getSelectionModel().select(Language.CPP17);
        codeArea.replaceText(TEMPLATES.getOrDefault(Language.CPP17, ""));   // <-- new


        codeArea.textProperty().addListener((obs, old, text) -> {
            updateCount(text);
            // Deferred, and re-reading the live text at apply time rather than
            // closing over this listener's `text` snapshot: applying
            // StyleSpans synchronously here re-enters the CodeArea from
            // inside its own change notification — e.g. the auto-close-pair
            // KEY_TYPED filter is still mid-insertText() when this fires — and
            // that reentrancy was corrupting the buffer a few keystrokes in
            // (typed characters landing on, and eating into, the line below).
            // Applying on the next pulse against whatever the text is BY THEN
            // keeps the spans length always in sync with the document, even
            // if more keystrokes land before this runs.
            Platform.runLater(() -> codeArea.setStyleSpans(0, highlight(codeArea.getText())));
        });
        languageBox.valueProperty().addListener((obs, old, lang) -> {
            // Load the new language's boilerplate only when nothing has been
            // written yet. Replacing unconditionally threw away a half-finished
            // solution the instant someone brushed the language selector, with
            // no way back — and MainController keeps per-problem drafts
            // precisely so that work is never lost mid-contest.
            if (lang != null && isUntouched(old)) {
                codeArea.replaceText(TEMPLATES.getOrDefault(lang, ""));
                codeArea.moveTo(0);
            }
            codeArea.setStyleSpans(0, highlight(codeArea.getText()));
        });

        installEditingBehaviour();
        updateCount("");
    }

    /** True when the buffer is empty or still exactly {@code language}'s boilerplate. */
    private boolean isUntouched(Language language) {
        String text = codeArea.getText();
        return text.isBlank() || text.equals(TEMPLATES.getOrDefault(language, ""));
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
                handleTab(e.isShiftDown());
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

    /**
     * Enter keeps the current indentation, and adds one level after '{'.
     *
     * If the caret sits directly between a freshly auto-closed pair (typing
     * '{' inserts "{}" with the caret in between — see the KEY_TYPED filter
     * below), a plain single-line insert would leave the closing bracket on
     * the same line as the new cursor position. Splitting into three lines
     * instead — opener, an indented blank line, the closer dedented back to
     * the opener's level — is what every mainstream IDE does here.
     */
    private void autoIndentNewline() {
        int caret = codeArea.getCaretPosition();
        int paragraph = codeArea.getCurrentParagraph();
        String line = codeArea.getParagraph(paragraph).getText();
        String text = codeArea.getText();

        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        String indent = " ".repeat(spaces);

        char before = caret > 0 ? text.charAt(caret - 1) : 0;
        char after = caret < text.length() ? text.charAt(caret) : 0;
        String closer = PAIRS.get(String.valueOf(before));
        if (closer != null && !closer.isEmpty() && closer.charAt(0) == after
                && "([{".indexOf(before) >= 0) {
            String inner = indent + INDENT;
            codeArea.insertText(caret, "\n" + inner + "\n" + indent);
            codeArea.moveTo(caret + 1 + inner.length());
            return;
        }

        String trimmed = line.strip();
        if (trimmed.endsWith("{") || trimmed.endsWith(":")) {
            indent += INDENT;
        }
        codeArea.insertText(caret, "\n" + indent);
    }

    /**
     * Tab with a selection indents every selected line (Shift+Tab dedents);
     * without one it's the plain 4-space insert as before (Shift+Tab dedents
     * the current line instead). Previously Tab's whole body was
     * {@code replaceSelection(INDENT)}, which — with text selected — replaced
     * the entire selection with four spaces instead of shifting it, and
     * Shift+Tab didn't dedent at all.
     */
    private void handleTab(boolean shiftDown) {
        if (codeArea.getSelectedText().isEmpty()) {
            if (shiftDown) {
                dedentLine(codeArea.getCurrentParagraph());
            } else {
                codeArea.replaceSelection(INDENT);
            }
            return;
        }
        IndexRange sel = codeArea.getSelection();
        int startPar = codeArea.offsetToPosition(sel.getStart(), Bias.Forward).getMajor();
        int endPar = codeArea.offsetToPosition(sel.getEnd(), Bias.Backward).getMajor();
        for (int p = startPar; p <= endPar; p++) {
            if (shiftDown) {
                dedentLine(p);
            } else {
                codeArea.insertText(p, 0, INDENT);
            }
        }
        // Re-select the whole block at its new width — Tab/Shift+Tab on a
        // multi-line selection is meant to be repeatable without reselecting.
        codeArea.selectRange(startPar, 0, endPar, codeArea.getParagraphLength(endPar));
    }

    /** Removes up to one INDENT's worth of leading spaces from paragraph {@code p}, if any. */
    private void dedentLine(int p) {
        String text = codeArea.getParagraph(p).getText();
        int remove = 0;
        while (remove < INDENT.length() && remove < text.length() && text.charAt(remove) == ' ') {
            remove++;
        }
        if (remove > 0) {
            codeArea.deleteText(p, 0, p, remove);
        }
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
    /** Restores a draft. Caret goes to the end so typing continues naturally. */
    public void setCode(String code) {
        if (code == null || code.isBlank()) {
            // No draft yet for this problem — start from boilerplate instead
            // of a blank buffer, same as picking the language fresh.
            code = TEMPLATES.getOrDefault(getLanguage(), "");
        }
        codeArea.replaceText(code);
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

    /** Maximum allowed file size for solution uploads (1 MB). */
    private static final long MAX_FILE_SIZE_BYTES = 1 * 1024 * 1024;

    public static List<String> getAllowedExtensions(Language language) {
        if (language == null) {
            return List.of();
        }
        return switch (language) {
            case CPP17 -> List.of(".cpp", ".cc", ".cxx", ".c++", ".hpp", ".h");
            case JAVA17 -> List.of(".java");
            case PYTHON310 -> List.of(".py");
        };
    }

    @FXML
    private void onUploadFile() {
        Language lang = languageBox.getValue();
        if (lang == null) {
            showErrorAlert(
                    bundleOrDefault("editor.uploadErrorTitle", "Upload Error"),
                    bundleOrDefault("editor.selectLanguageFirst", "Please select a programming language first."));
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(bundleOrDefault("editor.uploadTitle", "Upload Solution File"));

        List<String> allowedExts = getAllowedExtensions(lang);
        List<String> globPatterns = allowedExts.stream().map(ext -> "*" + ext).toList();
        String description = lang.display() + " Files (" + String.join(", ", globPatterns) + ")";
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, globPatterns));

        Window window = editorBox != null && editorBox.getScene() != null ? editorBox.getScene().getWindow() : null;
        File file = chooser.showOpenDialog(window);

        if (file == null) {
            return;
        }

        validateAndLoadFile(file, lang);
    }

    public void validateAndLoadFile(File file, Language lang) {
        try {
            // Safety rule 1: File existence and non-directory check
            if (file == null || !file.exists() || !file.isFile() || !file.canRead()) {
                showErrorAlert(
                        bundleOrDefault("editor.uploadErrorTitle", "Upload Error"),
                        "The selected file does not exist, is a directory, or cannot be read.");
                return;
            }

            // Safety rule 2: Strict extension validation according to selected language
            String fileName = file.getName().toLowerCase(Locale.ROOT);
            List<String> allowedExts = getAllowedExtensions(lang);
            boolean validExt = allowedExts.stream().anyMatch(fileName::endsWith);

            if (!validExt) {
                showErrorAlert(
                        bundleOrDefault("editor.invalidExtensionTitle", "Invalid File Type"),
                        "The file '" + file.getName() + "' is not a valid file type for " + lang.display() + ".\n"
                                + "Allowed extensions: " + String.join(", ", allowedExts));
                return;
            }

            // Safety rule 3: File size cap to prevent OOM/UI freezes
            if (file.length() > MAX_FILE_SIZE_BYTES) {
                showErrorAlert(
                        bundleOrDefault("editor.fileTooLargeTitle", "File Too Large"),
                        "The selected file (" + (file.length() / 1024) + " KB) exceeds the maximum allowed limit of 1 MB.");
                return;
            }

            // Safety rule 4: Binary file / null-byte safety check
            byte[] bytes = Files.readAllBytes(file.toPath());
            for (byte b : bytes) {
                if (b == 0) {
                    showErrorAlert(
                            bundleOrDefault("editor.binaryFileTitle", "Invalid File Content"),
                            "The file contains binary data (null bytes). Only plain text source code files are permitted.");
                    return;
                }
            }

            // Safety rule 5: UTF-8 character decoding validation
            String content;
            try {
                var decoder = StandardCharsets.UTF_8.newDecoder();
                decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
                decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                content = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                showErrorAlert(
                        bundleOrDefault("editor.encodingErrorTitle", "Encoding Error"),
                        "The file is not valid UTF-8 text. Please upload plain text solution files encoded in UTF-8.");
                return;
            }

            // Normalize line endings
            content = content.replace("\r\n", "\n").replace("\r", "\n");

            // Apply to editor safely
            codeArea.replaceText(content);
            codeArea.moveTo(0);
            updateCount(content);
            codeArea.setStyleSpans(0, highlight(content));

        } catch (SecurityException e) {
            showErrorAlert(
                    bundleOrDefault("editor.uploadErrorTitle", "Upload Error"),
                    "Security restriction prevented reading the file: " + e.getMessage());
        } catch (IOException e) {
            showErrorAlert(
                    bundleOrDefault("editor.uploadErrorTitle", "Upload Error"),
                    "Failed to read file: " + e.getMessage());
        } catch (Exception e) {
            showErrorAlert(
                    bundleOrDefault("editor.uploadErrorTitle", "Upload Error"),
                    "An unexpected error occurred: " + e.getMessage());
        }
    }

    private void showErrorAlert(String title, String message) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        SceneRouter.styleDialog(alert.getDialogPane());
        alert.showAndWait();
    }

    private void updateCount(String text) {
        int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
        countLabel.setText(lines + " lines, " + text.length() + " chars");
    }

    private StyleSpans<java.util.Collection<String>> highlight(String text) {
        Pattern pattern = switch (languageBox.getValue() == null
                ? Language.CPP17 : languageBox.getValue()) {
            case CPP17 -> CPP_PATTERN;
            case JAVA17 -> JAVA_PATTERN;
            case PYTHON310 -> PY_PATTERN;
        };
        StyleSpansBuilder<java.util.Collection<String>> spans = new StyleSpansBuilder<>();
        Matcher m = pattern.matcher(text);
        int last = 0;
        while (m.find()) {
            spans.add(Set.of(), m.start() - last);
            spans.add(Set.of(styleClassFor(m)), m.end() - m.start());
            last = m.end();
        }
        spans.add(Set.of(), text.length() - last);
        return spans.create();
    }

    /** Which named group matched determines the CSS class — see the field-block comment above. */
    private static String styleClassFor(Matcher m) {
        if (m.group("COMMENT") != null) {
            return "comment";
        }
        if (m.group("STRING") != null) {
            return "string";
        }
        if (m.group("NUMBER") != null) {
            return "number";
        }
        if (m.group("KEYWORD") != null) {
            return "keyword";
        }
        if (m.group("TYPE") != null) {
            return "type";
        }
        return "function";
    }

    private static Pattern highlightPattern(String commentRegex, Set<String> keywords, Set<String> types) {
        return Pattern.compile(
                "(?<COMMENT>" + commentRegex + ")"
                        + "|(?<STRING>" + STRING_LITERAL + ")"
                        + "|(?<NUMBER>" + NUMBER_LITERAL + ")"
                        + "|(?<KEYWORD>" + wordAlternation(keywords) + ")"
                        + "|(?<TYPE>" + wordAlternation(types) + ")"
                        + "|(?<FUNCTION>" + FUNCTION_CALL + ")");
    }

    private static String wordAlternation(Set<String> words) {
        return "\\b(?:" + String.join("|", words) + ")\\b";
    }
}
