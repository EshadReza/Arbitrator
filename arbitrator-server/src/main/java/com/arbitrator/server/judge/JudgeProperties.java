/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.judge;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Binds arbitrator.judge.* from application.yml plus the per-language command
 * table from languages.yml (FR-21). LanguageCommandPolicy deliberately
 * allowlists each supported toolchain, so adding a language requires updating
 * both the enum/configuration and that security policy.
 */
@ConfigurationProperties(prefix = "arbitrator.judge")
@Validated
public class JudgeProperties {

    /** Parallel judging jobs (NFR-P04: >= 10). */
    @Min(1)
    @Max(32)
    private int threads = 10;

    /** Maximum running plus queued submissions across all users. */
    @Min(1)
    private int maxBacklog = 500;

    /** Maximum Docker containers executing at once, including custom runs. */
    @Min(1)
    @Max(32) // two bounded stream readers per active container
    private int maxActiveContainers = 10;

    /**
     * Aggregate container-memory budget in MiB. Zero derives a fail-safe
     * budget of 70% of physical RAM at startup.
     */
    @Min(0)
    private int maxTotalMemoryMb = 0;

    /** Maximum simultaneous custom runs across all users. */
    @Min(1)
    @Max(32)
    private int maxCustomRuns = 2;

    /** UTF-8 byte limit for submitted and custom-run source. */
    @Min(1024)
    private int maxSourceBytes = 256 * 1024;

    /**
     * Docker CLI binary. Every compile and every test run is executed inside
     * a container built from {@link #dockerImage} — see
     * scripts/docker/Dockerfile and SandboxExecutor. Configurable purely so a
     * non-PATH docker (e.g. a Docker Desktop symlink quirk) can be pointed at
     * directly without a code change.
     */
    private String dockerBinary = "docker";

    /**
     * The sandbox image, built with scripts/docker/build-sandbox-image.sh.
     * SandboxExecutor resolves this reference to its immutable image ID once
     * at startup, so retagging cannot change toolchains beneath a running judge.
     * One shared image for every language in languages.yml — same design as
     * this class itself, where the toolchain choice lives in config, not code.
     */
    private String dockerImage = "arbitrator-judge:latest";

    /** Root under which per-submission work dirs are created (NFR-S03). */
    private String workRoot = System.getProperty("java.io.tmpdir") + "/arbitrator";

    private long compileTimeoutMs = 30000;

    private long checkerTimeLimitMs = 5000;

    private long checkerMemoryLimitKb = 262144; // 256 MiB

    /** BR-01: one submission per user per this many seconds. */
    private int submitCooldownSeconds = 30;

    private Map<String, LanguageSpec> languages = Map.of();

    public static class LanguageSpec {

        /** File name the source is written as, e.g. main.cpp / Main.java. */
        private String sourceFile;

        /** Compile command template; empty string = interpreted language. */
        private String compile = "";

        /** Run command template. Placeholders: {src} {exe} {dir}. */
        private String run;

        public String getSourceFile() {
            return sourceFile;
        }

        public void setSourceFile(String sourceFile) {
            this.sourceFile = sourceFile;
        }

        public String getCompile() {
            return compile;
        }

        public void setCompile(String compile) {
            this.compile = compile;
        }

        public String getRun() {
            return run;
        }

        public void setRun(String run) {
            this.run = run;
        }
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public int getMaxBacklog() {
        return maxBacklog;
    }

    public void setMaxBacklog(int maxBacklog) {
        this.maxBacklog = maxBacklog;
    }

    public int getMaxActiveContainers() {
        return maxActiveContainers;
    }

    public void setMaxActiveContainers(int maxActiveContainers) {
        this.maxActiveContainers = maxActiveContainers;
    }

    public int getMaxTotalMemoryMb() {
        return maxTotalMemoryMb;
    }

    public void setMaxTotalMemoryMb(int maxTotalMemoryMb) {
        this.maxTotalMemoryMb = maxTotalMemoryMb;
    }

    public int getMaxCustomRuns() {
        return maxCustomRuns;
    }

    public void setMaxCustomRuns(int maxCustomRuns) {
        this.maxCustomRuns = maxCustomRuns;
    }

    public int getMaxSourceBytes() {
        return maxSourceBytes;
    }

    public void setMaxSourceBytes(int maxSourceBytes) {
        this.maxSourceBytes = maxSourceBytes;
    }

    public String getDockerBinary() {
        return dockerBinary;
    }

    public void setDockerBinary(String dockerBinary) {
        this.dockerBinary = dockerBinary;
    }

    public String getDockerImage() {
        return dockerImage;
    }

    public void setDockerImage(String dockerImage) {
        this.dockerImage = dockerImage;
    }

    public String getWorkRoot() {
        return workRoot;
    }

    public void setWorkRoot(String workRoot) {
        this.workRoot = workRoot;
    }

    public long getCompileTimeoutMs() {
        return compileTimeoutMs;
    }

    public void setCompileTimeoutMs(long compileTimeoutMs) {
        this.compileTimeoutMs = compileTimeoutMs;
    }

    public long getCheckerTimeLimitMs() {
        return checkerTimeLimitMs;
    }

    public void setCheckerTimeLimitMs(long checkerTimeLimitMs) {
        this.checkerTimeLimitMs = checkerTimeLimitMs;
    }

    public long getCheckerMemoryLimitKb() {
        return checkerMemoryLimitKb;
    }

    public void setCheckerMemoryLimitKb(long checkerMemoryLimitKb) {
        this.checkerMemoryLimitKb = checkerMemoryLimitKb;
    }

    public int getSubmitCooldownSeconds() {
        return submitCooldownSeconds;
    }

    public void setSubmitCooldownSeconds(int submitCooldownSeconds) {
        this.submitCooldownSeconds = submitCooldownSeconds;
    }

    public Map<String, LanguageSpec> getLanguages() {
        return languages;
    }

    public void setLanguages(Map<String, LanguageSpec> languages) {
        this.languages = languages;
    }
}
