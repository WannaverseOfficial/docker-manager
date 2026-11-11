package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatabaseServerRepository extends JpaRepository<DatabaseServer, String> {
    List<DatabaseServer> findByDockerHostIdOrderByNameAsc(String dockerHostId);

    List<DatabaseServer> findByBackupIntervalHoursGreaterThan(int hours);

    boolean existsByDockerHostIdAndName(String dockerHostId, String name);
}
