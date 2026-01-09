package com.wannaverse.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerDeploymentRequest {
    private String commitSha;
    private boolean forceBuild;
}
