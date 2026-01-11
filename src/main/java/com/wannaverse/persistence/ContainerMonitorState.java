package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(
        name = "container_monitor_state_t",
        indexes = {
            @Index(name = "idx_monitor_state_host", columnList = "docker_host_id"),
            @Index(
                    name = "idx_monitor_state_container",
                    columnList = "docker_host_id, containerId",
                    unique = true)
        })
public class ContainerMonitorState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "docker_host_id", nullable = false)
    private DockerHost dockerHost;

    @Column(nullable = false)
    private String containerId;

    private String containerName;

    private String lastKnownStatus;

    private Integer lastRestartCount;

    private Integer restartCountAtWindowStart;

    private long lastRestartCountChange;

    private long lastCheckedAt;

    private long lastCrashNotificationAt;

    private long lastRestartLoopNotificationAt;

    private long lastHealthNotificationAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        lastCheckedAt = System.currentTimeMillis();
    }

    public boolean canNotifyCrash(long cooldownMs) {
        return System.currentTimeMillis() - lastCrashNotificationAt > cooldownMs;
    }

    public boolean canNotifyRestartLoop(long cooldownMs) {
        return System.currentTimeMillis() - lastRestartLoopNotificationAt > cooldownMs;
    }

    public boolean canNotifyHealth(long cooldownMs) {
        return System.currentTimeMillis() - lastHealthNotificationAt > cooldownMs;
    }

    public void markCrashNotified() {
        this.lastCrashNotificationAt = System.currentTimeMillis();
    }

    public void markRestartLoopNotified() {
        this.lastRestartLoopNotificationAt = System.currentTimeMillis();
    }

    public void markHealthNotified() {
        this.lastHealthNotificationAt = System.currentTimeMillis();
    }
}
