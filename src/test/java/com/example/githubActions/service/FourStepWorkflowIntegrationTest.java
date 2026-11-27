package com.example.githubActions.service;

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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FourStepWorkflowIntegrationTest {

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

    private Workflow ciCdWorkflow;

    @BeforeEach
    void setUpFourStepWorkflow() {
        stepExecutionRepository.deleteAll();
        executionRepository.deleteAll();
        stepRepository.deleteAll();
        workflowRepository.deleteAll();

        // Define the 4-step CI/CD workflow:
        // 1. building code, 2. running tests, 3. validating changes, 4. performing deployments
        List<Step> steps = List.of(
                createStep("building code", "echo '[BUILD] Compiling source code...' && echo '[BUILD] Artifact built'", 10, "{\"BUILD_ENV\":\"ci\"}"),
                createStep("running tests", "echo '[TEST] Executing test suite...' && echo '[TEST] All tests passed'", 10, "{\"TEST_SUITE\":\"unit\"}"),
                createStep("validating changes", "echo '[VALIDATE] Running lint and static analysis...' && echo '[VALIDATE] Clean'", 10, null),
                createStep("performing deployments", "echo \"[DEPLOY] Deploying build artifact to target=$DEPLOY_TARGET\"", 10, "{\"DEPLOY_TARGET\":\"staging\"}")
        );

        ciCdWorkflow = workflowExecutionService.createWorkflow("standard-ci-cd-workflow", steps);
    }

    private Step createStep(String name, String command, int timeoutSeconds, String envVars) {
        Step step = new Step(name, command, 0);
        step.setTimeoutSeconds(timeoutSeconds);
        step.setEnvVars(envVars);
        return step;
    }

    private Execution awaitCompletion(String executionId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Execution execution = workflowExecutionService.getExecutionStatus(executionId);
            if (execution.getStatus() == ExecutionStatus.SUCCESS || execution.getStatus() == ExecutionStatus.FAILED) {
                return execution;
            }
            Thread.sleep(50);
        }
        fail("Execution " + executionId + " did not finish within " + timeout);
        return null;
    }

    @Test
    @DisplayName("1. Verify 4-step workflow definition is persisted with exact names, order, and config")
    void shouldPersistFourStepWorkflowDefinitionCorrectly() {
        Workflow fetched = workflowExecutionService.getWorkflow(ciCdWorkflow.getWorkflowId());

        assertEquals("standard-ci-cd-workflow", fetched.getName());
        assertNotNull(fetched.getCreatedAt());
        assertEquals(4, fetched.getSteps().size());

        List<String> stepNames = fetched.getSteps().stream().map(Step::getName).toList();
        assertEquals(
                List.of("building code", "running tests", "validating changes", "performing deployments"),
                stepNames
        );
        assertEquals(1, fetched.getSteps().get(0).getStepOrder());
        assertEquals(2, fetched.getSteps().get(1).getStepOrder());
        assertEquals(3, fetched.getSteps().get(2).getStepOrder());
        assertEquals(4, fetched.getSteps().get(3).getStepOrder());
    }

    @Test
    @DisplayName("2. Trigger and run the 4-step workflow end-to-end and verify all step logs, env vars, and exit codes")
    void shouldExecuteAllFourStepsSuccessfullyInOrder() throws InterruptedException {
        Execution triggered = workflowExecutionService.triggerExecution(ciCdWorkflow.getWorkflowId());
        assertNotNull(triggered.getExecutionId());

        Execution completed = awaitCompletion(triggered.getExecutionId(), Duration.ofSeconds(10));
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());
        assertNotNull(completed.getStartedAt());
        assertNotNull(completed.getCompletedAt());
        assertFalse(completed.getCompletedAt().isBefore(completed.getStartedAt()));

        List<StepExecution> stepLogs = workflowExecutionService.getExecutionLogs(completed.getExecutionId());
        assertEquals(4, stepLogs.size());

        assertEquals("building code", stepLogs.get(0).getStep().getName());
        assertEquals(StepExecutionStatus.SUCCESS, stepLogs.get(0).getStatus());
        assertEquals(0, stepLogs.get(0).getExitCode());
        assertTrue(stepLogs.get(0).getOutput().contains("[BUILD] Artifact built"));

        assertEquals("running tests", stepLogs.get(1).getStep().getName());
        assertEquals(StepExecutionStatus.SUCCESS, stepLogs.get(1).getStatus());
        assertEquals(0, stepLogs.get(1).getExitCode());
        assertTrue(stepLogs.get(1).getOutput().contains("[TEST] All tests passed"));

        assertEquals("validating changes", stepLogs.get(2).getStep().getName());
        assertEquals(StepExecutionStatus.SUCCESS, stepLogs.get(2).getStatus());
        assertEquals(0, stepLogs.get(2).getExitCode());
        assertTrue(stepLogs.get(2).getOutput().contains("[VALIDATE] Clean"));

        assertEquals("performing deployments", stepLogs.get(3).getStep().getName());
        assertEquals(StepExecutionStatus.SUCCESS, stepLogs.get(3).getStatus());
        assertEquals(0, stepLogs.get(3).getExitCode());
        assertTrue(stepLogs.get(3).getOutput().contains("target=staging"));
    }

    @Test
    @DisplayName("3. Reuse the 4 steps in a rotated order (validate -> build -> test -> deploy) in a second workflow")
    void shouldAllowRotatingAndReusingFourStepsInAnotherWorkflow() throws InterruptedException {
        List<Step> originalSteps = workflowExecutionService.getWorkflow(ciCdWorkflow.getWorkflowId()).getSteps();

        // Rotate: validating changes -> building code -> running tests -> performing deployments
        List<Step> rotatedSteps = List.of(
                originalSteps.get(2),
                originalSteps.get(0),
                originalSteps.get(1),
                originalSteps.get(3)
        );

        Workflow rotatedWorkflow = workflowExecutionService.createWorkflow("rotated-ci-cd-workflow", rotatedSteps);
        Execution exec = workflowExecutionService.triggerExecution(rotatedWorkflow.getWorkflowId());

        Execution completed = awaitCompletion(exec.getExecutionId(), Duration.ofSeconds(10));
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());

        List<String> executedOrder = workflowExecutionService.getExecutionLogs(completed.getExecutionId())
                .stream()
                .map(se -> se.getStep().getName())
                .toList();

        assertEquals(
                List.of("validating changes", "building code", "running tests", "performing deployments"),
                executedOrder
        );
    }

    @Test
    @DisplayName("4. When 'running tests' fails, 'validating changes' and 'performing deployments' are skipped")
    void shouldSkipValidationAndDeploymentWhenTestsFail() throws InterruptedException {
        Workflow failingCiCd = workflowExecutionService.createWorkflow(
                "failing-ci-cd-workflow",
                List.of(
                        createStep("building code", "echo '[BUILD] OK'", 10, null),
                        createStep("running tests", "echo '[TEST] 2 assertions failed' && exit 1", 10, null),
                        createStep("validating changes", "echo '[VALIDATE] should be skipped'", 10, null),
                        createStep("performing deployments", "echo '[DEPLOY] should never deploy'", 10, null)
                )
        );

        Execution exec = workflowExecutionService.triggerExecution(failingCiCd.getWorkflowId());
        Execution completed = awaitCompletion(exec.getExecutionId(), Duration.ofSeconds(10));

        assertEquals(ExecutionStatus.FAILED, completed.getStatus());

        List<StepExecution> logs = workflowExecutionService.getExecutionLogs(completed.getExecutionId());
        assertEquals(4, logs.size());
        assertEquals(StepExecutionStatus.SUCCESS, logs.get(0).getStatus());
        assertEquals(StepExecutionStatus.FAILED, logs.get(1).getStatus());
        assertEquals(1, logs.get(1).getExitCode());
        assertEquals(StepExecutionStatus.SKIPPED, logs.get(2).getStatus());
        assertEquals(StepExecutionStatus.SKIPPED, logs.get(3).getStatus());
    }

    @Test
    @DisplayName("5. Two concurrent triggers on the 4-step workflow execute sequentially via the queue")
    void shouldQueueAndRunConcurrentTriggersOnFourStepWorkflowSequentially() throws Exception {
        Workflow timedWorkflow = workflowExecutionService.createWorkflow(
                "timed-four-step-workflow",
                List.of(
                        createStep("building code", "sleep 0.2 && echo 'build'", 10, null),
                        createStep("running tests", "echo 'test'", 10, null),
                        createStep("validating changes", "echo 'validate'", 10, null),
                        createStep("performing deployments", "echo 'deploy'", 10, null)
                )
        );

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);

        Future<Execution> user1 = pool.submit(() -> {
            gate.await();
            return workflowExecutionService.triggerExecution(timedWorkflow.getWorkflowId());
        });
        Future<Execution> user2 = pool.submit(() -> {
            gate.await();
            return workflowExecutionService.triggerExecution(timedWorkflow.getWorkflowId());
        });

        gate.countDown();
        Execution exec1 = awaitCompletion(user1.get(5, TimeUnit.SECONDS).getExecutionId(), Duration.ofSeconds(10));
        Execution exec2 = awaitCompletion(user2.get(5, TimeUnit.SECONDS).getExecutionId(), Duration.ofSeconds(10));
        pool.shutdown();

        assertEquals(ExecutionStatus.SUCCESS, exec1.getStatus());
        assertEquals(ExecutionStatus.SUCCESS, exec2.getStatus());

        List<Execution> history = workflowExecutionService.getExecutionsByWorkflowId(timedWorkflow.getWorkflowId());
        assertEquals(2, history.size());

        // Ensure no overlap between the two runs
        Execution first = exec1.getStartedAt().isBefore(exec2.getStartedAt()) ? exec1 : exec2;
        Execution second = first == exec1 ? exec2 : exec1;
        assertFalse(second.getStartedAt().isBefore(first.getCompletedAt()));
    }

    private void awaitCondition(BooleanSupplier condition, Duration timeout, String description) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(30);
        }
        fail("Timed out waiting for condition: " + description);
    }

    @Test
    @DisplayName("6. Check workflow and step statuses after each individual step completes")
    void shouldCheckStatusAfterEachStepCompletes(@TempDir Path tempDir) throws InterruptedException, IOException {
        Path gate2 = tempDir.resolve("release_step2");
        Path gate3 = tempDir.resolve("release_step3");
        Path gate4 = tempDir.resolve("release_step4");

        // Step 1 finishes immediately; steps 2, 3, 4 pause on gate files so we can inspect DB status after each step
        Workflow controlledWorkflow = workflowExecutionService.createWorkflow(
                "step-by-step-status-workflow",
                List.of(
                        createStep("building code", "echo 'step1-ok'", 15, null),
                        createStep("running tests",
                                "while [ ! -f '" + gate2 + "' ]; do sleep 0.05; done; echo 'step2-ok'", 15, null),
                        createStep("validating changes",
                                "while [ ! -f '" + gate3 + "' ]; do sleep 0.05; done; echo 'step3-ok'", 15, null),
                        createStep("performing deployments",
                                "while [ ! -f '" + gate4 + "' ]; do sleep 0.05; done; echo 'step4-ok'", 15, null)
                )
        );

        Execution triggered = workflowExecutionService.triggerExecution(controlledWorkflow.getWorkflowId());
        String executionId = triggered.getExecutionId();

        // After Step 1 completes (Step 2 is now RUNNING and paused on gate2)
        awaitCondition(() -> {
            List<StepExecution> logs = workflowExecutionService.getExecutionLogs(executionId);
            return logs.get(0).getStatus() == StepExecutionStatus.SUCCESS
                    && logs.get(1).getStatus() == StepExecutionStatus.RUNNING;
        }, Duration.ofSeconds(10), "Step 1 SUCCESS and Step 2 RUNNING");

        assertEquals(ExecutionStatus.RUNNING, workflowExecutionService.getExecutionStatus(executionId).getStatus());
        List<StepExecution> afterStep1 = workflowExecutionService.getExecutionLogs(executionId);
        assertEquals(StepExecutionStatus.SUCCESS, afterStep1.get(0).getStatus());
        assertEquals(StepExecutionStatus.RUNNING, afterStep1.get(1).getStatus());
        assertEquals(StepExecutionStatus.PENDING, afterStep1.get(2).getStatus());
        assertEquals(StepExecutionStatus.PENDING, afterStep1.get(3).getStatus());

        // Release Step 2 -> wait until Step 2 completes and Step 3 is RUNNING
        Files.createFile(gate2);
        awaitCondition(() -> {
            List<StepExecution> logs = workflowExecutionService.getExecutionLogs(executionId);
            return logs.get(1).getStatus() == StepExecutionStatus.SUCCESS
                    && logs.get(2).getStatus() == StepExecutionStatus.RUNNING;
        }, Duration.ofSeconds(10), "Step 2 SUCCESS and Step 3 RUNNING");

        assertEquals(ExecutionStatus.RUNNING, workflowExecutionService.getExecutionStatus(executionId).getStatus());
        List<StepExecution> afterStep2 = workflowExecutionService.getExecutionLogs(executionId);
        assertEquals(StepExecutionStatus.SUCCESS, afterStep2.get(0).getStatus());
        assertEquals(StepExecutionStatus.SUCCESS, afterStep2.get(1).getStatus());
        assertEquals(StepExecutionStatus.RUNNING, afterStep2.get(2).getStatus());
        assertEquals(StepExecutionStatus.PENDING, afterStep2.get(3).getStatus());

        // Release Step 3 -> wait until Step 3 completes and Step 4 is RUNNING
        Files.createFile(gate3);
        awaitCondition(() -> {
            List<StepExecution> logs = workflowExecutionService.getExecutionLogs(executionId);
            return logs.get(2).getStatus() == StepExecutionStatus.SUCCESS
                    && logs.get(3).getStatus() == StepExecutionStatus.RUNNING;
        }, Duration.ofSeconds(10), "Step 3 SUCCESS and Step 4 RUNNING");

        assertEquals(ExecutionStatus.RUNNING, workflowExecutionService.getExecutionStatus(executionId).getStatus());
        List<StepExecution> afterStep3 = workflowExecutionService.getExecutionLogs(executionId);
        assertEquals(StepExecutionStatus.SUCCESS, afterStep3.get(0).getStatus());
        assertEquals(StepExecutionStatus.SUCCESS, afterStep3.get(1).getStatus());
        assertEquals(StepExecutionStatus.SUCCESS, afterStep3.get(2).getStatus());
        assertEquals(StepExecutionStatus.RUNNING, afterStep3.get(3).getStatus());

        // Release Step 4 -> wait until Execution and all 4 steps complete
        Files.createFile(gate4);
        Execution completed = awaitCompletion(executionId, Duration.ofSeconds(10));
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());

        List<StepExecution> afterStep4 = workflowExecutionService.getExecutionLogs(executionId);
        for (StepExecution stepExec : afterStep4) {
            assertEquals(StepExecutionStatus.SUCCESS, stepExec.getStatus());
        }
    }

    @Test
    @DisplayName("7. Validate logs immediately after 3 successful steps (and verify persistence if step 4 fails)")
    void shouldValidateLogsAfterThreeSuccessfulSteps(@TempDir Path tempDir) throws InterruptedException, IOException {
        Path gate4 = tempDir.resolve("release_step4");

        Workflow threePassWorkflow = workflowExecutionService.createWorkflow(
                "validate-logs-after-3-steps",
                List.of(
                        createStep("building code", "echo '[BUILD] Compiled 24 classes' && echo '[BUILD] Jar packaged'", 15, null),
                        createStep("running tests", "echo '[TEST] Suite: integration' && echo '[TEST] 18/18 passed'", 15, null),
                        createStep("validating changes", "echo '[VALIDATE] Checkstyle 0 warnings' && echo '[VALIDATE] Schema OK'", 15, null),
                        createStep("performing deployments",
                                "while [ ! -f '" + gate4 + "' ]; do sleep 0.05; done; echo '[DEPLOY] Rollout failed' && exit 1", 15, null)
                )
        );

        Execution triggered = workflowExecutionService.triggerExecution(threePassWorkflow.getWorkflowId());
        String executionId = triggered.getExecutionId();

        // Wait until first 3 steps have succeeded and Step 4 is RUNNING
        awaitCondition(() -> {
            List<StepExecution> logs = workflowExecutionService.getExecutionLogs(executionId);
            return logs.get(2).getStatus() == StepExecutionStatus.SUCCESS
                    && logs.get(3).getStatus() == StepExecutionStatus.RUNNING;
        }, Duration.ofSeconds(10), "3 successful steps completed");

        // Validate logs immediately after the 3 successful steps
        List<StepExecution> logsAfter3Steps = workflowExecutionService.getExecutionLogs(executionId);
        assertEquals(4, logsAfter3Steps.size());

        // Step 1 logs
        assertEquals("building code", logsAfter3Steps.get(0).getStep().getName());
        assertEquals(StepExecutionStatus.SUCCESS, logsAfter3Steps.get(0).getStatus());
        assertEquals(0, logsAfter3Steps.get(0).getExitCode());
        assertTrue(logsAfter3Steps.get(0).getOutput().contains("[BUILD] Compiled 24 classes"));
        assertTrue(logsAfter3Steps.get(0).getOutput().contains("[BUILD] Jar packaged"));

        // Step 2 logs
        assertEquals("running tests", logsAfter3Steps.get(1).getStep().getName());
        assertEquals(StepExecutionStatus.SUCCESS, logsAfter3Steps.get(1).getStatus());
        assertEquals(0, logsAfter3Steps.get(1).getExitCode());
        assertTrue(logsAfter3Steps.get(1).getOutput().contains("[TEST] Suite: integration"));
        assertTrue(logsAfter3Steps.get(1).getOutput().contains("[TEST] 18/18 passed"));

        // Step 3 logs
        assertEquals("validating changes", logsAfter3Steps.get(2).getStep().getName());
        assertEquals(StepExecutionStatus.SUCCESS, logsAfter3Steps.get(2).getStatus());
        assertEquals(0, logsAfter3Steps.get(2).getExitCode());
        assertTrue(logsAfter3Steps.get(2).getOutput().contains("[VALIDATE] Checkstyle 0 warnings"));
        assertTrue(logsAfter3Steps.get(2).getOutput().contains("[VALIDATE] Schema OK"));

        // Step 4 is still running
        assertEquals(StepExecutionStatus.RUNNING, logsAfter3Steps.get(3).getStatus());

        // Release Step 4 to fail and verify logs of the 3 successful steps remain intact alongside Step 4's failure log
        Files.createFile(gate4);
        Execution completed = awaitCompletion(executionId, Duration.ofSeconds(10));
        assertEquals(ExecutionStatus.FAILED, completed.getStatus());

        List<StepExecution> finalLogs = workflowExecutionService.getExecutionLogs(executionId);
        assertEquals(StepExecutionStatus.SUCCESS, finalLogs.get(0).getStatus());
        assertEquals(StepExecutionStatus.SUCCESS, finalLogs.get(1).getStatus());
        assertEquals(StepExecutionStatus.SUCCESS, finalLogs.get(2).getStatus());
        assertEquals(StepExecutionStatus.FAILED, finalLogs.get(3).getStatus());
        assertEquals(1, finalLogs.get(3).getExitCode());
        assertTrue(finalLogs.get(3).getOutput().contains("[DEPLOY] Rollout failed"));
    }
}
