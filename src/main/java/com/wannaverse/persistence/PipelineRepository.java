package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PipelineRepository extends JpaRepository<Pipeline, String> {

    List<Pipeline> findByEnabledOrderByCreatedAtDesc(boolean enabled);

    List<Pipeline> findAllByOrderByCreatedAtDesc();

    List<Pipeline> findByDockerHostIdOrderByCreatedAtDesc(String dockerHostId);

    Optional<Pipeline> findByWebhookSecret(String webhookSecret);

    List<Pipeline> findByGitRepositoryIdOrderByCreatedAtDesc(String gitRepositoryId);

    @Query("SELECT p FROM Pipeline p WHERE p.pollingEnabled = true AND p.enabled = true")
    List<Pipeline> findEnabledPollingPipelines();

    @Query(
            "SELECT p FROM Pipeline p WHERE p.webhookEnabled = true AND p.enabled = true AND"
                    + " p.gitRepository.id = :gitRepoId")
    List<Pipeline> findWebhookEnabledPipelinesByGitRepo(@Param("gitRepoId") String gitRepositoryId);

    boolean existsByName(String name);
}
