package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GitRepositoryRepository extends JpaRepository<GitRepository, String> {
    List<GitRepository> findByDockerHostId(String dockerHostId);

    List<GitRepository> findByPollingEnabledTrue();

    Optional<GitRepository> findByWebhookSecret(String webhookSecret);

    Optional<GitRepository> findByName(String name);

    List<GitRepository> findByRolloutId(String rolloutId);

    /** Loads the repository with its host, for use outside a web request (no lazy loading). */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "dockerHost")
    @org.springframework.data.jpa.repository.Query("SELECT r FROM GitRepository r WHERE r.id = :id")
    Optional<GitRepository> findWithHostById(
            @org.springframework.data.repository.query.Param("id") String id);
}
