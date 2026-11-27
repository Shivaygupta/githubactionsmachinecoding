package com.example.githubActions.repository;

import com.example.githubActions.models.Step;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StepRepository extends JpaRepository<Step, String> {

    List<Step> findByWorkflowWorkflowIdOrderByStepOrderAsc(String workflowId);
}
