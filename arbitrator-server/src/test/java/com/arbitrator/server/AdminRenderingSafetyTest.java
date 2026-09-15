package com.arbitrator.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class AdminRenderingSafetyTest {

    private static final Pattern DYNAMIC_INLINE_HANDLER = Pattern.compile(
            "on(?:click|change|keydown)=\\\"[^\\\"]*\\$\\{", Pattern.DOTALL);

    @Test
    void generatedMarkupNeverInterpolatesValuesIntoInlineJavaScript() throws IOException {
        String html = adminHtml();

        assertFalse(DYNAMIC_INLINE_HANDLER.matcher(html).find(),
                "template values must be resolved by delegated data-action handlers");
        assertTrue(html.contains("document.addEventListener('click'"));
        assertTrue(html.contains("data-action=\"clone-contest\""));
        assertTrue(html.contains("data-action=\"open-notification-history\""));
    }

    @Test
    void studentControlledNamesAreHydratedWithTextContent() throws IOException {
        String html = adminHtml();

        assertTrue(html.contains("function hydrateNameCells(root, people)"));
        assertTrue(html.contains("cell.querySelector('.who b').textContent=display"));
        assertTrue(html.contains("el.textContent=grouped[Number(el.dataset.notifName)]"));
        assertFalse(html.contains("cloneContest(${c.id},'${esc(c.title)}')"));
        assertFalse(html.contains("openUserNotifHistory('${esc(n.username)}')"));
    }

    private static String adminHtml() throws IOException {
        try (var in = AdminRenderingSafetyTest.class.getResourceAsStream("/static/admin/index.html")) {
            if (in == null) throw new IOException("admin/index.html not found on classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
