package com.example.githubActions.service;

import com.example.githubActions.models.Step;

/**
 * Generic interface for executing a step.
 *
 * This is the extensibility point — implement this interface
 * to support new step types (e.g., Docker-based, HTTP call, script runner).
 * Each implementation handles how a step's command is actually executed.
 */
public interface StepExecutor {

    /**
     * Execute the given step and return the result.
     *
     * @param step the step definition containing command, timeout, env vars
     * @return the result of execution (output, exit code, success/failure)
     */
    StepResult execute(Step step);

    /**
     * Returns the type identifier for this executor.
     * Used for future routing when different steps may need different executors.
     */
    String getType();
}
