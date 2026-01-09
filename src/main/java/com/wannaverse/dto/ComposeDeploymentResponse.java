package com.wannaverse.dto;

import com.wannaverse.persistence.ComposeDeployment;
import com.wannaverse.persistence.ComposeDeployment.DeploymentStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComposeDeploymentResponse {
    private String id;
    private String dockerHostId;
    private String projectName;
    private String composeContent;
    private String envFileContent;
    private DeploymentStatus status;
    private int version;
    private String previousDeploymentId;
    private String gitRepositoryId;
    private String gitRepositoryName;
    private String commitSha;
    private String userId;
    private String username;
    private String logs;
    private long createdAt;
    private long completedAt;
    private Long durationMs;

    public static ComposeDeploymentResponse fromEntity(ComposeDeployment entity) {
        ComposeDeploymentResponse response = new ComposeDeploymentResponse();
        response.setId(entity.getId());
        response.setDockerHostId(entity.getDockerHost().getId());
        response.setProjectName(entity.getProjectName());
        response.setComposeContent(entity.getComposeContent());
        response.setEnvFileContent(entity.getEnvFileContent());
        response.setStatus(entity.getStatus());
        response.setVersion(entity.getVersion());

        if (entity.getPreviousDeployment() != null) {
            response.setPreviousDeploymentId(entity.getPreviousDeployment().getId());
        }

        if (entity.getGitRepository() != null) {
            response.setGitRepositoryId(entity.getGitRepository().getId());
            response.setGitRepositoryName(entity.getGitRepository().getName());
        }

        response.setCommitSha(entity.getCommitSha());
        response.setUserId(entity.getUserId());
        response.setUsername(entity.getUsername());
        response.setLogs(entity.getLogs());
        response.setCreatedAt(entity.getCreatedAt());
        response.setCompletedAt(entity.getCompletedAt());

        if (entity.getCompletedAt() > 0 && entity.getCreatedAt() > 0) {
            response.setDurationMs(entity.getCompletedAt() - entity.getCreatedAt());
        }

        return response;
    }
}
