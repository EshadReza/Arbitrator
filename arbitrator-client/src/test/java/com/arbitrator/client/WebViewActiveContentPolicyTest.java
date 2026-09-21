/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Prevents future rich-content WebViews from silently re-enabling scripts. */
class WebViewActiveContentPolicyTest {

    @Test
    void everyRichContentControllerDisablesJavaScript() throws IOException {
        assertDisablesJavaScript("MainController.java");
        assertDisablesJavaScript("AnnouncementsPanelController.java");
        assertDisablesJavaScript("AnnouncementPopup.java");
    }

    private static void assertDisablesJavaScript(String filename) throws IOException {
        Path source = Path.of("src/main/java/com/arbitrator/client/controller", filename);
        String java = Files.readString(source);
        assertTrue(java.contains("getEngine().setJavaScriptEnabled(false)"),
                filename + " must disable JavaScript before loading rich HTML");
    }
}
