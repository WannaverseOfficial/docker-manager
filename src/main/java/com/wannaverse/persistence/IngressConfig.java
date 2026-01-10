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
        name = "ingress_configs_t",
        indexes = {@Index(name = "idx_ingress_config_host", columnList = "docker_host_id")})
public class IngressConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "docker_host_id", nullable = false, unique = true)
    private String dockerHostId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngressStatus status = IngressStatus.DISABLED;

    private String nginxContainerId;

    private String nginxContainerName;

    private String ingressNetworkId;

    private String ingressNetworkName;

    @Column(nullable = false)
    private int httpPort = 80;

    @Column(nullable = false)
    private int httpsPort = 443;

    // Port where the Docker Manager app is accessible from within Docker containers
    // Used by nginx to proxy ACME challenges back to the app
    @Column(nullable = false)
    private int acmeProxyPort = 8080;

    // ACME/Let's Encrypt settings (user must explicitly configure)
    private String acmeEmail;

    private boolean acmeEnabled = false;

    private String acmeDirectoryUrl;

    @Column(columnDefinition = "TEXT")
    private String currentNginxConfig;

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

    private Long enabledAt;

    private Long disabledAt;

    private String enabledByUserId;

    private String enabledByUsername;

    private String lastError;

    public enum IngressStatus {
        DISABLED, // Ingress not set up
        ENABLING, // In process of enabling
        ENABLED, // Fully operational
        DISABLING, // In process of disabling
        ERROR // Something went wrong
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
