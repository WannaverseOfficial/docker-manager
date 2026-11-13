package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface DnsZoneRepository extends JpaRepository<DnsZone, String> {
    Optional<DnsZone> findByName(String name);

    @Transactional
    @Modifying
    @Query("update DnsZone z set z.serial = :serial, z.contentHash = :hash where z.id = :id")
    void updateSerial(
            @Param("id") String id, @Param("serial") long serial, @Param("hash") String hash);

    @Transactional
    @Modifying
    @Query(
            "update DnsZone z set z.delegationStatus = :status, z.delegationDetail = :detail,"
                    + " z.delegationCheckedAt = :at where z.id = :id")
    void updateDelegation(
            @Param("id") String id,
            @Param("status") String status,
            @Param("detail") String detail,
            @Param("at") Long at);
}
