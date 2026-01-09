package com.wannaverse.persistence;

import com.wannaverse.persistence.IngressAuditLog.IngressAction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IngressAuditLogRepository extends JpaRepository<IngressAuditLog, String> {
    List<IngressAuditLog> findByIngressConfigIdOrderByTimestampDesc(String ingressConfigId);

    Page<IngressAuditLog> findByIngressConfigIdOrderByTimestampDesc(
            String ingressConfigId, Pageable pageable);

    List<IngressAuditLog> findByIngressConfigIdAndActionOrderByTimestampDesc(
            String ingressConfigId, IngressAction action);

    List<IngressAuditLog> findByResourceTypeAndResourceIdOrderByTimestampDesc(
            String resourceType, String resourceId);

    List<IngressAuditLog> findByIngressConfigIdAndSuccessOrderByTimestampDesc(
            String ingressConfigId, boolean success);
}
