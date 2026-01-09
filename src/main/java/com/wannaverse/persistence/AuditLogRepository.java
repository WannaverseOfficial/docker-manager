package com.wannaverse.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository
        extends JpaRepository<AuditLog, String>, JpaSpecificationExecutor<AuditLog> {

    Page<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    Page<AuditLog> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    Page<AuditLog> findByActionOrderByTimestampDesc(String action, Pageable pageable);

    Page<AuditLog> findByResourceTypeOrderByTimestampDesc(Resource resourceType, Pageable pageable);

    @Query(
            "SELECT a FROM AuditLog a WHERE (:userId IS NULL OR a.userId = :userId) AND (:action IS"
                + " NULL OR a.action = :action) AND (:resourceType IS NULL OR a.resourceType ="
                + " :resourceType) AND (:startDate IS NULL OR a.timestamp >= :startDate) AND"
                + " (:endDate IS NULL OR a.timestamp <= :endDate) AND (:search IS NULL OR"
                + " LOWER(a.username) LIKE LOWER(CONCAT('%', :search, '%')) OR  LOWER(a.resourceId)"
                + " LIKE LOWER(CONCAT('%', :search, '%')) OR  LOWER(a.details) LIKE"
                + " LOWER(CONCAT('%', :search, '%'))) ORDER BY a.timestamp DESC")
    Page<AuditLog> findWithFilters(
            @Param("userId") String userId,
            @Param("action") String action,
            @Param("resourceType") Resource resourceType,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            @Param("search") String search,
            Pageable pageable);

    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action")
    List<String> findDistinctActions();
}
