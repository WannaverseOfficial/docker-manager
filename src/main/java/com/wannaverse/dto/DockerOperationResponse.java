package com.wannaverse.dto;

import com.wannaverse.persistence.DockerOperation;
import com.wannaverse.persistence.DockerOperation.OperationStatus;
import com.wannaverse.persistence.DockerOperation.OperationType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DockerOperationResponse {
    private String id;
    private String dockerHostId;
    private OperationType operationType;
    private OperationStatus status;
    private String resourceId;
    private String resourceName;
    private String deploymentJobId;
    private String commitSha;
    private String userId;
    private String username;
    private String logs;
    private String errorMessage;
    private long createdAt;
    private long completedAt;
    private Long durationMs;
    private boolean rollbackAvailable;

    public static DockerOperationResponse fromEntity(DockerOperation entity) {
        return fromEntity(entity, false);
    }

    public static DockerOperationResponse fromEntity(
            DockerOperation entity, boolean rollbackAvailable) {
        DockerOperationResponse response = new DockerOperationResponse();
        response.setId(entity.getId());
        response.setDockerHostId(entity.getDockerHost().getId());
        response.setOperationType(entity.getOperationType());
        response.setStatus(entity.getStatus());
        response.setResourceId(entity.getResourceId());
        response.setResourceName(entity.getResourceName());

        if (entity.getDeploymentJob() != null) {
            response.setDeploymentJobId(entity.getDeploymentJob().getId());
        }

        response.setCommitSha(entity.getCommitSha());
        response.setUserId(entity.getUserId());
        response.setUsername(entity.getUsername());
        response.setLogs(entity.getLogs());
        response.setErrorMessage(entity.getErrorMessage());
        response.setCreatedAt(entity.getCreatedAt());
        response.setCompletedAt(entity.getCompletedAt());

        if (entity.getCompletedAt() > 0 && entity.getCreatedAt() > 0) {
            response.setDurationMs(entity.getCompletedAt() - entity.getCreatedAt());
        }

        response.setRollbackAvailable(rollbackAvailable);

        return response;
    }
}
