package com.example.githubActions.exceptions;

public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(String workflowId) {
        super("Workflow not found with id: " + workflowId);
    }
}
