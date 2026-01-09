package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(
        name = "docker_registries_t",
        indexes = {
            @Index(name = "idx_registry_name", columnList = "name", unique = true),
            @Index(name = "idx_registry_url", columnList = "url")
        })
public class DockerRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegistryType registryType = RegistryType.PRIVATE;

    // Basic auth credentials (encrypted)
    @Column(columnDefinition = "TEXT")
    private String username;

    @Column(columnDefinition = "TEXT")
    private String password;

    // AWS ECR specific fields
    private String awsRegion;

    @Column(columnDefinition = "TEXT")
    private String awsAccessKeyId;

    @Column(columnDefinition = "TEXT")
    private String awsSecretKey;

    // Google Container Registry specific fields
    private String gcpProjectId;

    @Column(columnDefinition = "TEXT")
    private String gcpServiceAccountJson;

    // Azure Container Registry specific fields
    private String azureClientId;

    @Column(columnDefinition = "TEXT")
    private String azureClientSecret;

    private String azureTenantId;

    @Column(nullable = false)
    private boolean isDefault;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

    public enum RegistryType {
        DOCKER_HUB,
        PRIVATE,
        AWS_ECR,
        GCR,
        ACR
    }

    @PrePersist
    protected void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == 0) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = System.currentTimeMillis();
    }
}
