package com.example.githubActions.repository;

import com.example.githubActions.models.StepExecution;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StepExecutionRepository extends JpaRepository<StepExecution, String> {

    @EntityGraph(attributePaths = {"step"})
    List<StepExecution> findByExecutionExecutionId(String executionId);

    @EntityGraph(attributePaths = {"step"})
    List<StepExecution> findByExecutionExecutionIdOrderByStepStepOrderAsc(String executionId);
}
