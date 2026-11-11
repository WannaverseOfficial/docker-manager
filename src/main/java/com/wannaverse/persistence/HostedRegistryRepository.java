package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HostedRegistryRepository extends JpaRepository<HostedRegistry, String> {
    Optional<HostedRegistry> findByDockerHostId(String dockerHostId);
}
