package com.wannaverse.dto;

import com.wannaverse.service.RollbackService;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RollbackPointResponse {
    private String operationId;
    private String operationType;
    private String resourceName;
    private long timestamp;
    private String username;
    private boolean canRollback;
    private String reason;

    public static RollbackPointResponse fromRollbackPoint(RollbackService.RollbackPoint point) {
        RollbackPointResponse response = new RollbackPointResponse();
        response.setOperationId(point.operationId());
        response.setOperationType(point.operationType());
        response.setResourceName(point.resourceName());
        response.setTimestamp(point.timestamp());
        response.setUsername(point.username());
        response.setCanRollback(point.canRollback());
        response.setReason(point.reason());
        return response;
    }
}
