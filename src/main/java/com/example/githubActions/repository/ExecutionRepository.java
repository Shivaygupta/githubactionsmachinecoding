package com.example.githubActions.repository;

import com.example.githubActions.models.Execution;
import com.example.githubActions.models.enums.ExecutionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExecutionRepository extends JpaRepository<Execution, String> {

    @EntityGraph(attributePaths = {"workflow"})
    @Override
    Optional<Execution> findById(String id);

    @EntityGraph(attributePaths = {"workflow"})
    List<Execution> findByWorkflowWorkflowId(String workflowId);

    @EntityGraph(attributePaths = {"workflow"})
    List<Execution> findByStatus(ExecutionStatus status);

    @EntityGraph(attributePaths = {"workflow"})
    List<Execution> findByWorkflowWorkflowIdAndStatus(String workflowId, ExecutionStatus status);
}
