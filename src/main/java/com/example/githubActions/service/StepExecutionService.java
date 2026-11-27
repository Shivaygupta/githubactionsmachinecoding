package com.example.githubActions.service;

import com.example.githubActions.dao.StepExecutionDao;
import com.example.githubActions.models.Execution;
import com.example.githubActions.models.Step;
import com.example.githubActions.models.StepExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for orchestrating the execution of individual steps
 * within a workflow execution.
 *
 * Steps run sequentially in order. If any step fails, remaining steps
 * are marked as SKIPPED and the pipeline stops.
 *
 * This service delegates actual step execution to a StepExecutor
 * implementation, making it agnostic to how steps are run.
 */
@Service
public class StepExecutionService {

    private static final Logger log = LoggerFactory.getLogger(StepExecutionService.class);

    private final StepExecutionDao stepExecutionDao;
    private final StepExecutor stepExecutor;

    public StepExecutionService(StepExecutionDao stepExecutionDao, StepExecutor stepExecutor) {
        this.stepExecutionDao = stepExecutionDao;
        this.stepExecutor = stepExecutor;
    }

    /**
     * Create PENDING StepExecution records for every step in the workflow.
     * Called at trigger time before the execution actually starts.
     */
    public List<StepExecution> createStepExecutions(Execution execution, List<Step> steps) {
        List<StepExecution> stepExecutions = steps.stream()
                .map(step -> new StepExecution(execution, step))
                .toList();
        return stepExecutionDao.saveAll(stepExecutions);
    }

    /**
     * Execute all steps sequentially. Returns true if ALL steps passed.
     *
     * For each step:
     * 1. Mark as RUNNING
     * 2. Delegate to StepExecutor
     * 3. Mark as SUCCESS or FAILED based on result
     * 4. If FAILED → mark all remaining steps as SKIPPED → return false
     */
    public boolean executeAllSteps(List<StepExecution> stepExecutions) {
        for (int i = 0; i < stepExecutions.size(); i++) {
            StepExecution stepExecution = stepExecutions.get(i);

            log.info("Running step {}/{}: '{}'",
                    i + 1, stepExecutions.size(), stepExecution.getStep().getName());

            // 1. Mark as RUNNING
            stepExecutionDao.markAsRunning(stepExecution);

            // 2. Execute via the generic StepExecutor
            StepResult result = stepExecutor.execute(stepExecution.getStep());

            // 3. Record result
            if (result.isSuccess()) {
                stepExecutionDao.markAsSuccess(stepExecution, result.getOutput(), result.getExitCode());
                log.info("Step '{}' PASSED", stepExecution.getStep().getName());
            } else {
                stepExecutionDao.markAsFailed(stepExecution, result.getOutput(), result.getExitCode());
                log.warn("Step '{}' FAILED (exit code: {})", stepExecution.getStep().getName(), result.getExitCode());

                // 4. Skip all remaining steps
                skipRemainingSteps(stepExecutions, i + 1);
                return false;
            }
        }

        return true;
    }

    /**
     * Mark all steps from startIndex onwards as SKIPPED.
     */
    private void skipRemainingSteps(List<StepExecution> stepExecutions, int startIndex) {
        for (int i = startIndex; i < stepExecutions.size(); i++) {
            StepExecution remaining = stepExecutions.get(i);
            stepExecutionDao.markAsSkipped(remaining);
            log.info("Step '{}' SKIPPED (previous step failed)", remaining.getStep().getName());
        }
    }

    /**
     * Fetch step-level logs/results for a completed execution, ordered by step position.
     */
    public List<StepExecution> getStepExecutionLogs(String executionId) {
        return stepExecutionDao.findByExecutionIdOrdered(executionId);
    }
}
