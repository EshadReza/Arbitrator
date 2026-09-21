/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LanguageCommandPolicyTest {

    @Test
    void validBuiltInLanguageConfigurationPasses() {
        LanguageCommandPolicy.validateAll(validProperties());
    }

    @Test
    void sourceFilenameCannotEscapeSubmissionWorkspace() {
        JudgeProperties properties = validProperties();
        properties.getLanguages().get("cpp17").setSourceFile("../main.cpp");

        assertThrows(IllegalStateException.class,
                () -> LanguageCommandPolicy.validateAll(properties));
    }

    @Test
    void shellAndCompilerPluginConfigurationAreRejected() {
        JudgeProperties shell = validProperties();
        shell.getLanguages().get("cpp17").setCompile("sh -c g++ {src}");
        assertThrows(IllegalStateException.class, () -> LanguageCommandPolicy.validateAll(shell));

        JudgeProperties plugin = validProperties();
        plugin.getLanguages().get("cpp17").setCompile("g++ -fplugin=/tmp/evil.so -o {exe} {src}");
        assertThrows(IllegalStateException.class, () -> LanguageCommandPolicy.validateAll(plugin));
    }

    @Test
    void unknownPlaceholderIsRejected() {
        JudgeProperties properties = validProperties();
        properties.getLanguages().get("java17").setRun("java -cp {classpath} Main");

        assertThrows(IllegalStateException.class,
                () -> LanguageCommandPolicy.validateAll(properties));
    }

    @Test
    void renderedPathsRemainSingleArgumentsEvenWithShellSyntax() {
        Path source = Path.of("/tmp/source name;touch injected");
        List<String> command = LanguageCommandPolicy.render("g++ -o {exe} {src}",
                source, Path.of("/tmp/program name"), Path.of("/tmp/work dir"));

        assertEquals(List.of("g++", "-o", "/tmp/program name",
                "/tmp/source name;touch injected"), command);
    }

    private static JudgeProperties validProperties() {
        JudgeProperties properties = new JudgeProperties();
        JudgeProperties.LanguageSpec cpp = spec("main.cpp",
                "g++ -O2 -std=c++17 -o {exe} {src}", "{exe}");
        JudgeProperties.LanguageSpec java = spec("Main.java",
                "javac -d {dir} {src}", "java -XX:+UseSerialGC -cp {dir} Main");
        JudgeProperties.LanguageSpec python = spec("main.py", "", "python3 {src}");
        properties.setLanguages(Map.of("cpp17", cpp, "java17", java, "python310", python));
        return properties;
    }

    private static JudgeProperties.LanguageSpec spec(String source, String compile, String run) {
        JudgeProperties.LanguageSpec spec = new JudgeProperties.LanguageSpec();
        spec.setSourceFile(source);
        spec.setCompile(compile);
        spec.setRun(run);
        return spec;
    }
}
