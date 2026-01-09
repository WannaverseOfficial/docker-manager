package com.wannaverse.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DockerOperationRepository extends JpaRepository<DockerOperation, String> {
    Page<DockerOperation> findByDockerHostIdOrderByCreatedAtDesc(
            String dockerHostId, Pageable pageable);

    Page<DockerOperation> findByDockerHostIdAndOperationTypeOrderByCreatedAtDesc(
            String dockerHostId, DockerOperation.OperationType operationType, Pageable pageable);

    Page<DockerOperation> findByDockerHostIdAndStatusOrderByCreatedAtDesc(
            String dockerHostId, DockerOperation.OperationStatus status, Pageable pageable);

    List<DockerOperation> findByDockerHostIdAndResourceIdOrderByCreatedAtDesc(
            String dockerHostId, String resourceId);

    List<DockerOperation> findByDeploymentJobIdOrderByCreatedAtDesc(String deploymentJobId);

    @Query(
            "SELECT o FROM DockerOperation o WHERE o.dockerHost.id = :hostId "
                    + "AND (:type IS NULL OR o.operationType = :type) "
                    + "AND (:status IS NULL OR o.status = :status) "
                    + "ORDER BY o.createdAt DESC")
    Page<DockerOperation> findByFilters(
            @Param("hostId") String hostId,
            @Param("type") DockerOperation.OperationType type,
            @Param("status") DockerOperation.OperationStatus status,
            Pageable pageable);

    @Query("SELECT o FROM DockerOperation o WHERE o.createdAt < :cutoff")
    List<DockerOperation> findOlderThan(@Param("cutoff") long cutoffTimestamp);

    long countByDockerHostId(String dockerHostId);
}
