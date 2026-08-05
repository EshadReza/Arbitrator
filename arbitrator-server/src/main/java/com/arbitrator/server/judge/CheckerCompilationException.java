package com.arbitrator.server.judge;

/**
 * Thrown when a custom C++ checker program fails to compile.
 */
public class CheckerCompilationException extends Exception {

    private final String compilerOutput;

    public CheckerCompilationException(String compilerOutput) {
        super(compilerOutput);
        this.compilerOutput = compilerOutput;
    }

    public String getCompilerOutput() {
        return compilerOutput;
    }
}
