/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

import com.arbitrator.server.security.RichTextSanitizer;

/** Source-launched helper: browser fixtures use the production Java sanitizer. */
class RichTextFixture {
    public static void main(String[] args) {
        System.out.print(RichTextSanitizer.sanitize(args[0]));
    }
}
