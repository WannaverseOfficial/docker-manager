package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GitRepositoryRepository extends JpaRepository<GitRepository, String> {
    List<GitRepository> findByDockerHostId(String dockerHostId);

    List<GitRepository> findByPollingEnabledTrue();

    Optional<GitRepository> findByWebhookSecret(String webhookSecret);

    Optional<GitRepository> findByName(String name);
}
