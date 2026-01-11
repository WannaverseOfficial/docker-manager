package com.wannaverse.dto;

import com.wannaverse.persistence.PipelineStage;
import com.wannaverse.persistence.PipelineStep;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PipelineRequest {
    private String name;
    private String description;
    private String gitRepositoryId;
    private String dockerHostId;
    private Boolean enabled;
    private Boolean webhookEnabled;
    private Boolean pollingEnabled;
    private Integer pollingIntervalSeconds;
    private String branchFilter;
    private String graphLayout; // JSON
    private List<StageRequest> stages;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageRequest {
        private String id; // For updates
        private String name;
        private Integer orderIndex;
        private PipelineStage.ExecutionMode executionMode;
        private Integer positionX;
        private Integer positionY;
        private List<String> dependsOn; // Stage IDs this stage depends on
        private Boolean stopOnFailure; // Stop pipeline if this stage fails
        private List<StepRequest> steps;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepRequest {
        private String id; // For updates
        private String name;
        private Integer orderIndex;
        private PipelineStep.StepType stepType;
        private String configuration; // JSON
        private String workingDirectory;
        private Integer timeoutSeconds;
        private Boolean continueOnFailure;
        private String environmentVariables; // JSON
        private String artifactInputPattern;
        private String artifactOutputPattern;
        private Integer positionX;
        private Integer positionY;
    }
}
