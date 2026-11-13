package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface DnsHealthCheckRepository extends JpaRepository<DnsHealthCheck, String> {
    Optional<DnsHealthCheck> findByName(String name);

    @Transactional
    @Modifying
    @Query(
            "update DnsHealthCheck c set c.status = :status, c.lastResult = :result,"
                + " c.lastCheckedAt = :checkedAt, c.lastChangedAt = :changedAt where c.id = :id")
    void updateResult(
            @Param("id") String id,
            @Param("status") String status,
            @Param("result") String result,
            @Param("checkedAt") Long checkedAt,
            @Param("changedAt") Long changedAt);
}
