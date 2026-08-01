package com.labjudge.client.controller;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import com.labjudge.common.enums.Language;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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
    @FXML private Label countLabel;

    private CodeArea codeArea;
    private BiConsumer<Language, String> submitHandler;

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

        updateCount("");
    }

    @FXML
    private void onSubmit() {
        if (submitHandler != null) {
            submitHandler.accept(languageBox.getValue(), codeArea.getText());
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
