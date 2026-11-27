package com.example.githubActions.repository;

import com.example.githubActions.models.Workflow;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    @EntityGraph(attributePaths = {"steps"})
    @Override
    Optional<Workflow> findById(String id);

    @EntityGraph(attributePaths = {"steps"})
    @Override
    List<Workflow> findAll();

    Optional<Workflow> findByName(String name);

    boolean existsByName(String name);
}
