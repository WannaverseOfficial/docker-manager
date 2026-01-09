package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "audit_logs",
        indexes = {
            @Index(name = "idx_audit_user_id", columnList = "userId"),
            @Index(name = "idx_audit_action", columnList = "action"),
            @Index(name = "idx_audit_resource_type", columnList = "resourceType"),
            @Index(name = "idx_audit_timestamp", columnList = "timestamp")
        })
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(length = 36)
    private String userId;

    @Column(length = 100)
    private String username;

    @Column(nullable = false, length = 50)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Resource resourceType;

    @Column(length = 255)
    private String resourceId;

    @Column(length = 45)
    private String ipAddress;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false)
    private boolean success = true;

    @Column(length = 500)
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
