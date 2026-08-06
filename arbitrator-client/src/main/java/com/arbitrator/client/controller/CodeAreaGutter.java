package com.arbitrator.client.controller;

import java.util.function.IntFunction;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * Line-number gutter shared by {@link EditorController} and
 * {@link SubmissionsPanelController}.
 *
 * A prior fix padded every line number to the same 3-character string and
 * forced a real (non-italic) monospace font via CSS, on the theory that equal
 * strings in an equal font measure equal. They still didn't: RichTextFX's
 * {@code ParagraphBox} asks each visible row's own graphic node for its
 * {@code prefWidth(-1)} independently — there is no single shared gutter
 * column, just N labels each measured on their own — so any sub-pixel
 * measurement variance between rows (font substitution, virtualized-node
 * recycling, whatever) still shows up as that one row's numbers and code
 * drifting left or right of its neighbours. Pinning min/pref/maxWidth to the
 * same literal pixel value removes the measurement from the equation
 * entirely: every row reports the identical width because it was told to,
 * not because JavaFX happened to measure it that way.
 */
final class CodeAreaGutter {

    /** Fits four right-aligned digits plus the .lineno CSS padding at 13px. */
    private static final double WIDTH_PX = 46;

    private CodeAreaGutter() {
    }

    static IntFunction<Node> factory(CodeArea codeArea) {
        IntFunction<Node> numbers =
                LineNumberFactory.get(codeArea, digits -> "%" + Math.max(3, digits) + "d");
        return line -> {
            Node node = numbers.apply(line);
            if (node instanceof Region region) {
                region.setMinWidth(WIDTH_PX);
                region.setPrefWidth(WIDTH_PX);
                region.setMaxWidth(WIDTH_PX);
            }
            return node;
        };
    }
}
