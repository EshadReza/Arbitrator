/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.common.enums;

/** Submission languages per FR-09. Keys must match entries in languages.yml. */
public enum Language {
    CPP17("C++17", "cpp17", "main.cpp"),
    JAVA17("Java 17", "java17", "Main.java"),
    PYTHON310("Python 3.10", "python310", "main.py");

    private final String display;
    private final String configKey;
    private final String sourceFileName;

    Language(String display, String configKey, String sourceFileName) {
        this.display = display;
        this.configKey = configKey;
        this.sourceFileName = sourceFileName;
    }

    public String display() {
        return display;
    }

    /** Key under judge.languages in languages.yml (FR-21). */
    public String configKey() {
        return configKey;
    }

    /** File name the source is written as inside the sandbox work dir. */
    public String sourceFileName() {
        return sourceFileName;
    }
}
