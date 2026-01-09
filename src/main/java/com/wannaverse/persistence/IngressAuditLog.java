package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ingress_audit_logs_t",
        indexes = {
            @Index(name = "idx_ingress_audit_config", columnList = "ingress_config_id"),
            @Index(name = "idx_ingress_audit_action", columnList = "action"),
            @Index(name = "idx_ingress_audit_timestamp", columnList = "timestamp")
        })
public class IngressAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "ingress_config_id")
    private String ingressConfigId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngressAction action;

    private String resourceType;

    private String resourceId;

    @Column(columnDefinition = "TEXT")
    private String beforeState;

    @Column(columnDefinition = "TEXT")
    private String afterState;

    @Column(columnDefinition = "TEXT")
    private String details;

    private boolean success = true;

    private String errorMessage;

    private String userId;

    private String username;

    @Column(nullable = false)
    private long timestamp;

    public enum IngressAction {
        // Ingress lifecycle
        INGRESS_ENABLED,
        INGRESS_DISABLED,
        INGRESS_ERROR,

        // Route management
        ROUTE_CREATED,
        ROUTE_UPDATED,
        ROUTE_ENABLED,
        ROUTE_DISABLED,
        ROUTE_DELETED,

        // Certificate management
        CERTIFICATE_REQUESTED,
        CERTIFICATE_ISSUED,
        CERTIFICATE_RENEWED,
        CERTIFICATE_UPLOADED,
        CERTIFICATE_EXPIRED,
        CERTIFICATE_DELETED,
        CERTIFICATE_UPDATED,
        CERTIFICATE_ERROR,

        // ACME challenge tracking (for transparency)
        ACME_ACCOUNT_CREATED,
        ACME_CHALLENGE_STARTED,
        ACME_CHALLENGE_COMPLETED,
        ACME_CHALLENGE_FAILED,

        // Nginx operations
        NGINX_CONFIG_UPDATED,
        NGINX_RELOADED,
        NGINX_RESTART,
        NGINX_ERROR,

        // Network operations
        CONTAINER_CONNECTED,
        CONTAINER_DISCONNECTED
    }

    @PrePersist
    protected void onCreate() {
        timestamp = System.currentTimeMillis();
    }

    /** Factory method for creating success audit log. */
    public static IngressAuditLog success(
            String ingressConfigId,
            IngressAction action,
            String details,
            String userId,
            String username) {
        IngressAuditLog log = new IngressAuditLog();
        log.setIngressConfigId(ingressConfigId);
        log.setAction(action);
        log.setDetails(details);
        log.setSuccess(true);
        log.setUserId(userId);
        log.setUsername(username);
        return log;
    }

    /** Factory method for creating failure audit log. */
    public static IngressAuditLog failure(
            String ingressConfigId,
            IngressAction action,
            String details,
            String errorMessage,
            String userId,
            String username) {
        IngressAuditLog log = new IngressAuditLog();
        log.setIngressConfigId(ingressConfigId);
        log.setAction(action);
        log.setDetails(details);
        log.setSuccess(false);
        log.setErrorMessage(errorMessage);
        log.setUserId(userId);
        log.setUsername(username);
        return log;
    }
}
