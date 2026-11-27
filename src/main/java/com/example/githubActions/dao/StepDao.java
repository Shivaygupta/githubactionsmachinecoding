package com.example.githubActions.dao;

import com.example.githubActions.models.Step;
import com.example.githubActions.repository.StepRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class StepDao {

    private final StepRepository stepRepository;

    public StepDao(StepRepository stepRepository) {
        this.stepRepository = stepRepository;
    }

    /**
     * Persist a single step.
     */
    public Step save(Step step) {
        return stepRepository.save(step);
    }

    /**
     * Persist multiple steps in one go (batch save).
     */
    public List<Step> saveAll(List<Step> steps) {
        return stepRepository.saveAll(steps);
    }

    /**
     * Find a step by its ID.
     */
    public Optional<Step> findById(String stepId) {
        return stepRepository.findById(stepId);
    }

    /**
     * Find all steps belonging to a workflow, ordered by stepOrder ascending.
     * This is the execution order.
     */
    public List<Step> findByWorkflowIdOrdered(String workflowId) {
        return stepRepository.findByWorkflowWorkflowIdOrderByStepOrderAsc(workflowId);
    }

    /**
     * Delete a step by its ID.
     */
    public void deleteById(String stepId) {
        stepRepository.deleteById(stepId);
    }
}
