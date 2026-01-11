package com.wannaverse.dto;

import com.wannaverse.persistence.PipelineExecution;
import com.wannaverse.persistence.StageExecution;
import com.wannaverse.persistence.StepExecution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PipelineExecutionResponse {
    private String id;
    private String pipelineId;
    private String pipelineName;
    private int buildNumber;
    private PipelineExecution.ExecutionStatus status;
    private PipelineExecution.TriggerType triggerType;
    private String triggerCommit;
    private String triggerBranch;
    private String triggerMessage;
    private String triggeredBy;
    private String logs;
    private String errorMessage;
    private List<StageExecutionResponse> stageExecutions;
    private long createdAt;
    private long startedAt;
    private long finishedAt;
    private long durationMs;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageExecutionResponse {
        private String id;
        private String stageId;
        private String stageName;
        private PipelineExecution.ExecutionStatus status;
        private String logs;
        private List<StepExecutionResponse> stepExecutions;
        private long startedAt;
        private long finishedAt;
        private long durationMs;

        public static StageExecutionResponse fromEntity(StageExecution stageExec) {
            StageExecutionResponse response = new StageExecutionResponse();
            response.setId(stageExec.getId());
            response.setStageId(stageExec.getStage().getId());
            response.setStageName(stageExec.getStage().getName());
            response.setStatus(stageExec.getStatus());
            response.setLogs(stageExec.getLogs());
            response.setStartedAt(stageExec.getStartedAt());
            response.setFinishedAt(stageExec.getFinishedAt());
            if (stageExec.getFinishedAt() > 0 && stageExec.getStartedAt() > 0) {
                response.setDurationMs(stageExec.getFinishedAt() - stageExec.getStartedAt());
            }
            response.setStepExecutions(
                    stageExec.getStepExecutions().stream()
                            .map(StepExecutionResponse::fromEntity)
                            .collect(Collectors.toList()));
            return response;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepExecutionResponse {
        private String id;
        private String stepId;
        private String stepName;
        private PipelineExecution.ExecutionStatus status;
        private int exitCode;
        private String logs;
        private String artifacts;
        private String errorMessage;
        private String containerId;
        private long startedAt;
        private long finishedAt;
        private long durationMs;

        public static StepExecutionResponse fromEntity(StepExecution stepExec) {
            StepExecutionResponse response = new StepExecutionResponse();
            response.setId(stepExec.getId());
            response.setStepId(stepExec.getStep().getId());
            response.setStepName(stepExec.getStep().getName());
            response.setStatus(stepExec.getStatus());
            response.setExitCode(stepExec.getExitCode());
            response.setLogs(stepExec.getLogs());
            response.setArtifacts(stepExec.getArtifacts());
            response.setErrorMessage(stepExec.getErrorMessage());
            response.setContainerId(stepExec.getContainerId());
            response.setStartedAt(stepExec.getStartedAt());
            response.setFinishedAt(stepExec.getFinishedAt());
            if (stepExec.getFinishedAt() > 0 && stepExec.getStartedAt() > 0) {
                response.setDurationMs(stepExec.getFinishedAt() - stepExec.getStartedAt());
            }
            return response;
        }
    }

    public static PipelineExecutionResponse fromEntity(PipelineExecution execution) {
        PipelineExecutionResponse response = new PipelineExecutionResponse();
        response.setId(execution.getId());
        response.setPipelineId(execution.getPipeline().getId());
        response.setPipelineName(execution.getPipeline().getName());
        response.setBuildNumber(execution.getBuildNumber());
        response.setStatus(execution.getStatus());
        response.setTriggerType(execution.getTriggerType());
        response.setTriggerCommit(execution.getTriggerCommit());
        response.setTriggerBranch(execution.getTriggerBranch());
        response.setTriggerMessage(execution.getTriggerMessage());
        response.setTriggeredBy(execution.getTriggeredBy());
        response.setLogs(execution.getLogs());
        response.setErrorMessage(execution.getErrorMessage());
        response.setCreatedAt(execution.getCreatedAt());
        response.setStartedAt(execution.getStartedAt());
        response.setFinishedAt(execution.getFinishedAt());
        if (execution.getFinishedAt() > 0 && execution.getStartedAt() > 0) {
            response.setDurationMs(execution.getFinishedAt() - execution.getStartedAt());
        }
        response.setStageExecutions(
                execution.getStageExecutions().stream()
                        .map(StageExecutionResponse::fromEntity)
                        .collect(Collectors.toList()));
        return response;
    }
}
