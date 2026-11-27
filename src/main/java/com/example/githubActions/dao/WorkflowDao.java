package com.example.githubActions.dao;

import com.example.githubActions.models.Workflow;
import com.example.githubActions.repository.WorkflowRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class WorkflowDao {

    private final WorkflowRepository workflowRepository;

    public WorkflowDao(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    /**
     * Persist a new workflow. Sets createdAt if not already set.
     */
    public Workflow save(Workflow workflow) {
        if (workflow.getCreatedAt() == null) {
            workflow.setCreatedAt(LocalDateTime.now());
        }
        return workflowRepository.save(workflow);
    }

    /**
     * Find a workflow by its ID.
     */
    public Optional<Workflow> findById(String workflowId) {
        return workflowRepository.findById(workflowId);
    }

    /**
     * Find a workflow by its unique name.
     */
    public Optional<Workflow> findByName(String name) {
        return workflowRepository.findByName(name);
    }

    /**
     * Check if a workflow with the given name already exists.
     */
    public boolean existsByName(String name) {
        return workflowRepository.existsByName(name);
    }

    /**
     * List all workflows.
     */
    public List<Workflow> findAll() {
        return workflowRepository.findAll();
    }

    /**
     * Delete a workflow by its ID.
     */
    public void deleteById(String workflowId) {
        workflowRepository.deleteById(workflowId);
    }
}
