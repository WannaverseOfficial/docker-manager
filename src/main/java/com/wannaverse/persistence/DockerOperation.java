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
        name = "docker_operations_t",
        indexes = {
            @Index(name = "idx_docker_op_host", columnList = "docker_host_id"),
            @Index(name = "idx_docker_op_type", columnList = "operationType"),
            @Index(name = "idx_docker_op_status", columnList = "status"),
            @Index(name = "idx_docker_op_created", columnList = "createdAt")
        })
public class DockerOperation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "docker_host_id", nullable = false)
    private DockerHost dockerHost;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationStatus status;

    private String resourceId;

    private String resourceName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deployment_job_id")
    private DeploymentJob deploymentJob;

    private String commitSha;

    private String userId;

    private String username;

    @Column(columnDefinition = "TEXT")
    private String logs;

    private String errorMessage;

    @Column(nullable = false)
    private long createdAt;

    private long completedAt;

    public enum OperationType {
        CONTAINER_CREATE,
        CONTAINER_START,
        CONTAINER_STOP,
        CONTAINER_RESTART,
        CONTAINER_DELETE,
        CONTAINER_PAUSE,
        CONTAINER_UNPAUSE,
        IMAGE_PULL,
        IMAGE_DELETE,
        COMPOSE_UP,
        COMPOSE_DOWN,
        VOLUME_CREATE,
        VOLUME_DELETE,
        NETWORK_CREATE,
        NETWORK_DELETE
    }

    public enum OperationStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        ROLLED_BACK
    }

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
        if (status == null) {
            status = OperationStatus.PENDING;
        }
    }

    public void appendLog(String logLine) {
        if (logs == null) {
            logs = "";
        }
        logs += logLine + "\n";
    }
}
