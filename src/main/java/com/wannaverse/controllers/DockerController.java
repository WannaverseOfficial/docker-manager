package com.wannaverse.controllers;

import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.model.Statistics;
import com.wannaverse.dto.ComposeDeployRequest;
import com.wannaverse.dto.ContainerStatsResponse;
import com.wannaverse.dto.CreateContainerRequest;
import com.wannaverse.dto.ExecCommandRequest;
import com.wannaverse.dto.HostInfoResponse;
import com.wannaverse.persistence.ContainerDriftStatus;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.DockerOperation;
import com.wannaverse.persistence.Resource;
import com.wannaverse.persistence.StateSnapshot;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.InputValidator;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.DockerAPI;
import com.wannaverse.service.DockerRegistryService;
import com.wannaverse.service.DockerService;
import com.wannaverse.service.DriftDetectionService;
import com.wannaverse.service.ImagePolicyService;
import com.wannaverse.service.OperationTrackingService;
import com.wannaverse.service.PermissionService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/docker")
public class DockerController {
    private final DockerService dockerService;
    private final DockerHostRepository hostRepository;
    private final PermissionService permissionService;
    private final OperationTrackingService trackingService;
    private final ImagePolicyService imagePolicyService;
    private final DockerRegistryService registryService;
    private final DriftDetectionService driftDetectionService;

    public DockerController(
            DockerService dockerService,
            DockerHostRepository hostRepository,
            PermissionService permissionService,
            OperationTrackingService trackingService,
            ImagePolicyService imagePolicyService,
            DockerRegistryService registryService,
            DriftDetectionService driftDetectionService) {
        this.dockerService = dockerService;
        this.hostRepository = hostRepository;
        this.permissionService = permissionService;
        this.trackingService = trackingService;
        this.imagePolicyService = imagePolicyService;
        this.registryService = registryService;
        this.driftDetectionService = driftDetectionService;
    }

    // ==================== Helper Method ====================

