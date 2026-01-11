package com.wannaverse.dto;

import com.wannaverse.persistence.Pipeline;
import com.wannaverse.persistence.PipelineExecution;
import com.wannaverse.persistence.PipelineStage;
import com.wannaverse.persistence.PipelineStep;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PipelineResponse {
    private String id;
    private String name;
    private String description;
    private String gitRepositoryId;
    private String gitRepositoryName;
    private String dockerHostId;
    private String dockerHostName;
    private boolean enabled;
    private boolean webhookEnabled;
    private boolean pollingEnabled;
    private int pollingIntervalSeconds;
    private String branchFilter;
    private String webhookSecret;
    private String graphLayout;
    private List<StageResponse> stages;
    private long createdAt;
    private long updatedAt;
    private String createdBy;

    // Summary stats
    private int totalExecutions;
    private int successfulExecutions;
    private int failedExecutions;
    private Long lastExecutionAt;
    private PipelineExecution.ExecutionStatus lastExecutionStatus;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageResponse {
        private String id;
        private String name;
        private int orderIndex;
        private PipelineStage.ExecutionMode executionMode;
        private int positionX;
        private int positionY;
        private List<String> dependsOn;
        private boolean stopOnFailure;
        private List<StepResponse> steps;

        public static StageResponse fromEntity(PipelineStage stage) {
            StageResponse response = new StageResponse();
            response.setId(stage.getId());
            response.setName(stage.getName());
            response.setOrderIndex(stage.getOrderIndex());
            response.setExecutionMode(stage.getExecutionMode());
            response.setPositionX(stage.getPositionX());
            response.setPositionY(stage.getPositionY());
            response.setDependsOn(stage.getDependsOn() != null ? stage.getDependsOn() : List.of());
            response.setStopOnFailure(stage.isStopOnFailure());
            response.setSteps(
                    stage.getSteps().stream()
                            .map(StepResponse::fromEntity)
                            .collect(Collectors.toList()));
            return response;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepResponse {
        private String id;
        private String name;
        private int orderIndex;
        private PipelineStep.StepType stepType;
        private String configuration;
        private String workingDirectory;
        private int timeoutSeconds;
        private boolean continueOnFailure;
        private String environmentVariables;
        private String artifactInputPattern;
        private String artifactOutputPattern;
        private int positionX;
        private int positionY;

        public static StepResponse fromEntity(PipelineStep step) {
            StepResponse response = new StepResponse();
            response.setId(step.getId());
            response.setName(step.getName());
            response.setOrderIndex(step.getOrderIndex());
            response.setStepType(step.getStepType());
            response.setConfiguration(step.getConfiguration());
            response.setWorkingDirectory(step.getWorkingDirectory());
            response.setTimeoutSeconds(step.getTimeoutSeconds());
            response.setContinueOnFailure(step.isContinueOnFailure());
            response.setEnvironmentVariables(step.getEnvironmentVariables());
            response.setArtifactInputPattern(step.getArtifactInputPattern());
            response.setArtifactOutputPattern(step.getArtifactOutputPattern());
            response.setPositionX(step.getPositionX());
            response.setPositionY(step.getPositionY());
            return response;
        }
    }

    public static PipelineResponse fromEntity(Pipeline pipeline) {
        PipelineResponse response = new PipelineResponse();
        response.setId(pipeline.getId());
        response.setName(pipeline.getName());
        response.setDescription(pipeline.getDescription());
        response.setEnabled(pipeline.isEnabled());
        response.setWebhookEnabled(pipeline.isWebhookEnabled());
        response.setPollingEnabled(pipeline.isPollingEnabled());
        response.setPollingIntervalSeconds(pipeline.getPollingIntervalSeconds());
        response.setBranchFilter(pipeline.getBranchFilter());
        response.setWebhookSecret(pipeline.getWebhookSecret());
        response.setGraphLayout(pipeline.getGraphLayout());
        response.setCreatedAt(pipeline.getCreatedAt());
        response.setUpdatedAt(pipeline.getUpdatedAt());
        response.setCreatedBy(pipeline.getCreatedBy());

        if (pipeline.getGitRepository() != null) {
            response.setGitRepositoryId(pipeline.getGitRepository().getId());
            response.setGitRepositoryName(pipeline.getGitRepository().getName());
        }

        if (pipeline.getDockerHost() != null) {
            response.setDockerHostId(pipeline.getDockerHost().getId());
            response.setDockerHostName(pipeline.getDockerHost().getDockerHostUrl());
        }

        response.setStages(
                pipeline.getStages().stream()
                        .map(StageResponse::fromEntity)
                        .collect(Collectors.toList()));

        return response;
    }
}
