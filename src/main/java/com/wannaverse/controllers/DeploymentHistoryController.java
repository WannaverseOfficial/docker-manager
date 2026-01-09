package com.wannaverse.controllers;

import com.wannaverse.dto.ComposeDeploymentResponse;
import com.wannaverse.dto.DockerOperationResponse;
import com.wannaverse.dto.StateSnapshotResponse;
import com.wannaverse.persistence.*;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.OperationTrackingService;
import com.wannaverse.service.RollbackService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/deployments")
public class DeploymentHistoryController {
    private final DockerOperationRepository operationRepository;
    private final StateSnapshotRepository snapshotRepository;
    private final ComposeDeploymentRepository composeRepository;
    private final OperationTrackingService trackingService;
    private final RollbackService rollbackService;

    public DeploymentHistoryController(
            DockerOperationRepository operationRepository,
            StateSnapshotRepository snapshotRepository,
            ComposeDeploymentRepository composeRepository,
            OperationTrackingService trackingService,
            RollbackService rollbackService) {
        this.operationRepository = operationRepository;
        this.snapshotRepository = snapshotRepository;
        this.composeRepository = composeRepository;
        this.trackingService = trackingService;
        this.rollbackService = rollbackService;
    }

    // ==================== Operations ====================

    @GetMapping("/hosts/{hostId}/operations")
    public ResponseEntity<Map<String, Object>> listOperations(
            @PathVariable String hostId,
            @RequestParam(required = false) DockerOperation.OperationType type,
            @RequestParam(required = false) DockerOperation.OperationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<DockerOperation> operations =
                operationRepository.findByFilters(hostId, type, status, pageable);

        List<DockerOperationResponse> responses =
                operations.getContent().stream()
                        .map(
                                op -> {
                                    boolean rollbackAvailable =
                                            snapshotRepository
                                                            .findByOperationIdAndSnapshotType(
                                                                    op.getId(),
                                                                    StateSnapshot.SnapshotType
                                                                            .BEFORE)
                                                            .isPresent()
                                                    && op.getStatus()
                                                            != DockerOperation.OperationStatus
                                                                    .ROLLED_BACK;
                                    return DockerOperationResponse.fromEntity(
                                            op, rollbackAvailable);
                                })
                        .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("content", responses);
        result.put("totalElements", operations.getTotalElements());
        result.put("totalPages", operations.getTotalPages());
        result.put("page", page);
        result.put("size", size);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/operations/{operationId}")
    @RequirePermission(resource = Resource.DOCKER_OPERATIONS, action = "read")
    public ResponseEntity<DockerOperationResponse> getOperation(@PathVariable String operationId) {
        return operationRepository
                .findById(operationId)
                .map(
                        op -> {
                            boolean rollbackAvailable =
                                    snapshotRepository
                                                    .findByOperationIdAndSnapshotType(
                                                            op.getId(),
                                                            StateSnapshot.SnapshotType.BEFORE)
                                                    .isPresent()
                                            && op.getStatus()
                                                    != DockerOperation.OperationStatus.ROLLED_BACK;
                            return ResponseEntity.ok(
                                    DockerOperationResponse.fromEntity(op, rollbackAvailable));
                        })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/operations/{operationId}/snapshots")
    @RequirePermission(resource = Resource.STATE_SNAPSHOTS, action = "read")
    public ResponseEntity<List<StateSnapshotResponse>> getOperationSnapshots(
            @PathVariable String operationId) {
        List<StateSnapshot> snapshots =
                snapshotRepository.findByOperationIdOrderByCreatedAtAsc(operationId);

        List<StateSnapshotResponse> responses =
                snapshots.stream()
                        .map(
                                s ->
                                        StateSnapshotResponse.fromEntity(
                                                s, trackingService.getDecompressedInspectData(s)))
                        .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/operations/{operationId}/logs")
    @RequirePermission(resource = Resource.DOCKER_OPERATIONS, action = "read")
    public ResponseEntity<Map<String, String>> getOperationLogs(@PathVariable String operationId) {
        return operationRepository
                .findById(operationId)
                .map(
                        op ->
                                ResponseEntity.ok(
                                        Map.of("logs", op.getLogs() != null ? op.getLogs() : "")))
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Compose Deployments ====================

    @GetMapping("/hosts/{hostId}/compose")
    public ResponseEntity<Map<String, Object>> listComposeDeployments(
            @PathVariable String hostId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<ComposeDeployment> deployments =
                composeRepository.findByDockerHostIdOrderByCreatedAtDesc(hostId, pageable);

        List<ComposeDeploymentResponse> responses =
                deployments.getContent().stream()
                        .map(ComposeDeploymentResponse::fromEntity)
                        .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("content", responses);
        result.put("totalElements", deployments.getTotalElements());
        result.put("totalPages", deployments.getTotalPages());
        result.put("page", page);
        result.put("size", size);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/compose/{deploymentId}")
    @RequirePermission(resource = Resource.COMPOSE_DEPLOYMENTS, action = "read")
    public ResponseEntity<ComposeDeploymentResponse> getComposeDeployment(
            @PathVariable String deploymentId) {
        return composeRepository
                .findById(deploymentId)
                .map(d -> ResponseEntity.ok(ComposeDeploymentResponse.fromEntity(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/hosts/{hostId}/compose/project/{projectName}/history")
    @RequirePermission(
            resource = Resource.COMPOSE_DEPLOYMENTS,
            action = "list",
            hostIdParam = "hostId")
    public ResponseEntity<List<ComposeDeploymentResponse>> getProjectHistory(
            @PathVariable String hostId, @PathVariable String projectName) {

        List<ComposeDeployment> deployments =
                composeRepository.findByDockerHostIdAndProjectNameOrderByVersionDesc(
                        hostId, projectName);

        List<ComposeDeploymentResponse> responses =
                deployments.stream().map(ComposeDeploymentResponse::fromEntity).toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/hosts/{hostId}/compose/projects")
    @RequirePermission(
            resource = Resource.COMPOSE_DEPLOYMENTS,
            action = "list",
            hostIdParam = "hostId")
    public ResponseEntity<List<String>> getProjectNames(@PathVariable String hostId) {
        List<String> projects = composeRepository.findDistinctProjectNames(hostId);
        return ResponseEntity.ok(projects);
    }

    // ==================== Summary ====================

    @GetMapping("/hosts/{hostId}/summary")
    @RequirePermission(
            resource = Resource.DOCKER_OPERATIONS,
            action = "list",
            hostIdParam = "hostId")
    public ResponseEntity<Map<String, Object>> getSummary(@PathVariable String hostId) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalOperations", operationRepository.countByDockerHostId(hostId));
        summary.put("totalComposeDeployments", composeRepository.countByDockerHostId(hostId));
        return ResponseEntity.ok(summary);
    }
}
