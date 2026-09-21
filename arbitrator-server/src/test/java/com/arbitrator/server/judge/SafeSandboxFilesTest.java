/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeSandboxFilesTest {
    @TempDir Path root;

    @Test void rejectsFileAndDirectoryLinksAndLinkedCopySources() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path sentinel = outside.resolve("sentinel");
        Files.writeString(sentinel, "untouched");
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, sentinel);
        assertThrows(IOException.class, () -> SafeSandboxFiles.writeString(link, "attack"));
        assertThrows(IOException.class, () -> SafeSandboxFiles.copy(link, root.resolve("copy")));
        Path directoryLink = root.resolve("directory-link");
        Files.createSymbolicLink(directoryLink, outside);
        assertThrows(IOException.class, () -> SafeSandboxFiles.writeString(
                directoryLink.resolve("sentinel"), "attack"));
        assertEquals("untouched", Files.readString(sentinel));
    }

    @Test void replacementDoesNotTruncateHardLinkedTargetAndNormalCopiesWork() throws Exception {
        Path sentinel = root.resolve("sentinel");
        Files.writeString(sentinel, "untouched");
        Path hardLink = root.resolve("hard-link");
        Files.createLink(hardLink, sentinel);
        SafeSandboxFiles.writeString(hardLink, "new data");
        assertEquals("untouched", Files.readString(sentinel));
        assertEquals("new data", Files.readString(hardLink));
        Path copy = SafeSandboxFiles.copy(hardLink, root.resolve("copy"));
        assertEquals("new data", Files.readString(copy));
        assertThrows(IOException.class, () -> SafeSandboxFiles.copy(sentinel, copy));
    }
}
