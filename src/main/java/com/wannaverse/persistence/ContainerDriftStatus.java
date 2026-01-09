package com.wannaverse.persistence;

import com.wannaverse.persistence.GitRepository.DriftStatus;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "container_drift_status_t",
        indexes = {
            @Index(name = "idx_drift_host", columnList = "dockerHostId"),
            @Index(name = "idx_drift_container", columnList = "containerId"),
            @Index(name = "idx_drift_status", columnList = "configDriftStatus")
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_drift_host_container",
                    columnNames = {"dockerHostId", "containerId"})
        })
public class ContainerDriftStatus {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String dockerHostId;

    @Column(nullable = false)
    private String containerId;

    private String containerName;

    private String imageName;

    // Image drift
    @Enumerated(EnumType.STRING)
    private DriftStatus imageDriftStatus;

    private String runningImageDigest;

    private String latestImageDigest;

    // Config drift
    @Enumerated(EnumType.STRING)
    private DriftStatus configDriftStatus;

    @Column(columnDefinition = "TEXT")
    private String driftDetails;

    private Long lastCheckedAt;

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

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
