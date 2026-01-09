package com.wannaverse.dto;

import com.wannaverse.persistence.AuditLog;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuditLogResponse {
    private String id;
    private long timestamp;
    private String userId;
    private String username;
    private String action;
    private String resourceType;
    private String resourceId;
    private String ipAddress;
    private String details;
    private boolean success;
    private String errorMessage;

    public static AuditLogResponse fromEntity(AuditLog entity) {
        return AuditLogResponse.builder()
                .id(entity.getId())
                .timestamp(entity.getTimestamp().toEpochMilli())
                .userId(entity.getUserId())
                .username(entity.getUsername())
                .action(entity.getAction())
                .resourceType(entity.getResourceType().name())
                .resourceId(entity.getResourceId())
                .ipAddress(entity.getIpAddress())
                .details(entity.getDetails())
                .success(entity.isSuccess())
                .errorMessage(entity.getErrorMessage())
                .build();
    }
}
