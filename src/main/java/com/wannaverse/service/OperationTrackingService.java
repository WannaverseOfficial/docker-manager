package com.wannaverse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.wannaverse.persistence.*;
import com.wannaverse.security.SecurityContext;
import com.wannaverse.security.SecurityContextHolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class OperationTrackingService {
    private static final Logger log = LoggerFactory.getLogger(OperationTrackingService.class);

    private final DockerOperationRepository operationRepository;
    private final StateSnapshotRepository snapshotRepository;
    private final ComposeDeploymentRepository composeRepository;
    private final DockerHostRepository hostRepository;
    private final DockerService dockerService;
    private final ObjectMapper objectMapper;

    public OperationTrackingService(
            DockerOperationRepository operationRepository,
            StateSnapshotRepository snapshotRepository,
            ComposeDeploymentRepository composeRepository,
            DockerHostRepository hostRepository,
            DockerService dockerService) {
        this.operationRepository = operationRepository;
        this.snapshotRepository = snapshotRepository;
        this.composeRepository = composeRepository;
        this.hostRepository = hostRepository;
        this.dockerService = dockerService;
        this.objectMapper = new ObjectMapper();
        // Configure ObjectMapper to handle Docker Java API objects which have empty beans
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    @Transactional
    public DockerOperation beginOperation(
            String hostId,
            DockerOperation.OperationType type,
            String resourceId,
            String resourceName) {

        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Docker host not found: " + hostId));

        DockerOperation operation = new DockerOperation();
        operation.setDockerHost(host);
        operation.setOperationType(type);
        operation.setResourceId(resourceId);
        operation.setResourceName(resourceName);
        operation.setStatus(DockerOperation.OperationStatus.RUNNING);

        // Set user info from security context
        SecurityContext securityContext = SecurityContextHolder.getContext();
        if (securityContext != null && securityContext.isAuthenticated()) {
            operation.setUsername(securityContext.getUsername());
            operation.setUserId(securityContext.getUserId());
        }

        operation = operationRepository.save(operation);
        log.info("Started tracking operation: {} {} on {}", type, resourceName, hostId);

        return operation;
    }

    @Transactional
    public void completeOperation(DockerOperation operation, boolean success, String errorMessage) {
        operation.setStatus(
                success
                        ? DockerOperation.OperationStatus.SUCCESS
                        : DockerOperation.OperationStatus.FAILED);
        operation.setCompletedAt(System.currentTimeMillis());

        if (errorMessage != null) {
            operation.setErrorMessage(errorMessage);
        }

        operationRepository.save(operation);
        log.info("Completed operation: {} status={}", operation.getId(), operation.getStatus());
    }

    /** Capture container state - delegates to the version with hostId parameter. */
    @Transactional
    public StateSnapshot captureContainerState(
            DockerOperation operation,
            String containerId,
            StateSnapshot.SnapshotType snapshotType) {
        return captureContainerState(operation, containerId, snapshotType, null);
    }

    /** Capture container state with explicit hostId to avoid lazy loading issues. */
    @Transactional
    public StateSnapshot captureContainerState(
            DockerOperation operation,
            String containerId,
            StateSnapshot.SnapshotType snapshotType,
            String hostId) {

        try {
            DockerHost host = null;

            // First try to use provided hostId
            if (hostId != null) {
                host = hostRepository.findById(hostId).orElse(null);
            }

            // If not provided, try to get from operation
            if (host == null) {
                try {
                    host = operation.getDockerHost();
                    if (host != null) {
                        // Try to access a property to force lazy loading
                        String url = host.getDockerHostUrl();
                        if (url == null) {
                            host = null;
                        }
                    }
                } catch (Exception lazyEx) {
                    log.debug(
                            "Lazy loading failed for operation {}: {}",
                            operation.getId(),
                            lazyEx.getMessage());
                    host = null;
                }
            }

            // Last resort: re-fetch the operation from the database
            if (host == null) {
                DockerOperation refreshedOp =
                        operationRepository.findById(operation.getId()).orElse(null);
                if (refreshedOp != null) {
                    host = refreshedOp.getDockerHost();
                    // Also update our reference to avoid detached entity issues
                    operation = refreshedOp;
                }
            }

            if (host == null || host.getDockerHostUrl() == null) {
                log.error(
                        "Cannot capture container state: Docker host not available for operation"
                                + " {}",
                        operation.getId());
                return null;
            }

            log.debug(
                    "Capturing {} snapshot for container {} on host {}",
                    snapshotType,
                    containerId,
                    host.getId());

            DockerAPI api =
                    dockerService.dockerAPI(
                            dockerService.createClientCached(host.getDockerHostUrl()));

            InspectContainerResponse inspect = api.inspectContainer(containerId);

            StateSnapshot snapshot = new StateSnapshot();
            snapshot.setOperation(operation);
            snapshot.setSnapshotType(snapshotType);
            snapshot.setResourceId(containerId);
            snapshot.setResourceName(inspect.getName());

            // Compress the full inspect data
            String inspectJson = objectMapper.writeValueAsString(inspect);
            snapshot.setInspectDataCompressed(compressJson(inspectJson));

            // Extract key fields for quick access
            if (inspect.getConfig() != null) {
                snapshot.setImageName(inspect.getConfig().getImage());
                snapshot.setImageId(inspect.getImageId());

                if (inspect.getConfig().getEnv() != null) {
                    snapshot.setEnvironmentVars(
                            objectMapper.writeValueAsString(inspect.getConfig().getEnv()));
                }
            }

            if (inspect.getHostConfig() != null) {
                if (inspect.getHostConfig().getBinds() != null) {
                    snapshot.setVolumeBindings(
                            objectMapper.writeValueAsString(inspect.getHostConfig().getBinds()));
                }
                if (inspect.getHostConfig().getPortBindings() != null) {
                    snapshot.setPortBindings(
                            objectMapper.writeValueAsString(
                                    inspect.getHostConfig().getPortBindings()));
                }
            }

            if (inspect.getNetworkSettings() != null) {
                snapshot.setNetworkSettings(
                        objectMapper.writeValueAsString(inspect.getNetworkSettings()));
            }

            snapshot = snapshotRepository.save(snapshot);
            log.info(
                    "Captured {} snapshot for container {} (operation {})",
                    snapshotType,
                    containerId,
                    operation.getId());

            return snapshot;

        } catch (Exception e) {
            log.error(
                    "Failed to capture container state for {}: {}", containerId, e.getMessage(), e);
            return null;
        }
    }

    @Transactional
    public StateSnapshot captureComposeState(
            DockerOperation operation,
            String projectName,
            String composeContent,
            StateSnapshot.SnapshotType snapshotType) {

        StateSnapshot snapshot = new StateSnapshot();
        snapshot.setOperation(operation);
        snapshot.setSnapshotType(snapshotType);
        snapshot.setResourceId(projectName);
        snapshot.setResourceName(projectName);
        snapshot.setComposeContent(composeContent);

        snapshot = snapshotRepository.save(snapshot);
        log.debug("Captured {} snapshot for compose project {}", snapshotType, projectName);

        return snapshot;
    }

    @Transactional
    public ComposeDeployment createComposeDeployment(
            String hostId,
            String projectName,
            String composeContent,
            String envFileContent,
            String commitSha,
            GitRepository gitRepo) {

        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Docker host not found: " + hostId));

        // Get next version number
        Integer latestVersion = composeRepository.findLatestVersion(hostId, projectName);
        int newVersion = (latestVersion != null) ? latestVersion + 1 : 1;

        // Find previous active deployment
        ComposeDeployment previous =
                composeRepository.findActiveDeployment(hostId, projectName).orElse(null);

        ComposeDeployment deployment = new ComposeDeployment();
        deployment.setDockerHost(host);
        deployment.setProjectName(projectName);
        deployment.setComposeContent(composeContent);
        deployment.setEnvFileContent(envFileContent);
        deployment.setStatus(ComposeDeployment.DeploymentStatus.DEPLOYING);
        deployment.setVersion(newVersion);
        deployment.setPreviousDeployment(previous);
        deployment.setCommitSha(commitSha);
        deployment.setGitRepository(gitRepo);

        // Set user info from security context
        SecurityContext securityContext = SecurityContextHolder.getContext();
        if (securityContext != null && securityContext.isAuthenticated()) {
            deployment.setUsername(securityContext.getUsername());
            deployment.setUserId(securityContext.getUserId());
        }

        // Mark previous as stopped if exists
        if (previous != null) {
            previous.setStatus(ComposeDeployment.DeploymentStatus.STOPPED);
            composeRepository.save(previous);
        }

        deployment = composeRepository.save(deployment);
        log.info(
                "Created compose deployment v{} for project {} on host {}",
                newVersion,
                projectName,
                hostId);

        return deployment;
    }

    @Transactional
    public void completeComposeDeployment(
            ComposeDeployment deployment, boolean success, String errorMessage) {
        deployment.setStatus(
                success
                        ? ComposeDeployment.DeploymentStatus.ACTIVE
                        : ComposeDeployment.DeploymentStatus.FAILED);
        deployment.setCompletedAt(System.currentTimeMillis());

        if (errorMessage != null) {
            deployment.appendLog("ERROR: " + errorMessage);
        }

        composeRepository.save(deployment);
        log.info(
                "Completed compose deployment: {} status={}",
                deployment.getId(),
                deployment.getStatus());
    }

    @Transactional
    public void linkOperationToDeploymentJob(DockerOperation operation, DeploymentJob job) {
        operation.setDeploymentJob(job);
        operation.setCommitSha(job.getCommitSha());
        operationRepository.save(operation);
    }

    public byte[] compressJson(String json) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(json.getBytes());
            gzos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to compress JSON", e);
            return json.getBytes();
        }
    }

    public String decompressJson(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return null;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                GZIPInputStream gzis = new GZIPInputStream(bais)) {
            return new String(gzis.readAllBytes());
        } catch (IOException e) {
            log.error("Failed to decompress JSON", e);
            return new String(compressed);
        }
    }

    public List<StateSnapshot> getOperationSnapshots(String operationId) {
        return snapshotRepository.findByOperationIdOrderByCreatedAtAsc(operationId);
    }

    public String getDecompressedInspectData(StateSnapshot snapshot) {
        if (snapshot.getInspectDataCompressed() == null) {
            return null;
        }
        return decompressJson(snapshot.getInspectDataCompressed());
    }
}
