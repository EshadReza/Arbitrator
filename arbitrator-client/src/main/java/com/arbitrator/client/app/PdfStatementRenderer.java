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
 * are rasterised with PDFBox and embedded as data URIs.
 *
 * A PDF is drawn on white, always. Dark mode used to fix that with CSS
 * ({@code filter: invert(1)}, later with {@code mix-blend-mode: screen} added
 * on top to stop the inverted "black" from reading as a flat void against the
 * app's real dark surface) — but this WebView's WebKit build does not apply
 * {@code mix-blend-mode} at all, so the page's background stayed pure
 * {@code #000000} regardless. Recolouring is done here instead, per pixel, on
 * the actual rasterised bytes before they are ever handed to WebView: no CSS
 * feature to depend on, and no guessing whether one is supported. Every pixel
 * is recomputed by simple luminance (0 = ink, 1 = paper) and linearly
 * interpolated between the app's own dark-theme colours — {@code -c-text}
 * (arbitrator.css) for ink, {@code -c-surface} for paper — so paper becomes
 * exactly the window's background, not an approximation of it. The theme
 * toggle already re-renders this wrapper, so switching themes re-renders the
 * pages with no re-fetch.
 */
public final class PdfStatementRenderer {

    /** Enough to read comfortably; high enough that formulae stay crisp. */
    private static final float RENDER_DPI = 132f;

    /** A statement is a handful of pages — a runaway file is not worth rendering. */
    private static final int MAX_PAGES = 30;

    /** arbitrator.css dark palette: -c-surface (paper) and -c-text (ink). */
    private static final int DARK_BG = 0x1c222c;
    private static final int DARK_FG = 0xe6eaf0;

    private PdfStatementRenderer() {
    }

    /**
     * @param dark whether to recolour the pages for the dark theme
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
                pages.add(toDataUri(dark ? recolourForDarkTheme(img) : img));
            }
            if (doc.getNumberOfPages() > MAX_PAGES) {
                pages.add(null);         // marker: truncated
            }
        } catch (Exception e) {
            return errorPage(dark, e.getMessage());
        }

        String bg = dark ? "#1c222c" : "#ffffff";
        String fg = dark ? "#e6eaf0" : "#1c2430";

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <html><head><meta charset="utf-8"><style>
                  html, body { background: %s !important; color: %s !important;
                               margin: 0; padding: 14px; }
                  .page { display: block; width: 100%%; max-width: 900px;
                          margin: 0 auto 16px auto;
                          box-shadow: 0 1px 6px rgba(0,0,0,.25); border-radius: 4px; }
                  .note { font-family: -apple-system, "Segoe UI", sans-serif;
                          font-size: 12px; color: %s; text-align: center; padding: 8px; }
                </style></head><body>
                """.formatted(bg, fg, dark ? "#9aa5b4" : "#6b7684"));

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

    /**
     * Recomputes every pixel from its own perceived luminance, interpolated
     * between {@link #DARK_FG} (luminance 0, i.e. ink) and {@link #DARK_BG}
     * (luminance 1, i.e. paper). Bulk {@code getRGB}/{@code setRGB} arrays
     * rather than per-pixel calls — a 132 DPI page is over a million pixels,
     * and per-pixel method calls make that visibly slow.
     */
    private static BufferedImage recolourForDarkTheme(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);

        int bgR = (DARK_BG >> 16) & 0xFF, bgG = (DARK_BG >> 8) & 0xFF, bgB = DARK_BG & 0xFF;
        int fgR = (DARK_FG >> 16) & 0xFF, fgG = (DARK_FG >> 8) & 0xFF, fgB = DARK_FG & 0xFF;

        for (int i = 0; i < px.length; i++) {
            int argb = px[i];
            int a = (argb >>> 24) & 0xFF;
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;   // 0 = ink, 1 = paper
            int nr = fgR + (int) Math.round((bgR - fgR) * lum);
            int ng = fgG + (int) Math.round((bgG - fgG) * lum);
            int nb = fgB + (int) Math.round((bgB - fgB) * lum);
            px[i] = (a << 24) | (nr << 16) | (ng << 8) | nb;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
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
