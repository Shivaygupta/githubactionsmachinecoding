package com.example.githubActions.service;

/**
 * Immutable result of a single step execution.
 * Returned by StepExecutor implementations.
 */
public class StepResult {

    private final boolean success;
    private final String output;
    private final int exitCode;

    public StepResult(boolean success, String output, int exitCode) {
        this.success = success;
        this.output = output;
        this.exitCode = exitCode;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getOutput() {
        return output;
    }

    public int getExitCode() {
        return exitCode;
    }
}
