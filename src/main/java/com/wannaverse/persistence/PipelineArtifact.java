package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pipeline_artifacts_t")
public class PipelineArtifact {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private PipelineExecution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_execution_id")
    private StepExecution stepExecution;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String path;

    private String originalPath;

    private String contentType;

    private long sizeBytes;

    private String checksum;

    @Column(nullable = false)
    private long createdAt;

    private long expiresAt;

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
    }
}
