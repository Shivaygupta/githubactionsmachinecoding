package com.example.githubActions.dao;

import com.example.githubActions.models.StepExecution;
import com.example.githubActions.models.enums.StepExecutionStatus;
import com.example.githubActions.repository.StepExecutionRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class StepExecutionDao {

    private final StepExecutionRepository stepExecutionRepository;

    public StepExecutionDao(StepExecutionRepository stepExecutionRepository) {
        this.stepExecutionRepository = stepExecutionRepository;
    }

    /**
     * Persist a single step execution.
     */
    public StepExecution save(StepExecution stepExecution) {
        return stepExecutionRepository.save(stepExecution);
    }

    /**
     * Persist multiple step executions in one go (used when creating
     * all PENDING step executions at trigger time).
     */
    public List<StepExecution> saveAll(List<StepExecution> stepExecutions) {
        return stepExecutionRepository.saveAll(stepExecutions);
    }

    /**
     * Find a step execution by its ID.
     */
    public Optional<StepExecution> findById(String stepExecutionId) {
        return stepExecutionRepository.findById(stepExecutionId);
    }

    /**
     * Find all step executions for a given execution, unordered.
     */
    public List<StepExecution> findByExecutionId(String executionId) {
        return stepExecutionRepository.findByExecutionExecutionId(executionId);
    }

    /**
     * Find all step executions for a given execution, ordered by step position.
     * This is the primary method for viewing logs/results.
     */
    public List<StepExecution> findByExecutionIdOrdered(String executionId) {
        return stepExecutionRepository.findByExecutionExecutionIdOrderByStepStepOrderAsc(executionId);
    }

    /**
     * Mark a step execution as RUNNING and record the start time.
     */
    public StepExecution markAsRunning(StepExecution stepExecution) {
        stepExecution.setStatus(StepExecutionStatus.RUNNING);
        stepExecution.setStartedAt(LocalDateTime.now());
        return stepExecutionRepository.save(stepExecution);
    }

    /**
     * Mark a step execution as SUCCESS with its output and exit code.
     */
    public StepExecution markAsSuccess(StepExecution stepExecution, String output, int exitCode) {
        stepExecution.setStatus(StepExecutionStatus.SUCCESS);
        stepExecution.setOutput(output);
        stepExecution.setExitCode(exitCode);
        stepExecution.setCompletedAt(LocalDateTime.now());
        return stepExecutionRepository.save(stepExecution);
    }

    /**
     * Mark a step execution as FAILED with its output and exit code.
     */
    public StepExecution markAsFailed(StepExecution stepExecution, String output, int exitCode) {
        stepExecution.setStatus(StepExecutionStatus.FAILED);
        stepExecution.setOutput(output);
        stepExecution.setExitCode(exitCode);
        stepExecution.setCompletedAt(LocalDateTime.now());
        return stepExecutionRepository.save(stepExecution);
    }

    /**
     * Mark a step execution as SKIPPED (used when a prior step fails
     * and remaining steps should not run).
     */
    public StepExecution markAsSkipped(StepExecution stepExecution) {
        stepExecution.setStatus(StepExecutionStatus.SKIPPED);
        stepExecution.setCompletedAt(LocalDateTime.now());
        return stepExecutionRepository.save(stepExecution);
    }
}
