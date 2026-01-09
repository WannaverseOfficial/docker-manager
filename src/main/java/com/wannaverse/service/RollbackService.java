package com.wannaverse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.dockerjava.api.model.*;
import com.wannaverse.persistence.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class RollbackService {
    private static final Logger log = LoggerFactory.getLogger(RollbackService.class);

    private final DockerOperationRepository operationRepository;
    private final StateSnapshotRepository snapshotRepository;
    private final ComposeDeploymentRepository composeRepository;
    private final DockerHostRepository hostRepository;
    private final DockerService dockerService;
    private final OperationTrackingService trackingService;
    private final ObjectMapper objectMapper;

    public RollbackService(
            DockerOperationRepository operationRepository,
            StateSnapshotRepository snapshotRepository,
            ComposeDeploymentRepository composeRepository,
            DockerHostRepository hostRepository,
            DockerService dockerService,
            OperationTrackingService trackingService) {
        this.operationRepository = operationRepository;
        this.snapshotRepository = snapshotRepository;
        this.composeRepository = composeRepository;
        this.hostRepository = hostRepository;
        this.dockerService = dockerService;
        this.trackingService = trackingService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    public RollbackValidation validateRollback(String operationId) {
        DockerOperation operation = operationRepository.findById(operationId).orElse(null);

        if (operation == null) {
            return new RollbackValidation(false, "Operation not found", null);
        }

        // Check if there's a BEFORE snapshot
        StateSnapshot beforeSnapshot =
                snapshotRepository
                        .findByOperationIdAndSnapshotType(
                                operationId, StateSnapshot.SnapshotType.BEFORE)
                        .orElse(null);

        if (beforeSnapshot == null) {
            return new RollbackValidation(false, "No before state snapshot available", operation);
        }

        // Check if operation type supports rollback
        if (!isRollbackSupported(operation.getOperationType())) {
            return new RollbackValidation(
                    false,
                    "Rollback not supported for operation type: " + operation.getOperationType(),
                    operation);
        }

        // Check if already rolled back
        if (operation.getStatus() == DockerOperation.OperationStatus.ROLLED_BACK) {
            return new RollbackValidation(
                    false, "Operation has already been rolled back", operation);
        }

        // For container operations, check if image is available
        if (isContainerOperation(operation.getOperationType())) {
            String imageName = beforeSnapshot.getImageName();
            if (imageName != null) {
                try {
                    DockerHost host = operation.getDockerHost();
                    DockerAPI api =
                            dockerService.dockerAPI(
                                    dockerService.createClientCached(host.getDockerHostUrl()));

                    // Try to inspect the image
                    api.inspectImage(imageName);
                } catch (Exception e) {
                    return new RollbackValidation(
                            false, "Required image not available: " + imageName, operation);
                }
            }
        }

        return new RollbackValidation(true, null, operation);
    }

    @Transactional
    public RollbackResult rollbackOperation(String operationId) {
        RollbackValidation validation = validateRollback(operationId);

        if (!validation.canRollback()) {
            return new RollbackResult(false, validation.reason(), null);
        }

        DockerOperation originalOperation = validation.operation();
        DockerHost host = originalOperation.getDockerHost();

        StateSnapshot beforeSnapshot =
                snapshotRepository
                        .findByOperationIdAndSnapshotType(
                                operationId, StateSnapshot.SnapshotType.BEFORE)
                        .orElseThrow();

        try {
            // Start rollback operation tracking
            DockerOperation rollbackOp =
                    trackingService.beginOperation(
                            host.getId(),
                            getRollbackOperationType(originalOperation.getOperationType()),
                            originalOperation.getResourceId(),
                            "Rollback: " + originalOperation.getResourceName());

            // Capture current state before rollback
            if (originalOperation.getResourceId() != null) {
                try {
                    trackingService.captureContainerState(
                            rollbackOp,
                            originalOperation.getResourceId(),
                            StateSnapshot.SnapshotType.BEFORE);
                } catch (Exception e) {
                    log.debug(
                            "Could not capture current state before rollback: {}", e.getMessage());
                }
            }

            // Perform rollback based on operation type
            String newResourceId = performRollback(host, originalOperation, beforeSnapshot);

            // Capture state after rollback
            if (newResourceId != null) {
                trackingService.captureContainerState(
                        rollbackOp, newResourceId, StateSnapshot.SnapshotType.AFTER);
            }

            // Mark original operation as rolled back
            originalOperation.setStatus(DockerOperation.OperationStatus.ROLLED_BACK);
            operationRepository.save(originalOperation);

            // Complete rollback operation
            trackingService.completeOperation(rollbackOp, true, null);

            return new RollbackResult(true, "Rollback completed successfully", newResourceId);

        } catch (Exception e) {
            log.error("Rollback failed for operation {}: {}", operationId, e.getMessage(), e);
            return new RollbackResult(false, "Rollback failed: " + e.getMessage(), null);
        }
    }

    private String performRollback(
            DockerHost host, DockerOperation operation, StateSnapshot beforeSnapshot)
            throws Exception {

        DockerAPI api =
                dockerService.dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()));

        return switch (operation.getOperationType()) {
            case CONTAINER_DELETE -> recreateContainerFromSnapshot(api, beforeSnapshot);
            case CONTAINER_STOP -> {
                api.startContainer(operation.getResourceId());
                yield operation.getResourceId();
            }
            case CONTAINER_START -> {
                api.stopContainer(operation.getResourceId());
                yield operation.getResourceId();
            }
            case CONTAINER_CREATE -> {
                try {
                    api.forceRemoveContainer(operation.getResourceId());
                } catch (Exception e) {
                    log.warn("Failed to remove container during rollback: {}", e.getMessage());
                }
                yield null;
            }
            default ->
                    throw new UnsupportedOperationException(
                            "Rollback not implemented for: " + operation.getOperationType());
        };
    }

    private String recreateContainerFromSnapshot(DockerAPI api, StateSnapshot snapshot)
            throws Exception {
        String inspectJson = trackingService.getDecompressedInspectData(snapshot);

        if (inspectJson == null) {
            throw new IllegalStateException("No inspect data available in snapshot");
        }

        JsonNode inspect = objectMapper.readTree(inspectJson);

        // Extract configuration
        String imageName = snapshot.getImageName();
        String containerName = snapshot.getResourceName();
        if (containerName != null && containerName.startsWith("/")) {
            containerName = containerName.substring(1);
        }

        // Parse environment variables
        List<String> env = new ArrayList<>();
        if (snapshot.getEnvironmentVars() != null) {
            JsonNode envNode = objectMapper.readTree(snapshot.getEnvironmentVars());
            if (envNode.isArray()) {
                envNode.forEach(e -> env.add(e.asText()));
            }
        }

        // Parse port bindings
        Map<Integer, Integer> portBindings = new HashMap<>();
        if (snapshot.getPortBindings() != null) {
            JsonNode portsNode = objectMapper.readTree(snapshot.getPortBindings());
            portsNode
                    .fields()
                    .forEachRemaining(
                            entry -> {
                                String containerPort = entry.getKey().split("/")[0];
                                JsonNode bindings = entry.getValue();
                                if (bindings.isArray() && bindings.size() > 0) {
                                    String hostPort = bindings.get(0).get("HostPort").asText();
                                    try {
                                        portBindings.put(
                                                Integer.parseInt(containerPort),
                                                Integer.parseInt(hostPort));
                                    } catch (NumberFormatException e) {
                                        log.warn(
                                                "Could not parse port binding: {} -> {}",
                                                containerPort,
                                                hostPort);
                                    }
                                }
                            });
        }

        // Parse volume bindings
        List<String> volumeBindings = new ArrayList<>();
        if (snapshot.getVolumeBindings() != null) {
            JsonNode volumesNode = objectMapper.readTree(snapshot.getVolumeBindings());
            if (volumesNode.isArray()) {
                volumesNode.forEach(v -> volumeBindings.add(v.asText()));
            }
        }

        // Parse network
        String networkName = null;
        if (snapshot.getNetworkSettings() != null) {
            JsonNode networkNode = objectMapper.readTree(snapshot.getNetworkSettings());
            JsonNode networks = networkNode.get("Networks");
            if (networks != null && networks.isObject()) {
                Iterator<String> networkNames = networks.fieldNames();
                if (networkNames.hasNext()) {
                    networkName = networkNames.next();
                }
            }
        }

        // Create container
        var response =
                api.createContainer(
                        imageName,
                        containerName,
                        env.isEmpty() ? null : env,
                        portBindings.isEmpty() ? null : portBindings,
                        volumeBindings.isEmpty() ? null : volumeBindings,
                        networkName);

        String newContainerId = response.getId();

        // Start the container
        api.startContainer(newContainerId);

        log.info("Recreated container {} from snapshot", newContainerId);

        return newContainerId;
    }

    @Transactional
    public RollbackResult rollbackComposeDeployment(String deploymentId, int targetVersion) {
        ComposeDeployment current = composeRepository.findById(deploymentId).orElse(null);

        if (current == null) {
            return new RollbackResult(false, "Deployment not found", null);
        }

        DockerHost host = current.getDockerHost();
        String projectName = current.getProjectName();

        // Find target version
        ComposeDeployment target =
                composeRepository
                        .findByDockerHostIdAndProjectNameAndVersion(
                                host.getId(), projectName, targetVersion)
                        .orElse(null);

        if (target == null) {
            return new RollbackResult(false, "Target version not found: " + targetVersion, null);
        }

        try {
            // Create new deployment from target version
            ComposeDeployment newDeployment =
                    trackingService.createComposeDeployment(
                            host.getId(),
                            projectName,
                            target.getComposeContent(),
                            target.getEnvFileContent(),
                            target.getCommitSha(),
                            target.getGitRepository());

            // Deploy compose
            List<String> logs =
                    dockerService.deployCompose(
                            host.getDockerHostUrl(), target.getComposeContent(), projectName);

            // Update deployment status
            newDeployment.setLogs(String.join("\n", logs));
            trackingService.completeComposeDeployment(newDeployment, true, null);

            // Mark original as rolled back
            current.setStatus(ComposeDeployment.DeploymentStatus.ROLLED_BACK);
            composeRepository.save(current);

            return new RollbackResult(
                    true, "Rolled back to version " + targetVersion, newDeployment.getId());

        } catch (Exception e) {
            log.error("Compose rollback failed: {}", e.getMessage(), e);
            return new RollbackResult(false, "Rollback failed: " + e.getMessage(), null);
        }
    }

    public List<RollbackPoint> getRollbackPoints(
            String hostId, String resourceId, String resourceType) {
        List<DockerOperation> operations =
                operationRepository.findByDockerHostIdAndResourceIdOrderByCreatedAtDesc(
                        hostId, resourceId);

        return operations.stream()
                .filter(
                        op ->
                                snapshotRepository
                                        .findByOperationIdAndSnapshotType(
                                                op.getId(), StateSnapshot.SnapshotType.BEFORE)
                                        .isPresent())
                .filter(op -> op.getStatus() != DockerOperation.OperationStatus.ROLLED_BACK)
                .map(
                        op -> {
                            RollbackValidation validation = validateRollback(op.getId());
                            return new RollbackPoint(
                                    op.getId(),
                                    op.getOperationType().name(),
                                    op.getResourceName(),
                                    op.getCreatedAt(),
                                    op.getUsername(),
                                    validation.canRollback(),
                                    validation.reason());
                        })
                .toList();
    }

    private boolean isRollbackSupported(DockerOperation.OperationType type) {
        return switch (type) {
            case CONTAINER_CREATE, CONTAINER_DELETE, CONTAINER_START, CONTAINER_STOP -> true;
            default -> false;
        };
    }

    private boolean isContainerOperation(DockerOperation.OperationType type) {
        return type.name().startsWith("CONTAINER_");
    }

    private DockerOperation.OperationType getRollbackOperationType(
            DockerOperation.OperationType original) {
        return switch (original) {
            case CONTAINER_DELETE -> DockerOperation.OperationType.CONTAINER_CREATE;
            case CONTAINER_CREATE -> DockerOperation.OperationType.CONTAINER_DELETE;
            case CONTAINER_START -> DockerOperation.OperationType.CONTAINER_STOP;
            case CONTAINER_STOP -> DockerOperation.OperationType.CONTAINER_START;
            default -> original;
        };
    }

    // Result records
    public record RollbackValidation(
            boolean canRollback, String reason, DockerOperation operation) {}

    public record RollbackResult(boolean success, String message, String newResourceId) {}

    public record RollbackPoint(
            String operationId,
            String operationType,
            String resourceName,
            long timestamp,
            String username,
            boolean canRollback,
            String reason) {}
}
