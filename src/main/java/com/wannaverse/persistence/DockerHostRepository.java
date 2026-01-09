package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DockerHostRepository extends JpaRepository<DockerHost, String> {
    Optional<DockerHost> findByDockerHostUrl(String dockerHostUrl);

    List<DockerHost> getAllByDockerHostUrlNotNull();

    Optional<DockerHost> findById(String id);
}
