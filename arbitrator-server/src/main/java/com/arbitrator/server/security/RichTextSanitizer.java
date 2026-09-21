/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * Strict policy for the small HTML subset used by statements and
 * announcements. Active content, navigation, remote resources, forms, inline
 * CSS and SVG are deliberately absent from the allowlist.
 */
public final class RichTextSanitizer {

    private static final Safelist ALLOWLIST = new Safelist()
            .addTags(
                    "p", "br", "hr", "div", "span", "blockquote",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "strong", "b", "em", "i", "u", "s", "del", "ins", "small",
                    "sub", "sup", "pre", "code", "kbd", "samp", "var",
                    "ul", "ol", "li", "dl", "dt", "dd",
                    "table", "caption", "colgroup", "col", "thead", "tbody", "tfoot",
                    "tr", "th", "td")
            .addAttributes(":all", "class", "title")
            .addAttributes("ol", "start", "type", "reversed")
            .addAttributes("li", "value")
            .addAttributes("col", "span")
            .addAttributes("th", "colspan", "rowspan", "scope")
            .addAttributes("td", "colspan", "rowspan");

    private static final Document.OutputSettings OUTPUT = new Document.OutputSettings()
            .prettyPrint(false);

    private RichTextSanitizer() {
    }

    /** Returns a safe HTML body fragment, or null when the input was null. */
    public static String sanitize(String html) {
        return html == null ? null : Jsoup.clean(html, "", ALLOWLIST, OUTPUT);
    }
}
