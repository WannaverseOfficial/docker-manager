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
        name = "ingress_routes_t",
        indexes = {
            @Index(name = "idx_route_config", columnList = "ingress_config_id"),
            @Index(name = "idx_route_hostname", columnList = "hostname"),
            @Index(name = "idx_route_container", columnList = "target_container_id")
        })
public class IngressRoute {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "ingress_config_id", nullable = false)
    private String ingressConfigId;

    @Column(nullable = false)
    private String hostname;

    private String pathPrefix = "/";

    @Column(name = "target_container_id", nullable = false)
    private String targetContainerId;

    @Column(nullable = false)
    private String targetContainerName;

    @Column(nullable = false)
    private int targetPort;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Protocol protocol = Protocol.HTTP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TlsMode tlsMode = TlsMode.NONE;

    private String certificateId;

    private boolean authEnabled = false;

    private boolean forceHttpsRedirect = false;

    @Enumerated(EnumType.STRING)
    private AuthType authType;

    @Column(columnDefinition = "TEXT")
    private String authConfig;

    private boolean enabled = true;

    @Column(columnDefinition = "TEXT")
    private String generatedNginxBlock;

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

    private String createdByUserId;

    private String createdByUsername;

    public enum Protocol {
        HTTP,
        HTTPS
    }

    public enum TlsMode {
        NONE,
        LETS_ENCRYPT,
        CUSTOM_CERT,
        BRING_YOUR_OWN
    }

    public enum AuthType {
        BASIC,
        FORWARD_AUTH
    }

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = System.currentTimeMillis();
    }
}
