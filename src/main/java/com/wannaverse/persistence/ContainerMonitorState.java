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

    /** When the latest restart was observed; used to decide a restart loop is over. */
    private Long lastRestartSeenAt = 0L;

    /** State.FinishedAt of the last OOM kill that was reported, to report each one once. */
    private String lastOomFinishedAt;

    private long lastCrashNotificationAt;

    private long lastRestartLoopNotificationAt;

    private long lastHealthNotificationAt;

    /**
     * When someone resolved a failing-health-check issue by hand while it was still failing. No new
     * issue is raised until the container passes its check again or restarts.
     */
    private Long healthAcknowledgedAt = 0L;

    // Nullable in the schema: SQLite can't add a NOT NULL column to an existing (1.x) table. But
    // databases created by earlier 2.0 builds have them as NOT NULL, so never write null either:
    // the fields start at 0 and 0 means "not set".
    public long getLastRestartSeenAt() {
        return lastRestartSeenAt == null ? 0 : lastRestartSeenAt;
    }

    public long getHealthAcknowledgedAt() {
        return healthAcknowledgedAt == null ? 0 : healthAcknowledgedAt;
    }

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
