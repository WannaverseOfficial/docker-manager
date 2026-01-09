package com.wannaverse.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EmailLogRepository extends JpaRepository<EmailLog, String> {

    List<EmailLog> findByRecipientUserIdOrderBySentAtDesc(String userId);

    List<EmailLog> findByEventTypeOrderBySentAtDesc(NotificationEventType eventType);

    List<EmailLog> findByStatusOrderByCreatedAtDesc(EmailLog.EmailStatus status);

    @Query("SELECT e FROM EmailLog e ORDER BY e.createdAt DESC")
    Page<EmailLog> findAllOrderByCreatedAtDesc(Pageable pageable);

    long countByStatusAndCreatedAtAfter(EmailLog.EmailStatus status, Instant after);

    @Query(
            "SELECT e FROM EmailLog e WHERE "
                    + "(:status IS NULL OR e.status = :status) AND "
                    + "(:eventType IS NULL OR e.eventType = :eventType) "
                    + "ORDER BY e.createdAt DESC")
    Page<EmailLog> findWithFilters(
            @Param("status") EmailLog.EmailStatus status,
            @Param("eventType") NotificationEventType eventType,
            Pageable pageable);
}
