package com.example.githubActions.exceptions;

public class ExecutionNotFoundException extends RuntimeException {

    public ExecutionNotFoundException(String executionId) {
        super("Execution not found with id: " + executionId);
    }
}
