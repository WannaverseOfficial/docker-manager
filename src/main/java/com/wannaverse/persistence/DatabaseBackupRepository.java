package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatabaseBackupRepository extends JpaRepository<DatabaseBackup, String> {
    List<DatabaseBackup> findByServerIdOrderByCreatedAtDesc(String serverId);

    List<DatabaseBackup> findByServerIdAndOriginAndStatusOrderByCreatedAtDesc(
            String serverId, String origin, String status);

    List<DatabaseBackup> findByStatus(String status);

    List<DatabaseBackup> findByDockerHostIdAndServerName(String dockerHostId, String serverName);
}
