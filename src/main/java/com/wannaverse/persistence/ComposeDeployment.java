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
        name = "compose_deployments_t",
        indexes = {
            @Index(name = "idx_compose_host", columnList = "docker_host_id"),
            @Index(name = "idx_compose_project", columnList = "projectName"),
            @Index(name = "idx_compose_status", columnList = "status")
        })
public class ComposeDeployment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "docker_host_id", nullable = false)
    private DockerHost dockerHost;

    @Column(nullable = false)
    private String projectName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String composeContent;

    @Column(columnDefinition = "TEXT")
    private String envFileContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeploymentStatus status;

    @Column(nullable = false)
    private int version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_deployment_id")
    private ComposeDeployment previousDeployment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "git_repository_id")
    private GitRepository gitRepository;

    private String commitSha;

    private String userId;

    private String username;

    @Column(columnDefinition = "TEXT")
    private String logs;

    @Column(nullable = false)
    private long createdAt;

    private long completedAt;

    public enum DeploymentStatus {
        PENDING,
        DEPLOYING,
        ACTIVE,
        STOPPED,
        FAILED,
        ROLLED_BACK
    }

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
        if (status == null) {
            status = DeploymentStatus.PENDING;
        }
        if (version == 0) {
            version = 1;
        }
    }

    public void appendLog(String logLine) {
        if (logs == null) {
            logs = "";
        }
        logs += logLine + "\n";
    }
}
