package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SecurityScanRepository extends JpaRepository<SecurityScan, String> {
    List<SecurityScan> findTop200ByDockerHostIdOrderByStartedAtDesc(String dockerHostId);

    Optional<SecurityScan> findFirstByDockerHostIdAndKindAndTargetOrderByStartedAtDesc(
            String dockerHostId, String kind, String target);

    List<SecurityScan> findByStatus(String status);

    long deleteByStartedAtLessThan(long before);
}
