package com.example.githubActions.service;

import com.example.githubActions.exceptions.ExecutionNotFoundException;
import com.example.githubActions.exceptions.InvalidWorkflowException;
import com.example.githubActions.exceptions.WorkflowNotFoundException;
import com.example.githubActions.models.Execution;
import com.example.githubActions.models.Step;
import com.example.githubActions.models.StepExecution;
import com.example.githubActions.models.Workflow;
import com.example.githubActions.models.enums.ExecutionStatus;
import com.example.githubActions.models.enums.StepExecutionStatus;
import com.example.githubActions.repository.ExecutionRepository;
import com.example.githubActions.repository.StepExecutionRepository;
import com.example.githubActions.repository.StepRepository;
import com.example.githubActions.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WorkflowExecutionServiceTest {

    @Autowired
    private WorkflowExecutionService workflowExecutionService;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private StepRepository stepRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private StepExecutionRepository stepExecutionRepository;

    @BeforeEach
    void cleanDatabase() {
        stepExecutionRepository.deleteAll();
        executionRepository.deleteAll();
        stepRepository.deleteAll();
        workflowRepository.deleteAll();
    }

    /**
     * Helper to wait until an execution reaches a terminal state (SUCCESS or FAILED).
     */
    private Execution awaitCompletion(String executionId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Execution execution = workflowExecutionService.getExecutionStatus(executionId);
            if (execution.getStatus() == ExecutionStatus.SUCCESS || execution.getStatus() == ExecutionStatus.FAILED) {
                return execution;
            }
            Thread.sleep(50);
        }
        fail("Execution " + executionId + " did not complete within " + timeout);
        return null;
    }

    // =========================================================================
    // SCENARIO 1: Happy path with 4 steps (Build, Test, Validate, Deploy)
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: Happy path 4-step workflow (Build -> Test -> Validate -> Deploy) succeeds end-to-end")
    void testHappyPathFourStepWorkflow() throws InterruptedException {
        List<Step> steps = List.of(
                new Step("Build Code", "echo 'Building application...'", 1),
                new Step("Run Tests", "echo 'Running unit tests: 42 passed'", 2),
                new Step("Validate Changes", "echo 'Validating lint and schema'", 3),
                new Step("Perform Deployment", "echo 'Deploying to staging'", 4)
        );

        Workflow workflow = workflowExecutionService.createWorkflow("ci-cd-pipeline", steps);
        assertNotNull(workflow.getWorkflowId());
        assertEquals(4, workflow.getSteps().size());

        Execution triggered = workflowExecutionService.triggerExecution(workflow.getWorkflowId());
        assertNotNull(triggered.getExecutionId());

        Execution completed = awaitCompletion(triggered.getExecutionId(), Duration.ofSeconds(10));
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());
        assertNotNull(completed.getStartedAt());
        assertNotNull(completed.getCompletedAt());

        List<StepExecution> logs = workflowExecutionService.getExecutionLogs(completed.getExecutionId());
        assertEquals(4, logs.size());

        List<String> expectedNames = List.of("Build Code", "Run Tests", "Validate Changes", "Perform Deployment");
        List<String> expectedOutputs = List.of(
                "Building application...",
                "Running unit tests: 42 passed",
                "Validating lint and schema",
                "Deploying to staging"
        );

        for (int i = 0; i < 4; i++) {
            StepExecution stepExec = logs.get(i);
            assertEquals(expectedNames.get(i), stepExec.getStep().getName());
            assertEquals(StepExecutionStatus.SUCCESS, stepExec.getStatus());
            assertEquals(0, stepExec.getExitCode());
            assertTrue(stepExec.getOutput().contains(expectedOutputs.get(i)));
            assertNotNull(stepExec.getStartedAt());
            assertNotNull(stepExec.getCompletedAt());
        }
    }

    // =========================================================================
    // SCENARIO 2: Step failure & timeout behavior
    // =========================================================================

    @Test
    @DisplayName("Scenario 2a: When step 2 fails, step 1 succeeds, step 2 fails, steps 3 & 4 are SKIPPED, and execution is FAILED")
    void testStepFailureSkipsRemainingStepsAndFailsExecution() throws InterruptedException {
        List<Step> steps = List.of(
                new Step("Build Code", "echo 'Build OK'", 1),
                new Step("Run Tests", "echo 'Test failure!' && exit 1", 2),
                new Step("Validate Changes", "echo 'Should not run'", 3),
                new Step("Perform Deployment", "echo 'Should not deploy'", 4)
        );

        Workflow workflow = workflowExecutionService.createWorkflow("failing-pipeline", steps);
        Execution triggered = workflowExecutionService.triggerExecution(workflow.getWorkflowId());

        Execution completed = awaitCompletion(triggered.getExecutionId(), Duration.ofSeconds(10));
        assertEquals(ExecutionStatus.FAILED, completed.getStatus());

        List<StepExecution> logs = workflowExecutionService.getExecutionLogs(completed.getExecutionId());
        assertEquals(4, logs.size());

        // Step 1 passed
        assertEquals(StepExecutionStatus.SUCCESS, logs.get(0).getStatus());
        assertEquals(0, logs.get(0).getExitCode());
        assertTrue(logs.get(0).getOutput().contains("Build OK"));

        // Step 2 failed with exit code 1
        assertEquals(StepExecutionStatus.FAILED, logs.get(1).getStatus());
        assertEquals(1, logs.get(1).getExitCode());
        assertTrue(logs.get(1).getOutput().contains("Test failure!"));

        // Steps 3 and 4 skipped
        assertEquals(StepExecutionStatus.SKIPPED, logs.get(2).getStatus());
        assertNull(logs.get(2).getOutput());
        assertEquals(StepExecutionStatus.SKIPPED, logs.get(3).getStatus());
        assertNull(logs.get(3).getOutput());
    }

    @Test
    @DisplayName("Scenario 2b: Step exceeding timeout is terminated and marks execution as FAILED")
    void testStepTimeoutFailsStepAndExecution() throws InterruptedException {
        Step slowStep = new Step("Slow Step", "sleep 5 && echo 'done'", 1);
        slowStep.setTimeoutSeconds(1);
        Step nextStep = new Step("Deploy", "echo 'deploy'", 2);

        Workflow workflow = workflowExecutionService.createWorkflow("timeout-pipeline", List.of(slowStep, nextStep));
        Execution triggered = workflowExecutionService.triggerExecution(workflow.getWorkflowId());

        Execution completed = awaitCompletion(triggered.getExecutionId(), Duration.ofSeconds(10));
        assertEquals(ExecutionStatus.FAILED, completed.getStatus());

        List<StepExecution> logs = workflowExecutionService.getExecutionLogs(completed.getExecutionId());
        assertEquals(2, logs.size());
        assertEquals(StepExecutionStatus.FAILED, logs.get(0).getStatus());
        assertTrue(logs.get(0).getOutput().contains("timed out"));
        assertEquals(StepExecutionStatus.SKIPPED, logs.get(1).getStatus());
    }

    // =========================================================================
    // SCENARIO 3: Individual tests for each Functional Requirement (FR1 - FR5)
    // =========================================================================

    @Test
    @DisplayName("FR1: Define workflow with configurable steps (envVars, timeout), get & list workflows, and validate inputs")
    void testFR1CreateGetListAndValidateWorkflow() throws InterruptedException {
        Step step = new Step("Configured Step", "echo \"ENV=$APP_ENV\"", 1);
        step.setTimeoutSeconds(15);
        step.setEnvVars("{\"APP_ENV\":\"production\"}");

        Workflow created = workflowExecutionService.createWorkflow("fr1-workflow", List.of(step));

        // Verify getWorkflow & listWorkflows
        Workflow fetched = workflowExecutionService.getWorkflow(created.getWorkflowId());
        assertEquals("fr1-workflow", fetched.getName());
        assertEquals(1, fetched.getSteps().size());
        assertEquals(15, fetched.getSteps().get(0).getTimeoutSeconds());
        assertEquals("{\"APP_ENV\":\"production\"}", fetched.getSteps().get(0).getEnvVars());
        assertEquals(1, workflowExecutionService.listWorkflows().size());

        // Verify envVars actually injected during execution
        Execution exec = workflowExecutionService.triggerExecution(created.getWorkflowId());
        Execution done = awaitCompletion(exec.getExecutionId(), Duration.ofSeconds(10));
        assertEquals(ExecutionStatus.SUCCESS, done.getStatus());
        List<StepExecution> logs = workflowExecutionService.getExecutionLogs(done.getExecutionId());
        assertTrue(logs.get(0).getOutput().contains("ENV=production"));

        // Validation errors
        assertThrows(InvalidWorkflowException.class,
                () -> workflowExecutionService.createWorkflow("  ", List.of(new Step("s", "echo 1", 1))));
        assertThrows(InvalidWorkflowException.class,
                () -> workflowExecutionService.createWorkflow("empty-steps", List.of()));
        assertThrows(InvalidWorkflowException.class,
                () -> workflowExecutionService.createWorkflow("fr1-workflow", List.of(new Step("s", "echo 1", 1))));
        assertThrows(InvalidWorkflowException.class,
                () -> workflowExecutionService.createWorkflow("blank-cmd", List.of(new Step("s", "  ", 1))));
        assertThrows(WorkflowNotFoundException.class,
                () -> workflowExecutionService.getWorkflow("non-existent-id"));
    }

    @Test
    @DisplayName("FR2: Steps are reusable and can be rotated in any order across workflows")
    void testFR2StepReusabilityAndRotation() throws InterruptedException {
        Step build = new Step("Build", "echo 'step-build'", 0);
        Step test = new Step("Test", "echo 'step-test'", 0);
        Step validate = new Step("Validate", "echo 'step-validate'", 0);
        Step deploy = new Step("Deploy", "echo 'step-deploy'", 0);

        // Workflow A: Build -> Test -> Validate -> Deploy
        Workflow wfA = workflowExecutionService.createWorkflow("order-A", List.of(build, test, validate, deploy));
        // Workflow B: Reuses exact same Step objects in rotated order: Validate -> Build -> Deploy -> Test
        Workflow wfB = workflowExecutionService.createWorkflow("order-B", List.of(validate, build, deploy, test));

        Execution execA = workflowExecutionService.triggerExecution(wfA.getWorkflowId());
        Execution execB = workflowExecutionService.triggerExecution(wfB.getWorkflowId());

        Execution doneA = awaitCompletion(execA.getExecutionId(), Duration.ofSeconds(10));
        Execution doneB = awaitCompletion(execB.getExecutionId(), Duration.ofSeconds(10));

        assertEquals(ExecutionStatus.SUCCESS, doneA.getStatus());
        assertEquals(ExecutionStatus.SUCCESS, doneB.getStatus());

        List<String> actualOrderA = workflowExecutionService.getExecutionLogs(doneA.getExecutionId())
                .stream().map(se -> se.getStep().getName()).toList();
        List<String> actualOrderB = workflowExecutionService.getExecutionLogs(doneB.getExecutionId())
                .stream().map(se -> se.getStep().getName()).toList();

        assertEquals(List.of("Build", "Test", "Validate", "Deploy"), actualOrderA);
        assertEquals(List.of("Validate", "Build", "Deploy", "Test"), actualOrderB);
    }

    @Test
    @DisplayName("FR3: Trigger workflow creates QUEUED execution and rejects unknown workflow ID")
    void testFR3TriggerWorkflow() throws InterruptedException {
        Workflow wf = workflowExecutionService.createWorkflow(
                "fr3-workflow",
                List.of(new Step("Step 1", "sleep 0.2 && echo 'ok'", 1))
        );

        Execution triggered = workflowExecutionService.triggerExecution(wf.getWorkflowId());
        assertNotNull(triggered.getExecutionId());
        assertEquals(ExecutionStatus.QUEUED, triggered.getStatus());

        awaitCompletion(triggered.getExecutionId(), Duration.ofSeconds(10));

        assertThrows(WorkflowNotFoundException.class,
                () -> workflowExecutionService.triggerExecution("missing-workflow-id"));
    }

    @Test
    @DisplayName("FR4: Monitor execution status by executionId and list executions by workflowId")
    void testFR4CheckExecutionStatus() throws InterruptedException {
        Workflow wf = workflowExecutionService.createWorkflow(
                "fr4-workflow",
                List.of(new Step("Step 1", "echo 'status check'", 1))
        );

        Execution exec1 = workflowExecutionService.triggerExecution(wf.getWorkflowId());
        Execution exec2 = workflowExecutionService.triggerExecution(wf.getWorkflowId());

        awaitCompletion(exec1.getExecutionId(), Duration.ofSeconds(10));
        awaitCompletion(exec2.getExecutionId(), Duration.ofSeconds(10));

        Execution status1 = workflowExecutionService.getExecutionStatus(exec1.getExecutionId());
        assertEquals(ExecutionStatus.SUCCESS, status1.getStatus());

        List<Execution> workflowExecutions = workflowExecutionService.getExecutionsByWorkflowId(wf.getWorkflowId());
        assertEquals(2, workflowExecutions.size());

        assertThrows(ExecutionNotFoundException.class,
                () -> workflowExecutionService.getExecutionStatus("missing-exec-id"));
    }

    @Test
    @DisplayName("FR5: View step-level results and logs after execution completes")
    void testFR5ViewExecutionLogs() throws InterruptedException {
        Workflow wf = workflowExecutionService.createWorkflow(
                "fr5-workflow",
                List.of(
                        new Step("Compile", "echo 'compiled 12 files'", 1),
                        new Step("Package", "echo 'built app.jar'", 2)
                )
        );

        Execution exec = workflowExecutionService.triggerExecution(wf.getWorkflowId());
        awaitCompletion(exec.getExecutionId(), Duration.ofSeconds(10));

        List<StepExecution> logs = workflowExecutionService.getExecutionLogs(exec.getExecutionId());
        assertEquals(2, logs.size());
        assertEquals("Compile", logs.get(0).getStep().getName());
        assertTrue(logs.get(0).getOutput().contains("compiled 12 files"));
        assertEquals("Package", logs.get(1).getStep().getName());
        assertTrue(logs.get(1).getOutput().contains("built app.jar"));

        assertThrows(ExecutionNotFoundException.class,
                () -> workflowExecutionService.getExecutionLogs("missing-exec-id"));
    }

    // =========================================================================
    // SCENARIO 4: Concurrent execution test (NFR: queuing & sequential pickup)
    // =========================================================================

    @Test
    @DisplayName("Scenario 4 (NFR): Concurrent triggers on same workflow are queued and executed strictly one at a time without overlap")
    void testConcurrentTriggersQueuedAndExecutedSequentially() throws Exception {
        // Each run takes ~300ms so any overlap would be clearly visible in timestamps
        Workflow wf = workflowExecutionService.createWorkflow(
                "concurrent-workflow",
                List.of(
                        new Step("Step 1", "sleep 0.3 && echo 'step1-done'", 1),
                        new Step("Step 2", "echo 'step2-done'", 2)
                )
        );

        int concurrentUsers = 4;
        ExecutorService callers = Executors.newFixedThreadPool(concurrentUsers);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Execution>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentUsers; i++) {
            futures.add(callers.submit(() -> {
                startGate.await();
                return workflowExecutionService.triggerExecution(wf.getWorkflowId());
            }));
        }

        // Release all 4 concurrent triggers simultaneously
        startGate.countDown();

        List<String> executionIds = new ArrayList<>();
        for (Future<Execution> future : futures) {
            Execution exec = future.get(5, TimeUnit.SECONDS);
            executionIds.add(exec.getExecutionId());
        }
        callers.shutdown();

        assertEquals(concurrentUsers, executionIds.size());

        // Wait for all 4 queued executions to complete
        List<Execution> completedExecutions = new ArrayList<>();
        for (String execId : executionIds) {
            Execution done = awaitCompletion(execId, Duration.ofSeconds(15));
            assertEquals(ExecutionStatus.SUCCESS, done.getStatus());
            completedExecutions.add(done);
        }

        // Sort by startedAt and verify strictly sequential execution (no overlap!)
        completedExecutions.sort(Comparator.comparing(Execution::getStartedAt));
        for (int i = 1; i < completedExecutions.size(); i++) {
            Execution prev = completedExecutions.get(i - 1);
            Execution curr = completedExecutions.get(i);
            assertFalse(
                    curr.getStartedAt().isBefore(prev.getCompletedAt()),
                    String.format("Execution %d started at %s before previous execution completed at %s — concurrency lock violated!",
                            i, curr.getStartedAt(), prev.getCompletedAt())
            );
        }
    }
}
