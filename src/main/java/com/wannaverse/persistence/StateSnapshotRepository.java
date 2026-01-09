package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StateSnapshotRepository extends JpaRepository<StateSnapshot, String> {
    List<StateSnapshot> findByOperationIdOrderByCreatedAtAsc(String operationId);

    Optional<StateSnapshot> findByOperationIdAndSnapshotType(
            String operationId, StateSnapshot.SnapshotType snapshotType);

    List<StateSnapshot> findByResourceIdOrderByCreatedAtDesc(String resourceId);

    @Query(
            "SELECT s FROM StateSnapshot s WHERE s.operation.dockerHost.id = :hostId "
                    + "AND s.resourceId = :resourceId "
                    + "AND s.snapshotType = :type "
                    + "ORDER BY s.createdAt DESC")
    List<StateSnapshot> findByHostAndResourceAndType(
            @Param("hostId") String hostId,
            @Param("resourceId") String resourceId,
            @Param("type") StateSnapshot.SnapshotType type);

    @Query("SELECT s FROM StateSnapshot s WHERE s.createdAt < :cutoff")
    List<StateSnapshot> findOlderThan(@Param("cutoff") long cutoffTimestamp);

    void deleteByOperationId(String operationId);
}
