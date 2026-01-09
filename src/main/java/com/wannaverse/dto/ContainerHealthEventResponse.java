package com.wannaverse.dto;

import com.wannaverse.persistence.ContainerHealthEvent;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ContainerHealthEventResponse {
    private String id;
    private String hostId;
    private String containerId;
    private String containerName;
    private String eventType;
    private Integer exitCode;
    private Integer restartCount;
    private String errorMessage;
    private boolean notificationSent;
    private long detectedAt;
    private Long resolvedAt;
    private boolean active;

    public static ContainerHealthEventResponse fromEntity(ContainerHealthEvent event) {
        return ContainerHealthEventResponse.builder()
                .id(event.getId())
                .hostId(event.getDockerHost() != null ? event.getDockerHost().getId() : null)
                .containerId(event.getContainerId())
                .containerName(event.getContainerName())
                .eventType(event.getEventType().name())
                .exitCode(event.getExitCode())
                .restartCount(event.getRestartCount())
                .errorMessage(event.getErrorMessage())
                .notificationSent(event.isNotificationSent())
                .detectedAt(event.getDetectedAt())
                .resolvedAt(event.getResolvedAt())
                .active(event.isActive())
                .build();
    }
}
