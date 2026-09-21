/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Prevents unreviewed host-process and shell execution from entering the backend. */
class CommandExecutionPolicyTest {

    private static final Set<String> REVIEWED_PROCESS_LAUNCHERS = Set.of(
            "com/arbitrator/server/config/AdminPanelLauncher.java",
            "com/arbitrator/server/judge/SandboxExecutor.java");

    private static final Pattern RUNTIME_EXEC = Pattern.compile(
            "\\bRuntime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\s*\\(");
    private static final Pattern SHELL_EXECUTION = Pattern.compile(
            "(?s)(?:new\\s+ProcessBuilder|List\\s*\\.\\s*of|Arrays\\s*\\.\\s*asList)\\s*\\(\\s*"
                    + "\"(?:/bin/(?:ba)?sh|(?:ba)?sh)\"\\s*,\\s*\"-(?:c|lc)\"");

    @Test
    void productionCodeHasNoShellOrRuntimeExecAndNoUnreviewedLaunchSites() throws IOException {
        Path sourceRoot = sourceRoot();
        Set<String> launchSites = new HashSet<>();

        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                String relative = sourceRoot.relativize(file).toString().replace('\\', '/');

                assertFalse(RUNTIME_EXEC.matcher(source).find(),
                        () -> "Runtime.exec is forbidden in production backend code: " + relative);
                assertFalse(SHELL_EXECUTION.matcher(source).find(),
                        () -> "Shell command execution is forbidden in production backend code: " + relative);
                assertFalse(source.contains("ProcessBuilder.startPipeline"),
                        () -> "Process pipelines require a separate security review: " + relative);

                if (source.contains("new ProcessBuilder(")) {
                    launchSites.add(relative);
                }
            }
        }

        assertEquals(REVIEWED_PROCESS_LAUNCHERS, launchSites,
                "Every host-process launcher must be explicitly reviewed and allowlisted");
    }

    private static Path sourceRoot() {
        Path moduleRoot = Path.of("src/main/java");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        Path reactorRoot = Path.of("arbitrator-server/src/main/java");
        if (Files.isDirectory(reactorRoot)) {
            return reactorRoot;
        }
        throw new IllegalStateException("Could not locate arbitrator-server production sources");
    }
}
