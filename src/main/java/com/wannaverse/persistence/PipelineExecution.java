package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pipeline_executions_t")
public class PipelineExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @Column(nullable = false)
    private int buildNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerType triggerType;

    private String triggerCommit;

    private String triggerBranch;

    @Column(columnDefinition = "TEXT")
    private String triggerMessage;

    private String triggeredBy;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<StageExecution> stageExecutions = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String logs;

    private String errorMessage;

    @Column(nullable = false)
    private long createdAt;

    private long startedAt;

    private long finishedAt;

    public enum ExecutionStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    public enum TriggerType {
        MANUAL,
        WEBHOOK,
        POLLING,
        SCHEDULED
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
