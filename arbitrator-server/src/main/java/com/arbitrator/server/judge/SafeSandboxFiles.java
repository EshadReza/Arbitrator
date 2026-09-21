/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Host staging after the container has stopped; never follows artifact links. */
final class SafeSandboxFiles {
    private SafeSandboxFiles() {}

    private static void requireDirectory(Path file) throws IOException {
        if (!Files.isDirectory(file.toAbsolutePath().getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe sandbox staging directory");
        }
    }

    static void writeString(Path file, String text) throws IOException {
        requireDirectory(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(file,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Unsafe sandbox staging artifact");
            // Replace rather than truncate: even an existing hard link must not
            // redirect a write into another inode. No container is running here.
            Files.delete(file);
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file,
                StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
            while (bytes.hasRemaining()) channel.write(bytes);
        }
    }

    static Path copy(Path source, Path target) throws IOException {
        requireDirectory(source);
        requireDirectory(target);
        if (!Files.readAttributes(source, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new IOException("Unsafe sandbox copy source");
        }
        // All targets are in fresh private staging directories. CREATE_NEW
        // rejects pre-existing targets; NOFOLLOW also protects source opens.
        try (SeekableByteChannel input = Files.newByteChannel(source,
                     StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             SeekableByteChannel output = Files.newByteChannel(target,
                     StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (input.read(buffer) != -1) {
                buffer.flip();
                while (buffer.hasRemaining()) output.write(buffer);
                buffer.clear();
            }
        }
        return target;
    }
}
