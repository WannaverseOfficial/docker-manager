package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PipelineArtifactRepository extends JpaRepository<PipelineArtifact, String> {

    List<PipelineArtifact> findByExecutionIdOrderByCreatedAtDesc(String executionId);

    List<PipelineArtifact> findByStepExecutionIdOrderByCreatedAtDesc(String stepExecutionId);

    @Query("SELECT a FROM PipelineArtifact a WHERE a.expiresAt IS NOT NULL AND a.expiresAt < :now")
    List<PipelineArtifact> findExpiredArtifacts(@Param("now") long now);

    @Query("SELECT SUM(a.sizeBytes) FROM PipelineArtifact a WHERE a.execution.id = :executionId")
    Long getTotalSizeByExecutionId(@Param("executionId") String executionId);
}
