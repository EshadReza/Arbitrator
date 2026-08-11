package com.arbitrator.server.judge;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds arbitrator.judge.* from application.yml plus the per-language command
 * table from languages.yml (FR-21). Adding a language never touches Java code.
 */
@ConfigurationProperties(prefix = "arbitrator.judge")
public class JudgeProperties {

    /** Parallel judging jobs (NFR-P04: >= 10). */
    private int threads = 10;

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
