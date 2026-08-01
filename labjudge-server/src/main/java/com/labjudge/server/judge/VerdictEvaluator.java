package com.labjudge.server.judge;

import org.springframework.stereotype.Component;

/**
 * Exact-match judging (FR-12): trailing whitespace on each line and trailing
 * blank lines are ignored; everything else must match exactly.
 * Float-tolerance (FR-13) and custom checkers (FR-14) are Sprint 3 chunks
 * S3-B1/S3-B2 and will slot in beside this class, not inside it.
 */
@Component
public class VerdictEvaluator {

    public boolean matches(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }

    /** FR-12 EARS: normalize trailing whitespace and newlines. */
    String normalize(String s) {
        if (s == null) {
            return "";
        }
        String[] lines = s.replace("\r\n", "\n").split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int lastNonBlank = -1;
        for (int i = 0; i < lines.length; i++) {
            lines[i] = stripTrailing(lines[i]);
            if (!lines[i].isEmpty()) {
                lastNonBlank = i;
            }
        }
        for (int i = 0; i <= lastNonBlank; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
