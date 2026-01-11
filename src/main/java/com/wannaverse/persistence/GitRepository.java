package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "git_repositories_t")
public class GitRepository {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String repositoryUrl;

    @Column(nullable = false)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthType authType;

    @Column(columnDefinition = "TEXT")
    private String encryptedToken;

    @Column(columnDefinition = "TEXT")
    private String encryptedSshKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeploymentType deploymentType;

    private String dockerfilePath;

    private String composePath;

    private String imageName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "docker_host_id", nullable = false)
    private DockerHost dockerHost;

    private boolean pollingEnabled;

    private int pollingIntervalSeconds;

    private boolean webhookEnabled;

    private String lastCommitSha;

    @Column(unique = true)
    private String webhookSecret;

    @Column(nullable = false)
    private long createdAt;

    private long lastDeployedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DriftStatus driftStatus = DriftStatus.UNKNOWN;

    private String latestRemoteCommitSha;

    private Long driftDetectedAt;

    private Long lastDriftCheckAt;

    public enum AuthType {
        NONE,
        PAT,
        SSH_KEY
    }

    public enum DeploymentType {
        DOCKERFILE,
        DOCKER_COMPOSE
    }

    public enum DriftStatus {
        SYNCED,
        BEHIND,
        UNKNOWN,
        ERROR
    }

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
    }
}