    private DockerAPI getDockerAPI(String hostId) {
        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Docker host not found: " + hostId));
        return dockerService.dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()));
    }

    // ==================== Docker Host Management ====================

    @GetMapping("/hosts")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "list")
    public ResponseEntity<List<DockerHost>> getAllHosts() {
        return ResponseEntity.ok(hostRepository.findAll());
    }

    @GetMapping("/hosts/{hostId}")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<DockerHost> getHost(@PathVariable String hostId) {
        return hostRepository
                .findById(hostId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/hosts")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "create")
    @Auditable(resource = Resource.DOCKER_HOSTS, action = "create", captureRequestBody = true)
    public ResponseEntity<DockerHost> addHost(@RequestBody DockerHost host) {
        // Validate Docker host URL to prevent SSRF attacks
        if (!InputValidator.isValidDockerHostUrl(host.getDockerHostUrl())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid Docker host URL format. Must be unix:///path/to/socket.sock, "
                            + "tcp://host:port, or ssh://user@host");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(hostRepository.save(host));
    }

    @DeleteMapping("/hosts/{hostId}")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "delete", hostIdParam = "hostId")
    @Auditable(resource = Resource.DOCKER_HOSTS, action = "delete", resourceIdParam = "hostId")
    public ResponseEntity<Void> removeHost(@PathVariable String hostId) {
        hostRepository
                .findById(hostId)
                .ifPresent(
                        host -> {
                            dockerService.close(host.getDockerHostUrl());
                            hostRepository.deleteById(hostId);
                        });
        return ResponseEntity.noContent().build();
    }

    // ==================== Connection ====================

    @GetMapping("/hosts/{hostId}/ping")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "ping", hostIdParam = "hostId")
    public ResponseEntity<Map<String, Boolean>> ping(@PathVariable String hostId) {
        boolean result = getDockerAPI(hostId).ping();
        return ResponseEntity.ok(Map.of("connected", result));
    }

    // ==================== Container Operations ====================

    @GetMapping("/hosts/{hostId}/containers")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<Container>> listContainers(
            @PathVariable String hostId, @RequestParam(defaultValue = "false") boolean all) {
        DockerAPI api = getDockerAPI(hostId);
        List<Container> containers = all ? api.listAllContainers() : api.listRunningContainers();

        // Filter by resource-level permissions
        String userId = SecurityContextHolder.getCurrentUserId();
        if (userId != null) {
            Set<String> allowedIds =
                    permissionService.getAllowedResourceIds(
                            userId, Resource.CONTAINERS, "list", hostId);
            if (allowedIds != null) {
                if (allowedIds.isEmpty()) {
                    return ResponseEntity.ok(Collections.emptyList());
                }
                containers = filterContainers(containers, allowedIds);
            }
        }
        return ResponseEntity.ok(containers);
    }

    private List<Container> filterContainers(List<Container> containers, Set<String> allowedIds) {
        List<Container> filtered = new ArrayList<>();
        for (Container c : containers) {
            // Check container ID (short and full)
            if (allowedIds.contains(c.getId()) || allowedIds.contains(c.getId().substring(0, 12))) {
                filtered.add(c);
                continue;
            }
            // Check container names (Docker prefixes with /)
            if (c.getNames() != null) {
                for (String name : c.getNames()) {
                    String cleanName = name.startsWith("/") ? name.substring(1) : name;
                    if (allowedIds.contains(cleanName) || allowedIds.contains(name)) {
                        filtered.add(c);
                        break;
                    }
                }
            }
        }
        return filtered;
    }

    @GetMapping("/hosts/{hostId}/containers/{containerId}")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "inspect",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    public ResponseEntity<InspectContainerResponse> inspectContainer(
            @PathVariable String hostId, @PathVariable String containerId) {
        return ResponseEntity.ok(getDockerAPI(hostId).inspectContainer(containerId));
    }

    @GetMapping("/hosts/{hostId}/containers/{containerId}/health")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "health",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    public ResponseEntity<HealthState> getContainerHealth(
            @PathVariable String hostId, @PathVariable String containerId) {
        HealthState health = getDockerAPI(hostId).getContainerHealth(containerId);
        if (health == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(health);
    }

    @GetMapping("/hosts/{hostId}/containers/{containerId}/logs")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "logs",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    public ResponseEntity<Map<String, String>> getContainerLogs(
            @PathVariable String hostId,
            @PathVariable String containerId,
            @RequestParam(defaultValue = "500") int tail,
            @RequestParam(defaultValue = "false") boolean timestamps) {
        String logs = getDockerAPI(hostId).getContainerLogs(containerId, tail, timestamps);
        return ResponseEntity.ok(Map.of("logs", logs));
    }

    @GetMapping("/hosts/{hostId}/containers/{containerId}/processes")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "processes",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    public ResponseEntity<TopContainerResponse> getContainerProcesses(
            @PathVariable String hostId, @PathVariable String containerId) {
        return ResponseEntity.ok(getDockerAPI(hostId).getContainerProcesses(containerId));
    }

    @GetMapping("/hosts/{hostId}/containers/{containerId}/security/root")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "inspect",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    public ResponseEntity<Map<String, Boolean>> isRunningAsRoot(
            @PathVariable String hostId, @PathVariable String containerId) {
        return ResponseEntity.ok(
                Map.of("runningAsRoot", getDockerAPI(hostId).isRunningAsRoot(containerId)));
    }

    @GetMapping("/hosts/{hostId}/containers/{containerId}/security/privileged")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "inspect",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    public ResponseEntity<Map<String, Boolean>> isPrivileged(
            @PathVariable String hostId, @PathVariable String containerId) {
        return ResponseEntity.ok(
                Map.of("privileged", getDockerAPI(hostId).isPrivileged(containerId)));
    }

    @PostMapping("/hosts/{hostId}/containers")
    @RequirePermission(resource = Resource.CONTAINERS, action = "create", hostIdParam = "hostId")
    @Auditable(resource = Resource.CONTAINERS, action = "create", captureRequestBody = true)
    public ResponseEntity<CreateContainerResponse> createContainer(
            @PathVariable String hostId, @RequestBody CreateContainerRequest request) {
        // Validate container name
        if (request.getContainerName() != null
                && !InputValidator.isValidContainerName(request.getContainerName())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid container name. Use only alphanumeric characters, underscores, "
                            + "periods, and hyphens.");
        }

        // Validate image name
        if (!InputValidator.isValidImageName(request.getImageName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image name format.");
        }

        // Validate image against policies before creating container
        imagePolicyService.validateImageForContainerCreation(request.getImageName());

        // Pull image if it doesn't exist locally
        DockerAPI dockerAPI = getDockerAPI(hostId);
        try {
            dockerAPI.inspectImage(request.getImageName());
        } catch (NotFoundException e) {
            // Image doesn't exist locally, pull it first
            try {
                var authConfig = registryService.getAuthConfig(request.getImageName());
                dockerAPI.pullImage(request.getImageName(), authConfig);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Image pull was interrupted");
            }
        }

        DockerOperation operation =
                trackingService.beginOperation(
                        hostId,
                        DockerOperation.OperationType.CONTAINER_CREATE,
                        null,
                        request.getContainerName());

        try {
            CreateContainerResponse response =
                    dockerAPI.createContainer(
                            request.getImageName(),
                            request.getContainerName(),
                            request.getEnvironmentVariables(),
                            request.getPortBindings(),
                            request.getVolumeBindings(),
                            request.getNetworkName(),
                            null, // extraHosts
                            request.getUser());

            operation.setResourceId(response.getId());
            trackingService.captureContainerState(
                    operation, response.getId(), StateSnapshot.SnapshotType.AFTER, hostId);
            trackingService.completeOperation(operation, true, null);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            trackingService.completeOperation(operation, false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/hosts/{hostId}/compose")
    @RequirePermission(resource = Resource.CONTAINERS, action = "create", hostIdParam = "hostId")
    @Auditable(resource = Resource.CONTAINERS, action = "compose-deploy", captureRequestBody = true)
    public ResponseEntity<Map<String, Object>> deployCompose(
            @PathVariable String hostId, @RequestBody ComposeDeployRequest request) {
        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Docker host not found: " + hostId));

        String projectName =
                request.getProjectName() != null ? request.getProjectName() : "compose";

        DockerOperation operation =
                trackingService.beginOperation(
                        hostId, DockerOperation.OperationType.COMPOSE_UP, projectName, projectName);

        trackingService.captureComposeState(
                operation,
                projectName,
                request.getComposeContent(),
                StateSnapshot.SnapshotType.BEFORE);

        try {
            List<String> output =
                    dockerService.deployCompose(
                            host.getDockerHostUrl(),
                            request.getComposeContent(),
                            request.getProjectName());

            operation.setLogs(String.join("\n", output));
            trackingService.captureComposeState(
                    operation,
                    projectName,
                    request.getComposeContent(),
                    StateSnapshot.SnapshotType.AFTER);
            trackingService.completeOperation(operation, true, null);

            // Also create ComposeDeployment record
            trackingService.createComposeDeployment(
                    hostId, projectName, request.getComposeContent(), null, null, null);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("success", true, "output", output));
        } catch (Exception e) {
            trackingService.completeOperation(operation, false, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/hosts/{hostId}/containers/{containerId}/start")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "start",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(resource = Resource.CONTAINERS, action = "start", resourceIdParam = "containerId")
    public ResponseEntity<Void> startContainer(
            @PathVariable String hostId, @PathVariable String containerId) {
        DockerOperation operation =
                trackingService.beginOperation(
                        hostId,
                        DockerOperation.OperationType.CONTAINER_START,
                        containerId,
                        containerId);
        trackingService.captureContainerState(
                operation, containerId, StateSnapshot.SnapshotType.BEFORE, hostId);

        try {
            getDockerAPI(hostId).startContainer(containerId);
            trackingService.captureContainerState(
                    operation, containerId, StateSnapshot.SnapshotType.AFTER, hostId);
            trackingService.completeOperation(operation, true, null);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            trackingService.completeOperation(operation, false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/hosts/{hostId}/containers/{containerId}/stop")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "stop",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(resource = Resource.CONTAINERS, action = "stop", resourceIdParam = "containerId")
    public ResponseEntity<Void> stopContainer(
            @PathVariable String hostId,
            @PathVariable String containerId,
            @RequestParam(required = false) Integer timeout) {
        DockerOperation operation =
                trackingService.beginOperation(
                        hostId,
                        DockerOperation.OperationType.CONTAINER_STOP,
                        containerId,
                        containerId);
        trackingService.captureContainerState(
                operation, containerId, StateSnapshot.SnapshotType.BEFORE, hostId);

        try {
            DockerAPI api = getDockerAPI(hostId);
            if (timeout != null) {
                api.stopContainer(containerId, timeout);
            } else {
                api.stopContainer(containerId);
            }
            trackingService.captureContainerState(
                    operation, containerId, StateSnapshot.SnapshotType.AFTER, hostId);
            trackingService.completeOperation(operation, true, null);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            trackingService.completeOperation(operation, false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/hosts/{hostId}/containers/{containerId}/restart")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "restart",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(resource = Resource.CONTAINERS, action = "restart", resourceIdParam = "containerId")
    public ResponseEntity<Void> restartContainer(
            @PathVariable String hostId, @PathVariable String containerId) {
        DockerOperation operation =
                trackingService.beginOperation(
                        hostId,
                        DockerOperation.OperationType.CONTAINER_RESTART,
                        containerId,
                        containerId);
        trackingService.captureContainerState(
                operation, containerId, StateSnapshot.SnapshotType.BEFORE, hostId);

        try {
            getDockerAPI(hostId).restartContainer(containerId);
            trackingService.captureContainerState(
                    operation, containerId, StateSnapshot.SnapshotType.AFTER, hostId);
            trackingService.completeOperation(operation, true, null);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            trackingService.completeOperation(operation, false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/hosts/{hostId}/containers/{containerId}/pause")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "pause",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(resource = Resource.CONTAINERS, action = "pause", resourceIdParam = "containerId")
    public ResponseEntity<Void> pauseContainer(
            @PathVariable String hostId, @PathVariable String containerId) {
        getDockerAPI(hostId).pauseContainer(containerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/hosts/{hostId}/containers/{containerId}/unpause")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "unpause",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(resource = Resource.CONTAINERS, action = "unpause", resourceIdParam = "containerId")
    public ResponseEntity<Void> unpauseContainer(
            @PathVariable String hostId, @PathVariable String containerId) {
        getDockerAPI(hostId).unpauseContainer(containerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/hosts/{hostId}/containers/{containerId}/kill")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "kill",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(resource = Resource.CONTAINERS, action = "kill", resourceIdParam = "containerId")
    public ResponseEntity<Void> killContainer(
            @PathVariable String hostId,
            @PathVariable String containerId,
            @RequestParam(required = false) String signal) {
        DockerAPI api = getDockerAPI(hostId);
        if (signal != null) {
            api.killContainer(containerId, signal);
        } else {
            api.killContainer(containerId);
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/hosts/{hostId}/containers/{containerId}")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "delete",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(resource = Resource.CONTAINERS, action = "delete", resourceIdParam = "containerId")
    public ResponseEntity<Void> removeContainer(
            @PathVariable String hostId,
            @PathVariable String containerId,
            @RequestParam(defaultValue = "false") boolean force) {
        DockerOperation operation =
                trackingService.beginOperation(
                        hostId,
                        DockerOperation.OperationType.CONTAINER_DELETE,
                        containerId,
                        containerId);
        trackingService.captureContainerState(
                operation, containerId, StateSnapshot.SnapshotType.BEFORE, hostId);

        try {
            DockerAPI api = getDockerAPI(hostId);
            if (force) {
                api.forceRemoveContainer(containerId);
            } else {
                api.removeContainer(containerId);
            }
            trackingService.completeOperation(operation, true, null);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            trackingService.completeOperation(operation, false, e.getMessage());
            throw e;
        }
    }

    @PutMapping("/hosts/{hostId}/containers/{containerId}/rename")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "rename",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(resource = Resource.CONTAINERS, action = "rename", resourceIdParam = "containerId")
    public ResponseEntity<Void> renameContainer(
            @PathVariable String hostId,
            @PathVariable String containerId,
            @RequestParam String newName) {
        getDockerAPI(hostId).renameContainer(containerId, newName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/hosts/{hostId}/containers/{containerId}/exec")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "exec",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(
            resource = Resource.CONTAINERS,
            action = "exec",
            resourceIdParam = "containerId",
            captureRequestBody = true)
    public ResponseEntity<Map<String, String>> execCommand(
            @PathVariable String hostId,
            @PathVariable String containerId,
            @RequestBody ExecCommandRequest request) {
        // Validate command to prevent dangerous operations
        if (!InputValidator.isCommandSafe(request.getCommand())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Command not allowed. Dangerous commands and shell operators are blocked "
                            + "for security reasons.");
        }

        String output = getDockerAPI(hostId).execCommand(containerId, request.getCommand());
        return ResponseEntity.ok(Map.of("output", output));
    }

    // ==================== Image Operations ====================

    @GetMapping("/hosts/{hostId}/images")
    @RequirePermission(resource = Resource.IMAGES, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<Image>> listImages(
            @PathVariable String hostId, @RequestParam(defaultValue = "false") boolean dangling) {
        DockerAPI api = getDockerAPI(hostId);
        List<Image> images = dangling ? api.listDanglingImages() : api.listImages();

        // Filter by resource-level permissions
        String userId = SecurityContextHolder.getCurrentUserId();
        if (userId != null) {
            Set<String> allowedIds =
                    permissionService.getAllowedResourceIds(
                            userId, Resource.IMAGES, "list", hostId);
            if (allowedIds != null) {
                if (allowedIds.isEmpty()) {
                    return ResponseEntity.ok(Collections.emptyList());
                }
                images = filterImages(images, allowedIds);
            }
        }
        return ResponseEntity.ok(images);
    }

    private List<Image> filterImages(List<Image> images, Set<String> allowedIds) {
        List<Image> filtered = new ArrayList<>();
        for (Image img : images) {
            // Check image ID
            if (allowedIds.contains(img.getId())) {
                filtered.add(img);
                continue;
            }
            // Check repo tags (e.g., nginx:latest)
            if (img.getRepoTags() != null) {
                for (String tag : img.getRepoTags()) {
                    if (allowedIds.contains(tag)) {
                        filtered.add(img);
                        break;
                    }
                    // Also check just the repo name without tag
                    String repoName = tag.contains(":") ? tag.split(":")[0] : tag;
                    if (allowedIds.contains(repoName)) {
                        filtered.add(img);
                        break;
                    }
                }
            }
        }
        return filtered;
    }

    @GetMapping("/hosts/{hostId}/images/{imageId}")
    @RequirePermission(
            resource = Resource.IMAGES,
            action = "inspect",
            hostIdParam = "hostId",
            resourceIdParam = "imageId")
    public ResponseEntity<InspectImageResponse> inspectImage(
            @PathVariable String hostId, @PathVariable String imageId) {
        return ResponseEntity.ok(getDockerAPI(hostId).inspectImage(imageId));
    }

    @PostMapping("/hosts/{hostId}/images/pull")
    @RequirePermission(resource = Resource.IMAGES, action = "pull", hostIdParam = "hostId")
    @Auditable(resource = Resource.IMAGES, action = "pull", resourceIdParam = "imageName")
    public ResponseEntity<Map<String, String>> pullImage(
            @PathVariable String hostId, @RequestParam String imageName)
            throws InterruptedException {
        // Validate image against policies before pulling
        imagePolicyService.validateImageForPull(imageName);

        // Get auth config from registry if available
        var authConfig = registryService.getAuthConfig(imageName);
        getDockerAPI(hostId).pullImage(imageName, authConfig);
        return ResponseEntity.ok(Map.of("status", "pulled", "image", imageName));
    }

    @DeleteMapping("/hosts/{hostId}/images/{imageId}")
    @RequirePermission(
            resource = Resource.IMAGES,
            action = "delete",
            hostIdParam = "hostId",
            resourceIdParam = "imageId")
    @Auditable(resource = Resource.IMAGES, action = "delete", resourceIdParam = "imageId")
    public ResponseEntity<Void> removeImage(
            @PathVariable String hostId,
            @PathVariable String imageId,
            @RequestParam(defaultValue = "false") boolean force) {
        DockerAPI api = getDockerAPI(hostId);
        if (force) {
            api.forceRemoveImage(imageId);
        } else {
            api.removeImage(imageId);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/hosts/{hostId}/images/dangling")
    @RequirePermission(resource = Resource.IMAGES, action = "delete", hostIdParam = "hostId")
    @Auditable(resource = Resource.IMAGES, action = "cleanup_dangling")
    public ResponseEntity<Map<String, Object>> cleanupDanglingImages(@PathVariable String hostId) {
        DockerAPI api = getDockerAPI(hostId);
        List<Image> dangling = api.listDanglingImages();

        int deleted = 0;
        List<String> errors = new ArrayList<>();

        for (Image image : dangling) {
            try {
                api.removeImage(image.getId());
                deleted++;
            } catch (Exception e) {
                errors.add(image.getId().substring(0, 12) + ": " + e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("deleted", deleted);
        result.put("errors", errors);
        return ResponseEntity.ok(result);
    }

    // ==================== Volume Operations ====================

    @GetMapping("/hosts/{hostId}/volumes")
    @RequirePermission(resource = Resource.VOLUMES, action = "list", hostIdParam = "hostId")
    public ResponseEntity<ListVolumesResponse> listVolumes(@PathVariable String hostId) {
        ListVolumesResponse response = getDockerAPI(hostId).listVolumes();

        // Filter by resource-level permissions
        String userId = SecurityContextHolder.getCurrentUserId();
        if (userId != null && response.getVolumes() != null) {
            Set<String> allowedIds =
                    permissionService.getAllowedResourceIds(
                            userId, Resource.VOLUMES, "list", hostId);
            if (allowedIds != null) {
                if (allowedIds.isEmpty()) {
                    response.getVolumes().clear();
                } else {
                    response.getVolumes().removeIf(v -> !allowedIds.contains(v.getName()));
                }
            }
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/hosts/{hostId}/volumes")
    @RequirePermission(resource = Resource.VOLUMES, action = "create", hostIdParam = "hostId")
    @Auditable(resource = Resource.VOLUMES, action = "create", resourceIdParam = "volumeName")
    public ResponseEntity<CreateVolumeResponse> createVolume(
            @PathVariable String hostId, @RequestParam String volumeName) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(getDockerAPI(hostId).createVolume(volumeName));
    }

    @DeleteMapping("/hosts/{hostId}/volumes/{volumeName}")
    @RequirePermission(
            resource = Resource.VOLUMES,
            action = "delete",
            hostIdParam = "hostId",
            resourceIdParam = "volumeName")
    @Auditable(resource = Resource.VOLUMES, action = "delete", resourceIdParam = "volumeName")
    public ResponseEntity<Void> removeVolume(
            @PathVariable String hostId, @PathVariable String volumeName) {
        getDockerAPI(hostId).removeVolume(volumeName);
        return ResponseEntity.noContent().build();
    }

    // ==================== Network Operations ====================

    @GetMapping("/hosts/{hostId}/networks")
    @RequirePermission(resource = Resource.NETWORKS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<Network>> listNetworks(@PathVariable String hostId) {
        List<Network> networks = getDockerAPI(hostId).listNetworks();

        // Filter by resource-level permissions
        String userId = SecurityContextHolder.getCurrentUserId();
        if (userId != null) {
            Set<String> allowedIds =
                    permissionService.getAllowedResourceIds(
                            userId, Resource.NETWORKS, "list", hostId);
            if (allowedIds != null) {
                if (allowedIds.isEmpty()) {
                    return ResponseEntity.ok(Collections.emptyList());
                }
                networks =
                        networks.stream()
                                .filter(
                                        n ->
                                                allowedIds.contains(n.getName())
                                                        || allowedIds.contains(n.getId()))
                                .toList();
            }
        }
        return ResponseEntity.ok(networks);
    }

    @GetMapping("/hosts/{hostId}/networks/{networkId}")
    @RequirePermission(
            resource = Resource.NETWORKS,
            action = "inspect",
            hostIdParam = "hostId",
            resourceIdParam = "networkId")
    public ResponseEntity<Network> inspectNetwork(
            @PathVariable String hostId, @PathVariable String networkId) {
        return ResponseEntity.ok(getDockerAPI(hostId).inspectNetwork(networkId));
    }

    @PostMapping("/hosts/{hostId}/networks")
    @RequirePermission(resource = Resource.NETWORKS, action = "create", hostIdParam = "hostId")
    @Auditable(resource = Resource.NETWORKS, action = "create", resourceIdParam = "networkName")
    public ResponseEntity<CreateNetworkResponse> createNetwork(
            @PathVariable String hostId, @RequestParam String networkName) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(getDockerAPI(hostId).createNetwork(networkName));
    }

    @DeleteMapping("/hosts/{hostId}/networks/{networkId}")
    @RequirePermission(
            resource = Resource.NETWORKS,
            action = "delete",
            hostIdParam = "hostId",
            resourceIdParam = "networkId")
    @Auditable(resource = Resource.NETWORKS, action = "delete", resourceIdParam = "networkId")
    public ResponseEntity<Void> removeNetwork(
            @PathVariable String hostId, @PathVariable String networkId) {
        getDockerAPI(hostId).removeNetwork(networkId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/hosts/{hostId}/networks/{networkId}/connect")
    @RequirePermission(
            resource = Resource.NETWORKS,
            action = "connect",
            hostIdParam = "hostId",
            resourceIdParam = "networkId")
    @Auditable(resource = Resource.NETWORKS, action = "connect", resourceIdParam = "networkId")
    public ResponseEntity<Void> connectContainerToNetwork(
            @PathVariable String hostId,
            @PathVariable String networkId,
            @RequestParam String containerId) {
        getDockerAPI(hostId).connectContainerToNetwork(networkId, containerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/hosts/{hostId}/networks/{networkId}/disconnect")
    @RequirePermission(
            resource = Resource.NETWORKS,
            action = "disconnect",
            hostIdParam = "hostId",
            resourceIdParam = "networkId")
    @Auditable(resource = Resource.NETWORKS, action = "disconnect", resourceIdParam = "networkId")
    public ResponseEntity<Void> disconnectContainerFromNetwork(
            @PathVariable String hostId,
            @PathVariable String networkId,
            @RequestParam String containerId) {
        getDockerAPI(hostId).disconnectContainerFromNetwork(networkId, containerId);
        return ResponseEntity.ok().build();
    }

    // ==================== Stats & Info ====================

    @GetMapping("/hosts/{hostId}/info")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<HostInfoResponse> getHostInfo(@PathVariable String hostId) {
        DockerAPI api = getDockerAPI(hostId);
        Info info = api.getHostInfo();

        HostInfoResponse response =
                HostInfoResponse.builder()
                        .dockerVersion(info.getServerVersion())
                        .operatingSystem(info.getOperatingSystem())
                        .osType(info.getOsType())
                        .architecture(info.getArchitecture())
                        .kernelVersion(info.getKernelVersion())
                        .hostname(info.getName())
                        .totalMemory(info.getMemTotal())
                        .cpus(info.getNCPU())
                        .containersTotal(info.getContainers())
                        .containersRunning(info.getContainersRunning())
                        .containersPaused(info.getContainersPaused())
                        .containersStopped(info.getContainersStopped())
                        .imagesTotal(info.getImages())
                        .storageDriver(info.getDriver())
                        .dockerRootDir(info.getDockerRootDir())
                        .serverTime(info.getSystemTime())
                        .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/hosts/{hostId}/stats")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<ContainerStatsResponse>> getAllContainerStats(
            @PathVariable String hostId) {
        DockerAPI api = getDockerAPI(hostId);
        List<Container> runningContainers = api.listRunningContainers();

        List<ContainerStatsResponse> statsList = new ArrayList<>();
        for (Container container : runningContainers) {
            try {
                Statistics stats = api.getContainerStats(container.getId());
                if (stats != null) {
                    statsList.add(buildContainerStatsResponse(container, stats));
                }
            } catch (Exception e) {
                // Skip containers that fail to get stats
            }
        }

        return ResponseEntity.ok(statsList);
    }

    @GetMapping("/hosts/{hostId}/containers/{containerId}/stats")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "inspect",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    public ResponseEntity<ContainerStatsResponse> getContainerStats(
            @PathVariable String hostId, @PathVariable String containerId) {
        DockerAPI api = getDockerAPI(hostId);

        InspectContainerResponse inspection = api.inspectContainer(containerId);
        Statistics stats = api.getContainerStats(containerId);

        if (stats == null) {
            return ResponseEntity.noContent().build();
        }

        String containerName = inspection.getName();
        if (containerName != null && containerName.startsWith("/")) {
            containerName = containerName.substring(1);
        }

        ContainerStatsResponse response =
                ContainerStatsResponse.builder()
                        .containerId(containerId)
                        .containerName(containerName)
                        .cpuPercent(calculateCpuPercent(stats))
                        .cpuUsage(getCpuUsage(stats))
                        .systemCpuUsage(getSystemCpuUsage(stats))
                        .memoryUsage(getMemoryUsage(stats))
                        .memoryLimit(getMemoryLimit(stats))
                        .memoryPercent(calculateMemoryPercent(stats))
                        .networkRxBytes(getNetworkRxBytes(stats))
                        .networkTxBytes(getNetworkTxBytes(stats))
                        .blockReadBytes(getBlockReadBytes(stats))
                        .blockWriteBytes(getBlockWriteBytes(stats))
                        .timestamp(System.currentTimeMillis())
                        .pids(getPidCount(stats))
                        .build();

        return ResponseEntity.ok(response);
    }

    private ContainerStatsResponse buildContainerStatsResponse(
            Container container, Statistics stats) {
        String containerName =
                container.getNames() != null && container.getNames().length > 0
                        ? container.getNames()[0]
                        : container.getId().substring(0, 12);
        if (containerName.startsWith("/")) {
            containerName = containerName.substring(1);
        }

        return ContainerStatsResponse.builder()
                .containerId(container.getId())
                .containerName(containerName)
                .cpuPercent(calculateCpuPercent(stats))
                .cpuUsage(getCpuUsage(stats))
                .systemCpuUsage(getSystemCpuUsage(stats))
                .memoryUsage(getMemoryUsage(stats))
                .memoryLimit(getMemoryLimit(stats))
                .memoryPercent(calculateMemoryPercent(stats))
                .networkRxBytes(getNetworkRxBytes(stats))
                .networkTxBytes(getNetworkTxBytes(stats))
                .blockReadBytes(getBlockReadBytes(stats))
                .blockWriteBytes(getBlockWriteBytes(stats))
                .timestamp(System.currentTimeMillis())
                .pids(getPidCount(stats))
                .build();
    }

    private Double calculateCpuPercent(Statistics stats) {
        if (stats.getCpuStats() == null || stats.getPreCpuStats() == null) {
            return 0.0;
        }

        Long cpuDelta = getCpuUsage(stats) - getPreCpuUsage(stats);
        Long systemDelta = getSystemCpuUsage(stats) - getPreSystemCpuUsage(stats);

        if (systemDelta > 0 && cpuDelta > 0) {
            int cpuCount =
                    stats.getCpuStats().getOnlineCpus() != null
                            ? stats.getCpuStats().getOnlineCpus().intValue()
                            : 1;
            return (cpuDelta.doubleValue() / systemDelta.doubleValue()) * cpuCount * 100.0;
        }
        return 0.0;
    }

    private Long getCpuUsage(Statistics stats) {
        if (stats.getCpuStats() != null && stats.getCpuStats().getCpuUsage() != null) {
            return stats.getCpuStats().getCpuUsage().getTotalUsage();
        }
        return 0L;
    }

    private Long getPreCpuUsage(Statistics stats) {
        if (stats.getPreCpuStats() != null && stats.getPreCpuStats().getCpuUsage() != null) {
            return stats.getPreCpuStats().getCpuUsage().getTotalUsage();
        }
        return 0L;
    }

    private Long getSystemCpuUsage(Statistics stats) {
        if (stats.getCpuStats() != null) {
            return stats.getCpuStats().getSystemCpuUsage();
        }
        return 0L;
    }

    private Long getPreSystemCpuUsage(Statistics stats) {
        if (stats.getPreCpuStats() != null) {
            return stats.getPreCpuStats().getSystemCpuUsage();
        }
        return 0L;
    }

    private Long getMemoryUsage(Statistics stats) {
        if (stats.getMemoryStats() != null) {
            return stats.getMemoryStats().getUsage();
        }
        return 0L;
    }

    private Long getMemoryLimit(Statistics stats) {
        if (stats.getMemoryStats() != null) {
            return stats.getMemoryStats().getLimit();
        }
        return 0L;
    }

    private Double calculateMemoryPercent(Statistics stats) {
        Long usage = getMemoryUsage(stats);
        Long limit = getMemoryLimit(stats);
        if (limit != null && limit > 0) {
            return (usage.doubleValue() / limit.doubleValue()) * 100.0;
        }
        return 0.0;
    }

    private Long getNetworkRxBytes(Statistics stats) {
        if (stats.getNetworks() != null) {
            return stats.getNetworks().values().stream()
                    .mapToLong(n -> n.getRxBytes() != null ? n.getRxBytes() : 0L)
                    .sum();
        }
        return 0L;
    }

    private Long getNetworkTxBytes(Statistics stats) {
        if (stats.getNetworks() != null) {
            return stats.getNetworks().values().stream()
                    .mapToLong(n -> n.getTxBytes() != null ? n.getTxBytes() : 0L)
                    .sum();
        }
        return 0L;
    }

    private Long getBlockReadBytes(Statistics stats) {
        if (stats.getBlkioStats() != null
                && stats.getBlkioStats().getIoServiceBytesRecursive() != null) {
            return stats.getBlkioStats().getIoServiceBytesRecursive().stream()
                    .filter(entry -> "Read".equalsIgnoreCase(entry.getOp()))
                    .mapToLong(entry -> entry.getValue() != null ? entry.getValue() : 0L)
                    .sum();
        }
        return 0L;
    }

    private Long getBlockWriteBytes(Statistics stats) {
        if (stats.getBlkioStats() != null
                && stats.getBlkioStats().getIoServiceBytesRecursive() != null) {
            return stats.getBlkioStats().getIoServiceBytesRecursive().stream()
                    .filter(entry -> "Write".equalsIgnoreCase(entry.getOp()))
                    .mapToLong(entry -> entry.getValue() != null ? entry.getValue() : 0L)
                    .sum();
        }
        return 0L;
    }

    private Integer getPidCount(Statistics stats) {
        if (stats.getPidsStats() != null && stats.getPidsStats().getCurrent() != null) {
            return stats.getPidsStats().getCurrent().intValue();
        }
        return 0;
    }

    // ==================== Drift Detection ====================

    @GetMapping("/hosts/{hostId}/drift")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<ContainerDriftStatus>> getHostDrift(@PathVariable String hostId) {
        return ResponseEntity.ok(driftDetectionService.getContainerDriftForHost(hostId));
    }

    @GetMapping("/hosts/{hostId}/containers/{containerId}/drift")
    @RequirePermission(resource = Resource.CONTAINERS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<ContainerDriftStatus> getContainerDrift(
            @PathVariable String hostId, @PathVariable String containerId) {
        return driftDetectionService
                .getContainerDrift(hostId, containerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/hosts/{hostId}/drift/check")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<ContainerDriftStatus>> checkHostDrift(@PathVariable String hostId) {
        return ResponseEntity.ok(driftDetectionService.checkContainerDriftForHost(hostId));
    }

    @PostMapping("/hosts/{hostId}/containers/{containerId}/drift/check")
    @RequirePermission(resource = Resource.CONTAINERS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<ContainerDriftStatus> checkContainerDrift(
            @PathVariable String hostId, @PathVariable String containerId) {
        return ResponseEntity.ok(driftDetectionService.checkContainerDrift(hostId, containerId));
    }
}
