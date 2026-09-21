/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arbitrator.common.enums.Language;

/** Validates trusted compiler configuration and renders it without invoking a shell. */
final class LanguageCommandPolicy {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of("{src}", "{exe}", "{dir}");
    private static final Map<Language, String> COMPILE_EXECUTABLES = Map.of(
            Language.CPP17, "g++",
            Language.JAVA17, "javac");
    private static final Map<Language, String> RUN_EXECUTABLES = Map.of(
            Language.CPP17, "{exe}",
            Language.JAVA17, "java",
            Language.PYTHON310, "python3");

    private LanguageCommandPolicy() {
    }

    static void validateAll(JudgeProperties properties) {
        Map<String, JudgeProperties.LanguageSpec> specs = properties.getLanguages();
        Set<String> expectedKeys = new HashSet<>();
        for (Language language : Language.values()) {
            expectedKeys.add(language.configKey());
            JudgeProperties.LanguageSpec spec = specs.get(language.configKey());
            if (spec == null) {
                throw new IllegalStateException("Missing judge language configuration: "
                        + language.configKey());
            }
            validate(language, spec);
        }
        Set<String> unknown = new HashSet<>(specs.keySet());
        unknown.removeAll(expectedKeys);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Unknown judge language configuration: " + unknown);
        }
    }

    private static void validate(Language language, JudgeProperties.LanguageSpec spec) {
        if (!language.sourceFileName().equals(spec.getSourceFile())) {
            throw new IllegalStateException("Unsafe source filename for " + language.configKey()
                    + ": expected " + language.sourceFileName());
        }

        String compile = spec.getCompile();
        String expectedCompiler = COMPILE_EXECUTABLES.get(language);
        if (expectedCompiler == null) {
            if (compile != null && !compile.isBlank()) {
                throw new IllegalStateException(language.configKey() + " must not configure a compiler");
            }
        } else {
            validateTemplate(language.configKey() + " compile", compile, expectedCompiler, true);
        }
        validateTemplate(language.configKey() + " run", spec.getRun(),
                RUN_EXECUTABLES.get(language), false);
    }

    private static void validateTemplate(String label, String template, String expectedExecutable,
                                         boolean compilerCommand) {
        if (template == null || template.isBlank()) {
            throw new IllegalStateException(label + " command is required");
        }
        if (template.indexOf('\0') >= 0 || template.indexOf('\n') >= 0 || template.indexOf('\r') >= 0) {
            throw new IllegalStateException(label + " command must be a single line");
        }
        List<String> tokens = tokenize(template);
        if (!expectedExecutable.equals(tokens.get(0))) {
            throw new IllegalStateException(label + " executable must be " + expectedExecutable);
        }
        for (String token : tokens) {
            Matcher placeholders = PLACEHOLDER.matcher(token);
            while (placeholders.find()) {
                if (!ALLOWED_PLACEHOLDERS.contains(placeholders.group())) {
                    throw new IllegalStateException(label + " contains unknown placeholder "
                            + placeholders.group());
                }
            }
            if (token.indexOf('{') >= 0 || token.indexOf('}') >= 0) {
                String withoutKnown = token.replace("{src}", "")
                        .replace("{exe}", "").replace("{dir}", "");
                if (withoutKnown.indexOf('{') >= 0 || withoutKnown.indexOf('}') >= 0) {
                    throw new IllegalStateException(label + " contains malformed placeholder syntax");
                }
            }
            if (compilerCommand && isCompilerExtensionOption(token)) {
                throw new IllegalStateException(label + " contains forbidden compiler extension option: "
                        + token);
            }
        }
    }

    private static boolean isCompilerExtensionOption(String token) {
        return token.startsWith("@")
                || token.startsWith("-fplugin")
                || token.startsWith("-specs")
                || token.startsWith("-wrapper")
                || token.equals("-B") || token.startsWith("-B/")
                || token.equals("-processor") || token.startsWith("-processor=")
                || token.equals("-processorpath") || token.startsWith("-processorpath=")
                || token.equals("--processor-path") || token.startsWith("--processor-path=")
                || token.startsWith("-J-agent") || token.startsWith("-J-javaagent");
    }

    static List<String> render(String template, Path src, Path exe, Path dir) {
        List<String> rendered = new ArrayList<>();
        for (String token : tokenize(template)) {
            rendered.add(token
                    .replace("{src}", src.toString())
                    .replace("{exe}", exe.toString())
                    .replace("{dir}", dir.toString()));
        }
        return List.copyOf(rendered);
    }

    private static List<String> tokenize(String template) {
        return List.of(template.trim().split("\\s+"));
    }
}
