package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "step_executions_t")
public class StepExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stage_execution_id", nullable = false)
    private StageExecution stageExecution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", nullable = false)
    private PipelineStep step;

    private int orderIndex; // Copy of step.orderIndex for JPA ordering

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineExecution.ExecutionStatus status = PipelineExecution.ExecutionStatus.PENDING;

    private int exitCode;

    @Column(columnDefinition = "TEXT")
    private String logs;

    @Column(columnDefinition = "TEXT")
    private String artifacts; // JSON list of artifact paths

    private String errorMessage;

    private String containerId; // Docker container used for execution

    private long startedAt;

    private long finishedAt;

    public void appendLog(String logLine) {
        if (logs == null) {
            logs = "";
        }
        logs += logLine + "\n";
    }
}
