package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepExecutionRepository extends JpaRepository<StepExecution, String> {

    List<StepExecution> findByStageExecutionIdOrderByOrderIndexAsc(String stageExecutionId);

    List<StepExecution> findByStatusOrderByStartedAtDesc(PipelineExecution.ExecutionStatus status);
}
