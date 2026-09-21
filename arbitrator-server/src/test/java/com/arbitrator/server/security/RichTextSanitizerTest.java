/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class RichTextSanitizerTest {

    @Test
    void activeContentAndRemoteResourcesAreRemoved() {
        String clean = RichTextSanitizer.sanitize("""
                <script>alert('script')</script>
                <style>body{background:url(https://attacker.example/x)}</style>
                <img src="https://attacker.example/pixel" onerror="alert(1)">
                <iframe srcdoc="<script>alert(2)</script>"></iframe>
                <object data="https://attacker.example/object"></object>
                <svg onload="alert(3)"><script>alert(4)</script></svg>
                <form action="https://attacker.example"><input name="password"></form>
                <p onclick="alert(5)" style="background:url(https://attacker.example/y)">Safe text</p>
                """);

        String lower = clean.toLowerCase(Locale.ROOT);
        assertTrue(clean.contains("<p>Safe text</p>"));
        assertFalse(lower.contains("script"));
        assertFalse(lower.contains("style="));
        assertFalse(lower.contains("onerror"));
        assertFalse(lower.contains("onclick"));
        assertFalse(lower.contains("iframe"));
        assertFalse(lower.contains("object"));
        assertFalse(lower.contains("svg"));
        assertFalse(lower.contains("form"));
        assertFalse(lower.contains("input"));
        assertFalse(lower.contains("attacker.example"));
    }

    @Test
    void dangerousLinksLoseNavigationWhileTheirTextRemains() {
        String clean = RichTextSanitizer.sanitize("""
                <a href="javascript:alert(1)">javascript link</a>
                <a href="https://attacker.example/collect">remote link</a>
                """);

        assertTrue(clean.contains("javascript link"));
        assertTrue(clean.contains("remote link"));
        assertFalse(clean.toLowerCase(Locale.ROOT).contains("href"));
        assertFalse(clean.contains("attacker.example"));
    }

    @Test
    void requiredProblemFormattingIsPreserved() {
        String clean = RichTextSanitizer.sanitize("""
                <h1 class="title">A. Sum</h1>
                <p>For n &le; 10<sup>9</sup>, print <strong>the answer</strong>.</p>
                <pre class="sample"><code>2 3</code></pre>
                <table><tr><th scope="col">Input</th><td colspan="2">value</td></tr></table>
                """);

        assertTrue(clean.contains("<h1 class=\"title\">A. Sum</h1>"));
        assertTrue(clean.contains("10<sup>9</sup>"));
        assertTrue(clean.contains("<strong>the answer</strong>"));
        assertTrue(clean.contains("<pre class=\"sample\"><code>2 3</code></pre>"));
        assertTrue(clean.contains("scope=\"col\""));
        assertTrue(clean.contains("colspan=\"2\""));
    }
}
