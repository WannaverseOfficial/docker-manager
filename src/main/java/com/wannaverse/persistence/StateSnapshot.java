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
        name = "state_snapshots_t",
        indexes = {
            @Index(name = "idx_snapshot_operation", columnList = "docker_operation_id"),
            @Index(name = "idx_snapshot_resource", columnList = "resourceId"),
            @Index(name = "idx_snapshot_type", columnList = "snapshotType")
        })
public class StateSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "docker_operation_id", nullable = false)
    private DockerOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SnapshotType snapshotType;

    private String resourceId;

    private String resourceName;

    @Column(columnDefinition = "BLOB")
    private byte[] inspectDataCompressed;

    @Column(columnDefinition = "TEXT")
    private String composeContent;

    @Column(columnDefinition = "TEXT")
    private String environmentVars;

    @Column(columnDefinition = "TEXT")
    private String volumeBindings;

    @Column(columnDefinition = "TEXT")
    private String portBindings;

    @Column(columnDefinition = "TEXT")
    private String networkSettings;

    private String imageName;

    private String imageId;

    @Column(nullable = false)
    private long createdAt;

    public enum SnapshotType {
        BEFORE,
        AFTER
    }

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
    }
}
