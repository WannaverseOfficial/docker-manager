package com.wannaverse.dto;

import com.wannaverse.persistence.Resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Data
public class GrantPermissionRequest {
    @NotNull(message = "Resource is required")
    private Resource resource;

    @NotBlank(message = "Action is required")
    private String action;

    private String scopeHostId;

    private String scopeResourceId;
}
