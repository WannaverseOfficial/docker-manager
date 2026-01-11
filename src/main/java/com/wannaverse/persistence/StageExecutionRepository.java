package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StageExecutionRepository extends JpaRepository<StageExecution, String> {

    List<StageExecution> findByExecutionIdOrderByOrderIndexAsc(String executionId);

    List<StageExecution> findByStatusOrderByStartedAtDesc(PipelineExecution.ExecutionStatus status);
}
