package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pipeline_steps_t")
public class PipelineStep {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stage_id", nullable = false)
    private PipelineStage stage;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepType stepType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String configuration;

    private String workingDirectory;

    private int timeoutSeconds = 3600;

    private boolean continueOnFailure = false;

    @Column(columnDefinition = "TEXT")
    private String environmentVariables;

    private String artifactInputPattern;

    private String artifactOutputPattern;

    private int positionX;
    private int positionY;

    public enum StepType {
        SHELL,
        JAR,
        DOCKERFILE,
        DOCKER_COMPOSE,
        CUSTOM_IMAGE
    }
}
