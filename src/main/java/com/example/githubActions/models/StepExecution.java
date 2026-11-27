package com.example.githubActions.models;

import com.example.githubActions.models.enums.StepExecutionStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "step_execution")
public class StepExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "step_execution_id")
    private String stepExecutionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private Execution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", nullable = false)
    private Step step;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StepExecutionStatus status = StepExecutionStatus.PENDING;

    @Column(name = "output", columnDefinition = "TEXT")
    private String output; // captured stdout/stderr

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public StepExecution() {
    }

    public StepExecution(Execution execution, Step step) {
        this.execution = execution;
        this.step = step;
        this.status = StepExecutionStatus.PENDING;
    }

    // --- Getters and Setters ---

    public String getStepExecutionId() {
        return stepExecutionId;
    }

    public void setStepExecutionId(String stepExecutionId) {
        this.stepExecutionId = stepExecutionId;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }

    public Step getStep() {
        return step;
    }

    public void setStep(Step step) {
        this.step = step;
    }

    public StepExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(StepExecutionStatus status) {
        this.status = status;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
