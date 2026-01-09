package com.wannaverse.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ComposeDeploymentRepository extends JpaRepository<ComposeDeployment, String> {
    Page<ComposeDeployment> findByDockerHostIdOrderByCreatedAtDesc(
            String dockerHostId, Pageable pageable);

    List<ComposeDeployment> findByDockerHostIdAndProjectNameOrderByVersionDesc(
            String dockerHostId, String projectName);

    Optional<ComposeDeployment> findByDockerHostIdAndProjectNameAndVersion(
            String dockerHostId, String projectName, int version);

    @Query(
            "SELECT cd FROM ComposeDeployment cd WHERE cd.dockerHost.id = :hostId "
                    + "AND cd.projectName = :projectName "
                    + "AND cd.status = 'ACTIVE' "
                    + "ORDER BY cd.version DESC")
    Optional<ComposeDeployment> findActiveDeployment(
            @Param("hostId") String hostId, @Param("projectName") String projectName);

    @Query(
            "SELECT MAX(cd.version) FROM ComposeDeployment cd "
                    + "WHERE cd.dockerHost.id = :hostId AND cd.projectName = :projectName")
    Integer findLatestVersion(
            @Param("hostId") String hostId, @Param("projectName") String projectName);

    @Query(
            "SELECT DISTINCT cd.projectName FROM ComposeDeployment cd "
                    + "WHERE cd.dockerHost.id = :hostId ORDER BY cd.projectName")
    List<String> findDistinctProjectNames(@Param("hostId") String hostId);

    Page<ComposeDeployment> findByStatusOrderByCreatedAtDesc(
            ComposeDeployment.DeploymentStatus status, Pageable pageable);

    long countByDockerHostId(String dockerHostId);
}
