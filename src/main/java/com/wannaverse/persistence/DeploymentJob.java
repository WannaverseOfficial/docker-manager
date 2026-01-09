package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "deployment_jobs_t")
public class DeploymentJob {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "git_repository_id", nullable = false)
    private GitRepository gitRepository;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerType triggerType;

    private String commitSha;

    @Column(columnDefinition = "TEXT")
    private String logs;

    private String errorMessage;

    @Column(nullable = false)
    private long createdAt;

    private long startedAt;

    private long completedAt;

    public enum JobStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    public enum TriggerType {
        MANUAL,
        POLLING,
        WEBHOOK
    }

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
    }

    public void appendLog(String logLine) {
        if (logs == null) {
            logs = "";
        }
        logs += logLine + "\n";
    }
}
