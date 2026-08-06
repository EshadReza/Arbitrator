package com.arbitrator.client.app;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Turns a PDF statement into HTML the existing statement WebView can show.
 *
 * WebView has no PDF viewer — its WebKit build ships without one — so the pages
 * are rasterised with PDFBox and embedded as data URIs. That has a second,
 * bigger payoff: once the pages are images inside an ordinary HTML document,
 * the theme problem solves itself.
 *
 * A PDF is drawn on white, always. Rather than re-render it per theme, dark
 * mode applies a CSS {@code invert} filter to the page images: white becomes
 * near-black and black becomes near-white, while {@code hue-rotate(180deg)}
 * puts colours back where they started (invert alone turns a red diagram cyan).
 * The theme toggle already re-renders this wrapper, so switching themes flips
 * the filter with no re-fetch, no re-render, and no second copy in memory.
 */
public final class PdfStatementRenderer {

    /** Enough to read comfortably; high enough that formulae stay crisp. */
    private static final float RENDER_DPI = 132f;

    /** A statement is a handful of pages — a runaway file is not worth rendering. */
    private static final int MAX_PAGES = 30;

    private PdfStatementRenderer() {
    }

    /**
     * @param dark whether to invert the pages for the dark theme
     * @return a full HTML document ready for {@code WebEngine.loadContent}
     */
    public static String toHtml(byte[] pdfBytes, boolean dark) {
        List<String> pages = new ArrayList<>();
        int pageCount;
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            pageCount = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            for (int i = 0; i < pageCount; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, RENDER_DPI);
                pages.add(toDataUri(img));
            }
            if (doc.getNumberOfPages() > MAX_PAGES) {
                pages.add(null);         // marker: truncated
            }
        } catch (Exception e) {
            return errorPage(dark, e.getMessage());
        }

        String bg = dark ? "#1c222c" : "#ffffff";
        String fg = dark ? "#e6eaf0" : "#1c2430";
        // The filter is the whole theme story for a PDF: see the class comment.
        String filter = dark ? "filter: invert(1) hue-rotate(180deg);" : "";

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <html><head><meta charset="utf-8"><style>
                  html, body { background: %s !important; color: %s !important;
                               margin: 0; padding: 14px; }
                  .page { display: block; width: 100%%; max-width: 900px;
                          margin: 0 auto 16px auto; %s
                          box-shadow: 0 1px 6px rgba(0,0,0,.25); border-radius: 4px; }
                  .note { font-family: -apple-system, "Segoe UI", sans-serif;
                          font-size: 12px; color: %s; text-align: center; padding: 8px; }
                </style></head><body>
                """.formatted(bg, fg, filter, dark ? "#9aa5b4" : "#6b7684"));

        for (String page : pages) {
            if (page == null) {
                sb.append("<div class=\"note\">Only the first ").append(MAX_PAGES)
                        .append(" pages are shown.</div>");
            } else {
                sb.append("<img class=\"page\" src=\"").append(page).append("\"/>");
            }
        }
        return sb.append("</body></html>").toString();
    }

    private static String toDataUri(BufferedImage img) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // PNG, not JPEG: statements are text and line art, where JPEG's ringing
        // around glyph edges is exactly the artefact you would notice.
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String errorPage(boolean dark, String reason) {
        return """
                <html><head><meta charset="utf-8"><style>
                  body { font-family: -apple-system, "Segoe UI", sans-serif;
                         background: %s; color: %s; padding: 24px; }
                  code { color: %s; }
                </style></head><body>
                  <h2>The PDF statement could not be displayed</h2>
                  <p>Ask your instructor to re-upload it.</p>
                  <p><code>%s</code></p>
                </body></html>"""
                .formatted(dark ? "#1c222c" : "#ffffff",
                        dark ? "#e6eaf0" : "#1c2430",
                        dark ? "#9aa5b4" : "#6b7684",
                        reason == null ? "unknown error" : escape(reason));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
