package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ObservabilityStackRepository extends JpaRepository<ObservabilityStack, String> {
    Optional<ObservabilityStack> findByDockerHostId(String dockerHostId);
}
