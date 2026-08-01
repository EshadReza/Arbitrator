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

    /** Path to sandbox-run.sh; used on Linux, bypassed elsewhere (dev fallback). */
    private String sandboxScript = "scripts/sandbox-run.sh";

    /** Root under which per-submission work dirs are created (NFR-S03). */
    private String workRoot = System.getProperty("java.io.tmpdir") + "/arbitrator";

    private long compileTimeoutMs = 30000;

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

    public String getSandboxScript() {
        return sandboxScript;
    }

    public void setSandboxScript(String sandboxScript) {
        this.sandboxScript = sandboxScript;
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
