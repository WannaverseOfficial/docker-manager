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

    // Find active (unresolved) events
    List<ContainerHealthEvent> findByResolvedAtIsNullOrderByDetectedAtDesc();

    // Find active events for a specific host
    List<ContainerHealthEvent> findByDockerHostIdAndResolvedAtIsNullOrderByDetectedAtDesc(
            String dockerHostId);

    // Find active event for a specific container and type
    Optional<ContainerHealthEvent> findByDockerHostIdAndContainerIdAndEventTypeAndResolvedAtIsNull(
            String dockerHostId, String containerId, ContainerHealthEvent.EventType eventType);

    // Find all events for a host (paginated)
    Page<ContainerHealthEvent> findByDockerHostIdOrderByDetectedAtDesc(
            String dockerHostId, Pageable pageable);

    // Find events by type for a host
    Page<ContainerHealthEvent> findByDockerHostIdAndEventTypeOrderByDetectedAtDesc(
            String dockerHostId, ContainerHealthEvent.EventType eventType, Pageable pageable);

    // Find container history
    List<ContainerHealthEvent> findByDockerHostIdAndContainerIdOrderByDetectedAtDesc(
            String dockerHostId, String containerId);

    // Count active events
    long countByResolvedAtIsNull();

    long countByDockerHostIdAndResolvedAtIsNull(String dockerHostId);

    // Count by type for stats
    @Query(
            "SELECT e.eventType, COUNT(e) FROM ContainerHealthEvent e "
                    + "WHERE e.dockerHost.id = :hostId AND e.detectedAt > :since "
                    + "GROUP BY e.eventType")
    List<Object[]> countByEventTypeSince(
            @Param("hostId") String hostId, @Param("since") long since);

    // Find recent events for a container (for deduplication)
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
