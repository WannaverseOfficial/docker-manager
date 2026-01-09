package com.wannaverse.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComposeDeployRequest {

    @NotBlank(message = "Compose content is required")
    private String composeContent;

    private String projectName;
}
