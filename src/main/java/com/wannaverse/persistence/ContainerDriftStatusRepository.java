package com.wannaverse.persistence;

import com.wannaverse.persistence.GitRepository.DriftStatus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContainerDriftStatusRepository
        extends JpaRepository<ContainerDriftStatus, String> {
    List<ContainerDriftStatus> findByDockerHostId(String dockerHostId);

    Optional<ContainerDriftStatus> findByDockerHostIdAndContainerId(
            String dockerHostId, String containerId);

    List<ContainerDriftStatus> findByConfigDriftStatus(DriftStatus status);

    void deleteByDockerHostIdAndContainerId(String dockerHostId, String containerId);

    void deleteByDockerHostId(String dockerHostId);
}
