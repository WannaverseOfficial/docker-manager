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
@Table(name = "stage_executions_t")
public class StageExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private PipelineExecution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stage_id", nullable = false)
    private PipelineStage stage;

    private int orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineExecution.ExecutionStatus status = PipelineExecution.ExecutionStatus.PENDING;

    @OneToMany(mappedBy = "stageExecution", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<StepExecution> stepExecutions = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String logs;

    private long startedAt;

    private long finishedAt;

    public void appendLog(String logLine) {
        if (logs == null) {
            logs = "";
        }
        logs += logLine + "\n";
    }
}
