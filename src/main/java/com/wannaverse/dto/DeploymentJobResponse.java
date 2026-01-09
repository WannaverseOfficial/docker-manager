package com.wannaverse.dto;

import com.wannaverse.persistence.DeploymentJob;
import com.wannaverse.persistence.DeploymentJob.JobStatus;
import com.wannaverse.persistence.DeploymentJob.TriggerType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeploymentJobResponse {
    private String id;
    private String gitRepositoryId;
    private String gitRepositoryName;
    private JobStatus status;
    private TriggerType triggerType;
    private String commitSha;
    private String logs;
    private String errorMessage;
    private long createdAt;
    private long startedAt;
    private long completedAt;

    public static DeploymentJobResponse fromEntity(DeploymentJob entity) {
        DeploymentJobResponse response = new DeploymentJobResponse();
        response.setId(entity.getId());
        response.setGitRepositoryId(entity.getGitRepository().getId());
        response.setGitRepositoryName(entity.getGitRepository().getName());
        response.setStatus(entity.getStatus());
        response.setTriggerType(entity.getTriggerType());
        response.setCommitSha(entity.getCommitSha());
        response.setLogs(entity.getLogs());
        response.setErrorMessage(entity.getErrorMessage());
        response.setCreatedAt(entity.getCreatedAt());
        response.setStartedAt(entity.getStartedAt());
        response.setCompletedAt(entity.getCompletedAt());
        return response;
    }
}
