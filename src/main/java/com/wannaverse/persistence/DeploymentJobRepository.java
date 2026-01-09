package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeploymentJobRepository extends JpaRepository<DeploymentJob, String> {
    List<DeploymentJob> findByGitRepositoryIdOrderByCreatedAtDesc(String gitRepositoryId);

    List<DeploymentJob> findByStatusOrderByCreatedAtDesc(DeploymentJob.JobStatus status);

    List<DeploymentJob> findAllByOrderByCreatedAtDesc();

    List<DeploymentJob> findByGitRepositoryIdAndStatus(
            String gitRepositoryId, DeploymentJob.JobStatus status);
}
