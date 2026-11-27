package com.example.githubActions.dao;

import com.example.githubActions.models.Execution;
import com.example.githubActions.models.enums.ExecutionStatus;
import com.example.githubActions.repository.ExecutionRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class ExecutionDao {

    private final ExecutionRepository executionRepository;

    public ExecutionDao(ExecutionRepository executionRepository) {
        this.executionRepository = executionRepository;
    }

    /**
     * Persist a new execution (initially in QUEUED status).
     */
    public Execution save(Execution execution) {
        return executionRepository.save(execution);
    }

    /**
     * Find an execution by its ID.
     */
    public Optional<Execution> findById(String executionId) {
        return executionRepository.findById(executionId);
    }

    /**
     * Find all executions for a given workflow.
     */
    public List<Execution> findByWorkflowId(String workflowId) {
        return executionRepository.findByWorkflowWorkflowId(workflowId);
    }

    /**
     * Find all executions with a given status (e.g., QUEUED for queue recovery on startup).
     */
    public List<Execution> findByStatus(ExecutionStatus status) {
        return executionRepository.findByStatus(status);
    }

    /**
     * Find executions for a specific workflow that are in a given status.
     * Used for per-workflow concurrency checks (e.g., is this workflow already RUNNING?).
     */
    public List<Execution> findByWorkflowIdAndStatus(String workflowId, ExecutionStatus status) {
        return executionRepository.findByWorkflowWorkflowIdAndStatus(workflowId, status);
    }

    /**
     * Mark an execution as RUNNING and record the start time.
     */
    public Execution markAsRunning(Execution execution) {
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setStartedAt(LocalDateTime.now());
        return executionRepository.save(execution);
    }

    /**
     * Mark an execution as SUCCESS and record the completion time.
     */
    public Execution markAsSuccess(Execution execution) {
        execution.setStatus(ExecutionStatus.SUCCESS);
        execution.setCompletedAt(LocalDateTime.now());
        return executionRepository.save(execution);
    }

    /**
     * Mark an execution as FAILED and record the completion time.
     */
    public Execution markAsFailed(Execution execution) {
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setCompletedAt(LocalDateTime.now());
        return executionRepository.save(execution);
    }
}
