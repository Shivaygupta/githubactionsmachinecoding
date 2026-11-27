package com.example.githubActions.service;

import com.example.githubActions.models.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Default StepExecutor that runs shell commands as OS processes.
 * Supports timeout enforcement and environment variable injection.
 *
 * To add new step types in the future (e.g., DockerStepExecutor,
 * HttpStepExecutor), implement the StepExecutor interface.
 */
@Component
public class CommandStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandStepExecutor.class);
    private static final String TYPE = "COMMAND";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public StepResult execute(Step step) {
        log.info("Executing step '{}': {}", step.getName(), step.getCommand());

        try {
            ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", step.getCommand());
            processBuilder.redirectErrorStream(true); // merge stderr into stdout

            // Inject environment variables if present
            if (step.getEnvVars() != null && !step.getEnvVars().isBlank()) {
                Map<String, String> envVars = objectMapper.readValue(
                        step.getEnvVars(), new TypeReference<Map<String, String>>() {});
                processBuilder.environment().putAll(envVars);
            }

            Process process = processBuilder.start();

            // Capture output asynchronously so reading doesn't block timeout enforcement
            StringBuilder output = new StringBuilder();
            Thread outputReaderThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append("\n");
                        }
                    }
                } catch (Exception ignored) {
                    // Stream closed when process is forcibly terminated
                }
            }, "step-output-reader");
            outputReaderThread.start();

            // Enforce timeout while output is being drained concurrently
            int timeoutSeconds = step.getTimeoutSeconds() != null ? step.getTimeoutSeconds() : 30;
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                // Kill child/descendant processes (e.g., sleep spawned by sh -c) then the shell
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                outputReaderThread.join(1000);
                String timeoutMsg = "Step '" + step.getName() + "' timed out after " + timeoutSeconds + "s";
                log.warn(timeoutMsg);
                synchronized (output) {
                    return new StepResult(false, output + "\n" + timeoutMsg, -1);
                }
            }

            outputReaderThread.join(2000);
            int exitCode = process.exitValue();
            boolean success = (exitCode == 0);

            log.info("Step '{}' completed with exit code {}", step.getName(), exitCode);
            synchronized (output) {
                return new StepResult(success, output.toString(), exitCode);
            }

        } catch (Exception e) {
            String errorMsg = "Step '" + step.getName() + "' failed with exception: " + e.getMessage();
            log.error(errorMsg, e);
            return new StepResult(false, errorMsg, -1);
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
