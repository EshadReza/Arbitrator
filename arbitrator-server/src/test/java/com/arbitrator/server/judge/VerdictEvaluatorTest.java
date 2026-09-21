/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure unit tests — run on any OS, no Spring context, no compilers needed. */
class VerdictEvaluatorTest {

    private final VerdictEvaluator evaluator = new VerdictEvaluator();

    @Test
    void exactMatchAccepts() {
        assertTrue(evaluator.matches("5\n", "5\n"));
    }

    @Test
    void trailingWhitespacePerLineIsIgnored() {          // FR-12
        assertTrue(evaluator.matches("5\n10\n", "5   \n10\t\n"));
    }

    @Test
    void trailingNewlinesAreIgnored() {                  // FR-12
        assertTrue(evaluator.matches("5", "5\n\n\n"));
    }

    @Test
    void windowsLineEndingsAreNormalized() {
        assertTrue(evaluator.matches("a\nb\n", "a\r\nb\r\n"));
    }

    @Test
    void leadingWhitespaceIsSignificant() {
        assertFalse(evaluator.matches("5", " 5"));
    }

    @Test
    void differentTokensAreWrong() {
        assertFalse(evaluator.matches("5\n", "6\n"));
    }

    @Test
    void internalBlankLinesAreSignificant() {
        assertFalse(evaluator.matches("a\nb", "a\n\nb"));
    }

    @Test
    void normalizeCollapsesToCanonicalForm() {
        assertEquals("x\ny", evaluator.normalize("x \r\ny\t\n\n"));
    }
}
