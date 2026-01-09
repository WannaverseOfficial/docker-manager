package com.wannaverse.dto;

import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.GitRepository.AuthType;
import com.wannaverse.persistence.GitRepository.DeploymentType;
import com.wannaverse.persistence.GitRepository.DriftStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitRepositoryResponse {
    private String id;
    private String name;
    private String repositoryUrl;
    private String branch;
    private AuthType authType;
    private DeploymentType deploymentType;
    private String dockerfilePath;
    private String composePath;
    private String imageName;
    private String dockerHostId;
    private boolean pollingEnabled;
    private int pollingIntervalSeconds;
    private boolean webhookEnabled;
    private String lastCommitSha;
    private String webhookUrl;
    private long createdAt;
    private long lastDeployedAt;

    // Drift detection fields
    private DriftStatus driftStatus;
    private String latestRemoteCommitSha;
    private Long driftDetectedAt;
    private Long lastDriftCheckAt;

    public static GitRepositoryResponse fromEntity(GitRepository entity, String baseUrl) {
        GitRepositoryResponse response = new GitRepositoryResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setRepositoryUrl(entity.getRepositoryUrl());
        response.setBranch(entity.getBranch());
        response.setAuthType(entity.getAuthType());
        response.setDeploymentType(entity.getDeploymentType());
        response.setDockerfilePath(entity.getDockerfilePath());
        response.setComposePath(entity.getComposePath());
        response.setImageName(entity.getImageName());
        response.setDockerHostId(entity.getDockerHost().getId());
        response.setPollingEnabled(entity.isPollingEnabled());
        response.setPollingIntervalSeconds(entity.getPollingIntervalSeconds());
        response.setWebhookEnabled(entity.isWebhookEnabled());
        response.setLastCommitSha(entity.getLastCommitSha());
        response.setWebhookUrl(baseUrl + "/api/git/webhook/" + entity.getWebhookSecret());
        response.setCreatedAt(entity.getCreatedAt());
        response.setLastDeployedAt(entity.getLastDeployedAt());

        // Drift detection fields
        response.setDriftStatus(entity.getDriftStatus());
        response.setLatestRemoteCommitSha(entity.getLatestRemoteCommitSha());
        response.setDriftDetectedAt(entity.getDriftDetectedAt());
        response.setLastDriftCheckAt(entity.getLastDriftCheckAt());

        return response;
    }
}
