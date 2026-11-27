package com.example.githubActions.models;

import jakarta.persistence.*;


@Entity
@Table(name = "step", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"workflow_id", "step_order"})
})
public class Step {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "step_id")
    private String stepId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "command", nullable = false)
    private String command;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 30;

    @Column(name = "env_vars", columnDefinition = "TEXT")
    private String envVars; // JSON string: {"KEY": "VALUE", ...}

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    public Step() {
    }

    public Step(String name, String command, Integer stepOrder) {
        this.name = name;
        this.command = command;
        this.stepOrder = stepOrder;
    }

    // --- Getters and Setters ---

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public void setWorkflow(Workflow workflow) {
        this.workflow = workflow;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars;
    }

    public Integer getStepOrder() {
        return stepOrder;
    }

    public void setStepOrder(Integer stepOrder) {
        this.stepOrder = stepOrder;
    }
}
