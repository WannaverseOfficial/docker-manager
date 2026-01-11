package com.wannaverse.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContainerHealthEventRepository
        extends JpaRepository<ContainerHealthEvent, String> {

    List<ContainerHealthEvent> findByResolvedAtIsNullOrderByDetectedAtDesc();

    List<ContainerHealthEvent> findByDockerHostIdAndResolvedAtIsNullOrderByDetectedAtDesc(
            String dockerHostId);

    Optional<ContainerHealthEvent> findByDockerHostIdAndContainerIdAndEventTypeAndResolvedAtIsNull(
            String dockerHostId, String containerId, ContainerHealthEvent.EventType eventType);

    Page<ContainerHealthEvent> findByDockerHostIdOrderByDetectedAtDesc(
            String dockerHostId, Pageable pageable);

    Page<ContainerHealthEvent> findByDockerHostIdAndEventTypeOrderByDetectedAtDesc(
            String dockerHostId, ContainerHealthEvent.EventType eventType, Pageable pageable);

    List<ContainerHealthEvent> findByDockerHostIdAndContainerIdOrderByDetectedAtDesc(
            String dockerHostId, String containerId);

    long countByResolvedAtIsNull();

    long countByDockerHostIdAndResolvedAtIsNull(String dockerHostId);

    @Query(
            "SELECT e.eventType, COUNT(e) FROM ContainerHealthEvent e "
                    + "WHERE e.dockerHost.id = :hostId AND e.detectedAt > :since "
                    + "GROUP BY e.eventType")
    List<Object[]> countByEventTypeSince(
            @Param("hostId") String hostId, @Param("since") long since);

    @Query(
            "SELECT e FROM ContainerHealthEvent e "
                    + "WHERE e.dockerHost.id = :hostId "
                    + "AND e.containerId = :containerId "
                    + "AND e.eventType = :eventType "
                    + "AND e.detectedAt > :since "
                    + "ORDER BY e.detectedAt DESC")
    List<ContainerHealthEvent> findRecentEvents(
            @Param("hostId") String hostId,
            @Param("containerId") String containerId,
            @Param("eventType") ContainerHealthEvent.EventType eventType,
            @Param("since") long since);
}
