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
    private String tokenUsername;
    private boolean hasToken;
    private boolean hasSshKey;
    private boolean runContainer;
    private com.wannaverse.dto.ContainerSpec containerSpec;
    private String dockerHostName;
    private String dockerHostId;
    private boolean pollingEnabled;
    private int pollingIntervalSeconds;
    private boolean webhookEnabled;
    private String lastCommitSha;
    private String webhookUrl;
    private long createdAt;
    private long lastDeployedAt;

    private DriftStatus driftStatus;
    private String latestRemoteCommitSha;
    private Long driftDetectedAt;
    private Long lastDriftCheckAt;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    public static com.wannaverse.dto.ContainerSpec readSpec(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, com.wannaverse.dto.ContainerSpec.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String writeSpec(com.wannaverse.dto.ContainerSpec spec) {
        if (spec == null) return null;
        try {
            return JSON.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid container settings");
        }
    }

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
        response.setDockerHostName(entity.getDockerHost().getName());
        response.setTokenUsername(entity.getTokenUsername());
        response.setHasToken(entity.getEncryptedToken() != null);
        response.setHasSshKey(entity.getEncryptedSshKey() != null);
        response.setRunContainer(!Boolean.FALSE.equals(entity.getRunContainer()));
        response.setContainerSpec(readSpec(entity.getContainerSpecJson()));
        response.setPollingEnabled(entity.isPollingEnabled());
        response.setPollingIntervalSeconds(entity.getPollingIntervalSeconds());
        response.setWebhookEnabled(entity.isWebhookEnabled());
        response.setLastCommitSha(entity.getLastCommitSha());
        response.setWebhookUrl(baseUrl + "/api/git/webhook/" + entity.getWebhookSecret());
        response.setCreatedAt(entity.getCreatedAt());
        response.setLastDeployedAt(entity.getLastDeployedAt());

        response.setDriftStatus(entity.getDriftStatus());
        response.setLatestRemoteCommitSha(entity.getLatestRemoteCommitSha());
        response.setDriftDetectedAt(entity.getDriftDetectedAt());
        response.setLastDriftCheckAt(entity.getLastDriftCheckAt());

        return response;
    }
}
