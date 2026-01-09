package com.wannaverse.dto;

import com.wannaverse.persistence.GitRepository.AuthType;
import com.wannaverse.persistence.GitRepository.DeploymentType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGitRepositoryRequest {
    private String name;
    private String repositoryUrl;
    private String branch;
    private AuthType authType;
    private String token;
    private String sshKey;
    private DeploymentType deploymentType;
    private String dockerfilePath;
    private String composePath;
    private String imageName;
    private boolean pollingEnabled;
    private int pollingIntervalSeconds;
    private boolean webhookEnabled;
}
