package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ContainerMonitorStateRepository
        extends JpaRepository<ContainerMonitorState, String> {

    Optional<ContainerMonitorState> findByDockerHostIdAndContainerId(
            String dockerHostId, String containerId);

    List<ContainerMonitorState> findByDockerHostId(String dockerHostId);

    @Modifying
    @Transactional
    @Query(
            "DELETE FROM ContainerMonitorState s "
                    + "WHERE s.dockerHost.id = :hostId "
                    + "AND s.containerId NOT IN :activeContainerIds")
    void deleteStaleEntries(
            @Param("hostId") String hostId,
            @Param("activeContainerIds") List<String> activeContainerIds);

    @Transactional
    void deleteByDockerHostId(String dockerHostId);
}
