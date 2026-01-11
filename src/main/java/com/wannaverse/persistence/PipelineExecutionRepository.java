package com.wannaverse.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PipelineExecutionRepository extends JpaRepository<PipelineExecution, String> {

    List<PipelineExecution> findByPipelineIdOrderByCreatedAtDesc(String pipelineId);

    Page<PipelineExecution> findByPipelineIdOrderByCreatedAtDesc(
            String pipelineId, Pageable pageable);

    List<PipelineExecution> findByStatusOrderByCreatedAtDesc(
            PipelineExecution.ExecutionStatus status);

    List<PipelineExecution> findByPipelineIdAndStatusOrderByCreatedAtDesc(
            String pipelineId, PipelineExecution.ExecutionStatus status);

    @Query("SELECT MAX(e.buildNumber) FROM PipelineExecution e WHERE e.pipeline.id = :pipelineId")
    Optional<Integer> findMaxBuildNumberByPipelineId(@Param("pipelineId") String pipelineId);

    @Query("SELECT e FROM PipelineExecution e WHERE e.status = 'RUNNING'")
    List<PipelineExecution> findRunningExecutions();

    @Query(
            "SELECT COUNT(e) FROM PipelineExecution e WHERE e.pipeline.id = :pipelineId AND"
                    + " e.status = :status")
    long countByPipelineIdAndStatus(
            @Param("pipelineId") String pipelineId,
            @Param("status") PipelineExecution.ExecutionStatus status);

    List<PipelineExecution> findTop10ByPipelineIdOrderByCreatedAtDesc(String pipelineId);
}
