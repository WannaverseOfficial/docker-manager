package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DnsRecordRepository extends JpaRepository<DnsRecord, String> {
    List<DnsRecord> findByZoneId(String zoneId);

    List<DnsRecord> findByHealthCheckId(String healthCheckId);

    @Transactional
    void deleteByZoneId(String zoneId);
}
