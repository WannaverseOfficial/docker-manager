package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(
        name = "container_health_events_t",
        indexes = {
            @Index(name = "idx_health_event_host", columnList = "docker_host_id"),
            @Index(name = "idx_health_event_container", columnList = "containerId"),
            @Index(name = "idx_health_event_type", columnList = "eventType"),
            @Index(name = "idx_health_event_detected", columnList = "detectedAt"),
            @Index(name = "idx_health_event_active", columnList = "docker_host_id, resolvedAt")
        })
public class ContainerHealthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "docker_host_id", nullable = false)
    private DockerHost dockerHost;

    @Column(nullable = false)
    private String containerId;

    private String containerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType eventType;

    private Integer exitCode;

    private Integer restartCount;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private boolean notificationSent;

    @Column(nullable = false)
    private long detectedAt;

    private Long resolvedAt;

    public enum EventType {
        CRASH,
        RESTART_LOOP,
        HEALTH_UNHEALTHY,
        OOM_KILLED
    }

    @PrePersist
    protected void onCreate() {
        if (detectedAt == 0) {
            detectedAt = System.currentTimeMillis();
        }
    }

    public boolean isActive() {
        return resolvedAt == null;
    }

    public void resolve() {
        this.resolvedAt = System.currentTimeMillis();
    }
}
