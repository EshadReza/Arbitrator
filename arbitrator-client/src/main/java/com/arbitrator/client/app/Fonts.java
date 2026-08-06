package com.arbitrator.client.app;

import java.io.InputStream;

import javafx.scene.text.Font;

/**
 * Registers the fonts arbitrator.css names — Inter for the UI, JetBrains Mono
 * for code — before any scene loads, instead of trusting the OS to have them.
 *
 * A lab of "basic Dell premade" Windows machines has neither font installed
 * by default (CLAUDE.md's target hardware); without this, every CSS
 * `-fx-font-family: "Inter", ...` and `"JetBrains Mono", ...` silently falls
 * through to whatever the fallback chain resolves to on that machine — a
 * different UI typeface per PC, and worse, a different *monospace* face in
 * the code editor. That one is not cosmetic: CodeAreaGutter pins the line
 * number column to a literal pixel width on the assumption that the font is
 * truly fixed-pitch, which only holds for the font actually shipped here —
 * see CodeAreaGutter's own class comment for the drift that motivated it.
 *
 * Only the weights and styles the stylesheet actually asks for (`bold`,
 * `normal`, `italic` — grep the file for `-fx-font-weight` / `-fx-font-style`
 * before adding more) are bundled; a face nothing selects is 300-400 KB spent
 * on nothing.
 */
public final class Fonts {

    private static final String[] FILES = {
            "/fonts/Inter-Regular.ttf",
            "/fonts/Inter-Bold.ttf",
            "/fonts/Inter-Italic.ttf",
            "/fonts/JetBrainsMono-Regular.ttf",
            "/fonts/JetBrainsMono-Bold.ttf",
            "/fonts/JetBrainsMono-Italic.ttf",
    };

    private Fonts() {
    }

    /** Idempotent and cheap enough to call unconditionally at startup. */
    public static void loadAll() {
        for (String path : FILES) {
            try (InputStream in = Fonts.class.getResourceAsStream(path)) {
                if (in == null || Font.loadFont(in, 0) == null) {
                    // A missing/corrupt font must not stop the app — it falls
                    // back to the OS font chain, which is exactly today's
                    // behaviour, not a new failure mode.
                    System.err.println("Fonts: could not load " + path);
                }
            } catch (Exception e) {
                System.err.println("Fonts: could not load " + path + ": " + e.getMessage());
            }
        }
    }
}
