package com.example.githubActions.service;

import com.example.githubActions.dao.ExecutionDao;
import com.example.githubActions.dao.StepDao;
import com.example.githubActions.dao.WorkflowDao;
import com.example.githubActions.exceptions.ExecutionNotFoundException;
import com.example.githubActions.exceptions.InvalidWorkflowException;
import com.example.githubActions.exceptions.WorkflowNotFoundException;
import com.example.githubActions.models.Execution;
import com.example.githubActions.models.Step;
import com.example.githubActions.models.StepExecution;
import com.example.githubActions.models.Workflow;
import com.example.githubActions.models.enums.ExecutionStatus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Core service for the workflow execution system.
 *
 * Responsibilities:
 * - FR1: Create workflows with configurable steps
 * - FR2: Steps are reusable building blocks, composable in any order
 * - FR3: Trigger workflow execution
 * - FR4: Check execution status
 * - FR5: View execution results/logs
 *
 * NFR: Per-workflow mutual exclusion via an in-memory queue.
 *      Only one execution of a given workflow runs at a time.
 *      Additional triggers are queued and picked up sequentially.
 *
 * On startup, recovers QUEUED/RUNNING executions from the DB.
 */
@Service
public class WorkflowExecutionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionService.class);

    private final WorkflowDao workflowDao;
    private final StepDao stepDao;
    private final ExecutionDao executionDao;
    private final StepExecutionService stepExecutionService;

    /**
     * Per-workflow lock map. Ensures only one execution of a given workflow
     * runs at a time. Keyed by workflowId.
     */
    private final ConcurrentHashMap<String, Object> workflowLocks = new ConcurrentHashMap<>();

    /**
     * Execution queue. All triggered executions go here and get picked
     * up by the single-threaded consumer for sequential processing.
     */
    private final BlockingQueue<Execution> executionQueue = new LinkedBlockingQueue<>();

    /**
     * Single-threaded executor that consumes from the queue.
     * Ensures system-wide sequential execution pickup.
     */
    private final ExecutorService queueConsumer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "execution-queue-consumer");
        t.setDaemon(true);
        return t;
    });

    public WorkflowExecutionService(WorkflowDao workflowDao,
                                     StepDao stepDao,
                                     ExecutionDao executionDao,
                                     StepExecutionService stepExecutionService) {
        this.workflowDao = workflowDao;
        this.stepDao = stepDao;
        this.executionDao = executionDao;
        this.stepExecutionService = stepExecutionService;
    }

    // ==================== STARTUP RECOVERY ====================

    /**
     * On application startup, recover any executions that were QUEUED or
     * RUNNING (stale from a crash) and re-enqueue or fail them.
     */
    @PostConstruct
    public void recoverOnStartup() {
        log.info("Recovering executions from previous run...");

        // Mark stale RUNNING executions as FAILED (crashed mid-execution)
        List<Execution> staleRunning = executionDao.findByStatus(ExecutionStatus.RUNNING);
        for (Execution execution : staleRunning) {
            log.warn("Marking stale RUNNING execution {} as FAILED (crash recovery)",
                    execution.getExecutionId());
            executionDao.markAsFailed(execution);
        }

        // Re-enqueue QUEUED executions
        List<Execution> queued = executionDao.findByStatus(ExecutionStatus.QUEUED);
        for (Execution execution : queued) {
            log.info("Re-enqueuing QUEUED execution {}", execution.getExecutionId());
            executionQueue.offer(execution);
        }

        // Start the queue consumer
        startQueueConsumer();

        log.info("Recovery complete. {} stale executions failed, {} re-enqueued.",
                staleRunning.size(), queued.size());
    }

    // ==================== FR1 & FR2: CREATE WORKFLOW ====================

    /**
     * Create a new workflow with the given name and ordered steps.
     *
     * @param name  unique workflow name
     * @param steps ordered list of steps (each with name, command, timeout, envVars)
     * @return the persisted Workflow with generated ID
     * @throws InvalidWorkflowException if name is blank, steps are empty, or name already exists
     */
    @Transactional
    public Workflow createWorkflow(String name, List<Step> steps) {
        // Validation
        if (name == null || name.isBlank()) {
            throw new InvalidWorkflowException("Workflow name cannot be blank");
        }
        if (steps == null || steps.isEmpty()) {
            throw new InvalidWorkflowException("Workflow must have at least one step");
        }
        if (workflowDao.existsByName(name)) {
            throw new InvalidWorkflowException("Workflow with name '" + name + "' already exists");
        }

        // Validate individual steps
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.getCommand() == null || step.getCommand().isBlank()) {
                throw new InvalidWorkflowException("Step at position " + (i + 1) + " has a blank command");
            }
            if (step.getName() == null || step.getName().isBlank()) {
                throw new InvalidWorkflowException("Step at position " + (i + 1) + " has a blank name");
            }
        }

        // Create workflow
        Workflow workflow = new Workflow(name);
        workflow = workflowDao.save(workflow);

        // Assign step order and link to workflow (clone if reusing an already-persisted Step instance)
        List<Step> stepsToSave = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step source = steps.get(i);
            Step stepToSave;
            if (source.getStepId() != null || source.getWorkflow() != null) {
                stepToSave = new Step(source.getName(), source.getCommand(), i + 1);
                stepToSave.setTimeoutSeconds(source.getTimeoutSeconds());
                stepToSave.setEnvVars(source.getEnvVars());
            } else {
                stepToSave = source;
                stepToSave.setStepOrder(i + 1);
            }
            stepToSave.setWorkflow(workflow);
            stepsToSave.add(stepToSave);
        }

        List<Step> savedSteps = stepDao.saveAll(stepsToSave);
        workflow.setSteps(savedSteps);

        log.info("Created workflow '{}' (id: {}) with {} steps", name, workflow.getWorkflowId(), savedSteps.size());
        return workflow;
    }

    // ==================== FR1: GET / LIST WORKFLOWS ====================

    /**
     * Get a workflow by its ID.
     */
    public Workflow getWorkflow(String workflowId) {
        return workflowDao.findById(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowId));
    }

    /**
     * List all workflows.
     */
    public List<Workflow> listWorkflows() {
        return workflowDao.findAll();
    }

    // ==================== FR3: TRIGGER EXECUTION ====================

    /**
     * Trigger execution of a workflow. The execution is created with QUEUED status
     * and placed on the execution queue. It will be picked up by the queue consumer
     * when no other execution of the same workflow is running (per-workflow lock).
     *
     * @param workflowId the workflow to execute
     * @return the created Execution in QUEUED status
     * @throws WorkflowNotFoundException if the workflow doesn't exist
     */
    @Transactional
    public Execution triggerExecution(String workflowId) {
        Workflow workflow = workflowDao.findById(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowId));

        // Create Execution in QUEUED status
        Execution execution = new Execution(workflow);
        execution = executionDao.save(execution);

        // Create PENDING StepExecution records for each step
        List<Step> steps = stepDao.findByWorkflowIdOrdered(workflowId);
        stepExecutionService.createStepExecutions(execution, steps);

        log.info("Execution {} queued for workflow '{}' (id: {})",
                execution.getExecutionId(), workflow.getName(), workflowId);

        // Enqueue only after transaction commits so consumer sees committed DB rows
        final Execution queuedExecution = execution;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executionQueue.offer(queuedExecution);
                }
            });
        } else {
            executionQueue.offer(queuedExecution);
        }

        return execution;
    }

    // ==================== FR4: CHECK STATUS ====================

    /**
     * Get the current status of an execution.
     */
    public Execution getExecutionStatus(String executionId) {
        return executionDao.findById(executionId)
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
    }

    /**
     * List all executions for a given workflow.
     */
    public List<Execution> getExecutionsByWorkflowId(String workflowId) {
        if (workflowDao.findById(workflowId).isEmpty()) {
            throw new WorkflowNotFoundException(workflowId);
        }
        return executionDao.findByWorkflowId(workflowId);
    }

    // ==================== FR5: VIEW RESULTS ====================

    /**
     * Get step-level logs/results for a completed execution.
     */
    public List<StepExecution> getExecutionLogs(String executionId) {
        if (executionDao.findById(executionId).isEmpty()) {
            throw new ExecutionNotFoundException(executionId);
        }
        return stepExecutionService.getStepExecutionLogs(executionId);
    }

    // ==================== NFR: QUEUE CONSUMER ====================

    /**
     * Starts the background queue consumer thread.
     * Continuously polls the queue and processes executions one at a time.
     * Per-workflow locking ensures only one execution per workflow runs concurrently.
     */
    private void startQueueConsumer() {
        queueConsumer.submit(() -> {
            log.info("Execution queue consumer started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Block until an execution is available
                    Execution execution = executionQueue.take();
                    processExecution(execution);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Execution queue consumer interrupted, shutting down");
                    break;
                } catch (Exception e) {
                    log.error("Unexpected error in queue consumer", e);
                    // Continue processing — don't let one bad execution kill the consumer
                }
            }
        });
    }

    /**
     * Process a single execution. Acquires a per-workflow lock to ensure
     * mutual exclusion for the same workflow.
     */
    private void processExecution(Execution execution) {
        String workflowId = execution.getWorkflow().getWorkflowId();
        String executionId = execution.getExecutionId();

        // Per-workflow lock — ensures only one execution of this workflow runs at a time
        Object lock = workflowLocks.computeIfAbsent(workflowId, k -> new Object());

        synchronized (lock) {
            log.info("Processing execution:{} for workflow:{}", executionId, workflowId);

            try {
                // Re-fetch from DB to get the latest state (might have been modified)
                Execution freshExecution = executionDao.findById(executionId).orElse(null);
                if (freshExecution == null) {
                    log.warn("Execution {} no longer exists, skipping", executionId);
                    return;
                }

                // Only process if still QUEUED
                if (freshExecution.getStatus() != ExecutionStatus.QUEUED) {
                    log.info("Execution {} is no longer QUEUED (status: {}), skipping",
                            executionId, freshExecution.getStatus());
                    return;
                }

                // Mark as RUNNING
                executionDao.markAsRunning(freshExecution);

                // Fetch ordered step executions
                List<StepExecution> stepExecutions =
                        stepExecutionService.getStepExecutionLogs(executionId);

                // Execute all steps sequentially
                boolean allPassed = stepExecutionService.executeAllSteps(stepExecutions);

                // Mark execution as SUCCESS or FAILED
                if (allPassed) {
                    executionDao.markAsSuccess(freshExecution);
                    log.info("Execution {} completed SUCCESSFULLY", executionId);
                } else {
                    executionDao.markAsFailed(freshExecution);
                    log.info("Execution {} FAILED", executionId);
                }

            } catch (Exception e) {
                log.error("Execution {} failed with unexpected error: {}", executionId, e.getMessage(), e);
                // Try to mark as failed in DB
                try {
                    Execution failedExecution = executionDao.findById(executionId).orElse(null);
                    if (failedExecution != null) {
                        executionDao.markAsFailed(failedExecution);
                    }
                } catch (Exception dbError) {
                    log.error("Failed to mark execution {} as FAILED in DB", executionId, dbError);
                }
            }
        }
    }
}
