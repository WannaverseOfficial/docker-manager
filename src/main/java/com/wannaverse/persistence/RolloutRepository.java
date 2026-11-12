package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RolloutRepository extends JpaRepository<Rollout, String> {
    List<Rollout> findByDockerHostIdOrderByNameAsc(String dockerHostId);

    List<Rollout> findAllByOrderByNameAsc();

    Optional<Rollout> findByRouteId(String routeId);

    boolean existsByDockerHostIdAndContainerName(String dockerHostId, String containerName);
}
