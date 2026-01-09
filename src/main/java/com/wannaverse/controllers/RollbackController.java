package com.wannaverse.controllers;

import com.wannaverse.dto.RollbackPointResponse;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.RollbackService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rollback")
public class RollbackController {
    private final RollbackService rollbackService;

    public RollbackController(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    @GetMapping("/hosts/{hostId}/points/{resourceType}/{resourceId}")
    @RequirePermission(resource = Resource.ROLLBACK, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<RollbackPointResponse>> getRollbackPoints(
            @PathVariable String hostId,
            @PathVariable String resourceType,
            @PathVariable String resourceId) {

        List<RollbackService.RollbackPoint> points =
                rollbackService.getRollbackPoints(hostId, resourceId, resourceType);

        List<RollbackPointResponse> responses =
                points.stream().map(RollbackPointResponse::fromRollbackPoint).toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/validate/{operationId}")
    @RequirePermission(resource = Resource.ROLLBACK, action = "validate")
    public ResponseEntity<Map<String, Object>> validateRollback(@PathVariable String operationId) {
        RollbackService.RollbackValidation validation =
                rollbackService.validateRollback(operationId);

        return ResponseEntity.ok(
                Map.of(
                        "canRollback",
                        validation.canRollback(),
                        "reason",
                        validation.reason() != null ? validation.reason() : ""));
    }

    @PostMapping("/operation/{operationId}")
    @RequirePermission(resource = Resource.ROLLBACK, action = "execute")
    @Auditable(resource = Resource.ROLLBACK, action = "execute", resourceIdParam = "operationId")
    public ResponseEntity<Map<String, Object>> rollbackOperation(@PathVariable String operationId) {
        RollbackService.RollbackResult result = rollbackService.rollbackOperation(operationId);

        return ResponseEntity.ok(
                Map.of(
                        "success", result.success(),
                        "message", result.message(),
                        "newResourceId",
                                result.newResourceId() != null ? result.newResourceId() : ""));
    }

    @PostMapping("/compose/{deploymentId}/version/{version}")
    @RequirePermission(resource = Resource.ROLLBACK, action = "execute")
    @Auditable(
            resource = Resource.ROLLBACK,
            action = "rollback-compose",
            resourceIdParam = "deploymentId")
    public ResponseEntity<Map<String, Object>> rollbackComposeDeployment(
            @PathVariable String deploymentId, @PathVariable int version) {

        RollbackService.RollbackResult result =
                rollbackService.rollbackComposeDeployment(deploymentId, version);

        return ResponseEntity.ok(
                Map.of(
                        "success", result.success(),
                        "message", result.message(),
                        "newDeploymentId",
                                result.newResourceId() != null ? result.newResourceId() : ""));
    }
}
