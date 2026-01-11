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
    private String configuration; // JSON config based on stepType

    private String workingDirectory;

    private int timeoutSeconds = 3600; // Default 1 hour

    private boolean continueOnFailure = false;

    @Column(columnDefinition = "TEXT")
    private String environmentVariables; // JSON key-value pairs

    private String artifactInputPattern; // glob pattern for input artifacts

    private String artifactOutputPattern; // glob pattern to capture outputs

    // Graph position for visual editor
    private int positionX;
    private int positionY;

    /**
     * Step execution types with their configuration schemas:
     *
     * <p>SHELL: { "script": "npm install && npm test" } JAR: { "jarPath": "/path/to.jar", "args":
     * ["--arg1"], "jvmOpts": "-Xmx512m" } DOCKERFILE: { "dockerfile": "./Dockerfile", "imageName":
     * "app:latest", "buildArgs": {} } DOCKER_COMPOSE: { "composeFile": "./docker-compose.yml",
     * "services": ["app"], "command": "up -d" } CUSTOM_IMAGE: { "image": "maven:3.8", "command":
     * "mvn clean install", "volumes": [] }
     */
    public enum StepType {
        SHELL, // Execute shell script
        JAR, // Execute JAR file with java
        DOCKERFILE, // Build image from Dockerfile
        DOCKER_COMPOSE, // Run docker-compose command
        CUSTOM_IMAGE // Run command in specified Docker image
    }
}
