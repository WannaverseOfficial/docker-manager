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
    private final ContainerSpecService specService;
    private final StackService stackService;

    public RollbackService(
            DockerOperationRepository operationRepository,
            StateSnapshotRepository snapshotRepository,
            ComposeDeploymentRepository composeRepository,
            DockerHostRepository hostRepository,
            DockerService dockerService,
            OperationTrackingService trackingService,
            ContainerSpecService specService,
            StackService stackService) {
        this.specService = specService;
        this.stackService = stackService;
        this.operationRepository = operationRepository;
        this.snapshotRepository = snapshotRepository;
        this.composeRepository = composeRepository;
        this.hostRepository = hostRepository;
        this.dockerService = dockerService;
        this.trackingService = trackingService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        this.objectMapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
    }

    public RollbackValidation validateRollback(String operationId) {
        DockerOperation operation = operationRepository.findById(operationId).orElse(null);

        if (operation == null) {
            return new RollbackValidation(false, "Operation not found", null);
        }

        StateSnapshot beforeSnapshot =
                snapshotRepository
                        .findByOperationIdAndSnapshotType(
                                operationId, StateSnapshot.SnapshotType.BEFORE)
                        .orElse(null);

        // Undoing a create just removes the container, so it needs no earlier state.
        boolean needsBefore =
                operation.getOperationType() != DockerOperation.OperationType.CONTAINER_CREATE;
        if (beforeSnapshot == null && needsBefore) {
            return new RollbackValidation(false, "No before state snapshot available", operation);
        }

        if (!isRollbackSupported(operation.getOperationType())) {
            return new RollbackValidation(
                    false,
                    "Rollback not supported for operation type: " + operation.getOperationType(),
                    operation);
        }

        if (operation.getStatus() == DockerOperation.OperationStatus.ROLLED_BACK) {
            return new RollbackValidation(
                    false, "Operation has already been rolled back", operation);
        }

        if (beforeSnapshot != null && isContainerOperation(operation.getOperationType())) {
            String imageName = beforeSnapshot.getImageName();
            if (imageName != null) {
                try {
                    DockerHost host = operation.getDockerHost();
                    DockerAPI api =
                            dockerService.dockerAPI(
                                    dockerService.createClientCached(host.getDockerHostUrl()));

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
                        .orElse(null);

        try {
            DockerOperation rollbackOp =
                    trackingService.beginOperation(
                            host.getId(),
                            getRollbackOperationType(originalOperation.getOperationType()),
                            originalOperation.getResourceId(),
                            "Rollback: " + originalOperation.getResourceName());

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

            String newResourceId = performRollback(host, originalOperation, beforeSnapshot);

            if (newResourceId != null) {
                trackingService.captureContainerState(
                        rollbackOp, newResourceId, StateSnapshot.SnapshotType.AFTER);
            }

            originalOperation.setStatus(DockerOperation.OperationStatus.ROLLED_BACK);
            operationRepository.save(originalOperation);

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
            case CONTAINER_UPDATE ->
                    specService.recreate(
                            api,
                            operation.getResourceId(),
                            specService.specFrom(inspectFrom(beforeSnapshot)),
                            false,
                            // Restores the container's own recorded settings.
                            true);
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

    /**
     * Recreates a deleted container from its full `docker inspect` snapshot, so its restart policy,
     * command, labels, capabilities, limits, UDP ports and all networks come back too. (Previously
     * only the image, environment, TCP ports and one network were restored, and volume binds failed
     * to parse.)
     */
    private String recreateContainerFromSnapshot(DockerAPI api, StateSnapshot snapshot)
            throws Exception {
        com.github.dockerjava.api.command.InspectContainerResponse inspect = inspectFrom(snapshot);
        return specService.createFromInspect(api, inspect);
    }

    private com.github.dockerjava.api.command.InspectContainerResponse inspectFrom(
            StateSnapshot snapshot) throws Exception {
        String inspectJson = trackingService.getDecompressedInspectData(snapshot);
        if (inspectJson == null) {
            throw new IllegalStateException("No inspect data available in snapshot");
        }
        JsonNode tree = objectMapper.readTree(inspectJson);
        // Mounts are restored from the top-level "Mounts" list; the HostConfig copies may have
        // been stored in a form that does not read back.
        if (tree.get("HostConfig") instanceof com.fasterxml.jackson.databind.node.ObjectNode host) {
            host.remove("Binds");
            host.remove("Mounts");
        }
        return objectMapper.treeToValue(
                tree, com.github.dockerjava.api.command.InspectContainerResponse.class);
    }

    /** Redeploys an earlier version of a stack (see {@link StackService#rollback}). */
    public RollbackResult rollbackComposeDeployment(String deploymentId, int targetVersion) {
        ComposeDeployment current = composeRepository.findById(deploymentId).orElse(null);
        if (current == null) {
            return new RollbackResult(false, "Deployment not found", null);
        }
        try {
            var ctx = com.wannaverse.security.SecurityContextHolder.getContext();
            var job =
                    stackService.rollback(
                            current.getDockerHost().getId(),
                            current.getProjectName(),
                            targetVersion,
                            ctx != null ? ctx.getUsername() : null,
                            ctx != null && ctx.isAdmin());
            return new RollbackResult(true, "Rolling back to version " + targetVersion, job.id);
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
            case CONTAINER_CREATE,
                    CONTAINER_DELETE,
                    CONTAINER_START,
                    CONTAINER_STOP,
                    CONTAINER_UPDATE ->
                    true;
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
