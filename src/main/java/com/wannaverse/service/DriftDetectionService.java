package com.wannaverse.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.wannaverse.persistence.*;
import com.wannaverse.persistence.GitRepository.DriftStatus;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class DriftDetectionService {
    private static final Logger log = LoggerFactory.getLogger(DriftDetectionService.class);

    private final GitRepositoryRepository gitRepositoryRepository;
    private final GitService gitService;
    private final ContainerDriftStatusRepository containerDriftStatusRepository;
    private final StateSnapshotRepository stateSnapshotRepository;
    private final DockerHostRepository dockerHostRepository;
    private final DockerService dockerService;
    private final ObjectMapper objectMapper;

    public DriftDetectionService(
            GitRepositoryRepository gitRepositoryRepository,
            GitService gitService,
            ContainerDriftStatusRepository containerDriftStatusRepository,
            StateSnapshotRepository stateSnapshotRepository,
            DockerHostRepository dockerHostRepository,
            DockerService dockerService,
            ObjectMapper objectMapper) {
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.gitService = gitService;
        this.containerDriftStatusRepository = containerDriftStatusRepository;
        this.stateSnapshotRepository = stateSnapshotRepository;
        this.dockerHostRepository = dockerHostRepository;
        this.dockerService = dockerService;
        this.objectMapper = objectMapper;
    }

    private DockerAPI getDockerAPI(DockerHost host) {
        return dockerService.dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()));
    }

    @Scheduled(fixedDelayString = "${app.drift.git-check-interval-ms:300000}")
    @Transactional
    public void checkAllGitDrift() {
        log.debug("Running scheduled git drift check");
        List<GitRepository> repos = gitRepositoryRepository.findAll();
        for (GitRepository repo : repos) {
            try {
                checkGitDrift(repo);
            } catch (Exception e) {
                log.warn("Failed to check drift for repo {}: {}", repo.getName(), e.getMessage());
            }
        }
    }

    @Transactional
    public GitRepository checkGitDrift(GitRepository repo) {
        try {
            String latestSha = gitService.getLatestCommitSha(repo);
            repo.setLatestRemoteCommitSha(latestSha);
            repo.setLastDriftCheckAt(System.currentTimeMillis());

            if (latestSha == null) {
                repo.setDriftStatus(DriftStatus.ERROR);
            } else if (repo.getLastCommitSha() == null) {
                repo.setDriftStatus(DriftStatus.BEHIND);
                if (repo.getDriftDetectedAt() == null) {
                    repo.setDriftDetectedAt(System.currentTimeMillis());
                }
            } else if (latestSha.equals(repo.getLastCommitSha())) {
                repo.setDriftStatus(DriftStatus.SYNCED);
                repo.setDriftDetectedAt(null);
            } else {
                repo.setDriftStatus(DriftStatus.BEHIND);
                if (repo.getDriftDetectedAt() == null) {
                    repo.setDriftDetectedAt(System.currentTimeMillis());
                }
            }

            return gitRepositoryRepository.save(repo);
        } catch (GitAPIException e) {
            log.error("Git API error checking drift for {}: {}", repo.getName(), e.getMessage());
            repo.setDriftStatus(DriftStatus.ERROR);
            repo.setLastDriftCheckAt(System.currentTimeMillis());
            return gitRepositoryRepository.save(repo);
        }
    }

    @Transactional
    public GitRepository checkGitDriftById(String repoId) {
        return gitRepositoryRepository
                .findById(repoId)
                .map(this::checkGitDrift)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repoId));
    }

    public List<GitDriftInfo> getAllGitDriftStatus() {
        return gitRepositoryRepository.findAll().stream()
                .map(
                        repo ->
                                new GitDriftInfo(
                                        repo.getId(),
                                        repo.getName(),
                                        repo.getDriftStatus(),
                                        repo.getLastCommitSha(),
                                        repo.getLatestRemoteCommitSha(),
                                        repo.getDriftDetectedAt(),
                                        repo.getLastDriftCheckAt()))
                .toList();
    }

    @Scheduled(fixedDelayString = "${app.drift.container-check-interval-ms:600000}")
    @Transactional
    public void checkAllContainerDrift() {
        log.debug("Running scheduled container drift check");
        List<DockerHost> hosts = dockerHostRepository.findAll();
        for (DockerHost host : hosts) {
            try {
                checkContainerDriftForHost(host.getId());
            } catch (Exception e) {
                log.warn(
                        "Failed to check container drift for host {}: {}",
                        host.getId(),
                        e.getMessage());
            }
        }
    }

    @Transactional
    public List<ContainerDriftStatus> checkContainerDriftForHost(String hostId) {
        DockerHost host =
                dockerHostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Host not found: " + hostId));

        List<ContainerDriftStatus> results = new ArrayList<>();

        try {
            DockerAPI api = getDockerAPI(host);
            var containers = api.listAllContainers();
            for (var container : containers) {
                try {
                    ContainerDriftStatus status =
                            checkSingleContainerDrift(host, container.getId(), container);
                    if (status != null) {
                        results.add(status);
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed to check drift for container {}: {}",
                            container.getId(),
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to list containers for host {}: {}", host.getId(), e.getMessage());
        }

        cleanupStaleContainerDrift(hostId, results);

        return results;
    }

    @Transactional
    public ContainerDriftStatus checkContainerDrift(String hostId, String containerId) {
        DockerHost host =
                dockerHostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Host not found: " + hostId));

        try {
            DockerAPI api = getDockerAPI(host);
            var inspect = api.inspectContainer(containerId);
            return checkSingleContainerDrift(host, containerId, inspect);
        } catch (Exception e) {
            log.error("Failed to check drift for container {}: {}", containerId, e.getMessage());
            throw new RuntimeException("Failed to check container drift: " + e.getMessage(), e);
        }
    }

    private ContainerDriftStatus checkSingleContainerDrift(
            DockerHost host, String containerId, Object containerInfo) {
        ContainerDriftStatus status =
                containerDriftStatusRepository
                        .findByDockerHostIdAndContainerId(host.getId(), containerId)
                        .orElse(new ContainerDriftStatus());

        status.setDockerHostId(host.getId());
        status.setContainerId(containerId);
        status.setLastCheckedAt(System.currentTimeMillis());

        // Get current container state
        InspectContainerResponse inspect;
        try {
            DockerAPI api = getDockerAPI(host);
            inspect = api.inspectContainer(containerId);
        } catch (Exception e) {
            log.warn("Could not inspect container {}: {}", containerId, e.getMessage());
            return null;
        }

        String containerName =
                inspect.getName() != null ? inspect.getName().replaceFirst("^/", "") : containerId;
        status.setContainerName(containerName);

        String imageName = inspect.getConfig() != null ? inspect.getConfig().getImage() : null;
        status.setImageName(imageName);

        String imageId = inspect.getImageId();
        status.setRunningImageDigest(imageId);

        List<StateSnapshot> snapshots =
                stateSnapshotRepository.findByHostAndResourceAndType(
                        host.getId(), containerId, StateSnapshot.SnapshotType.AFTER);

        if (snapshots.isEmpty()) {
            // No baseline snapshot, cannot detect drift
            status.setConfigDriftStatus(DriftStatus.UNKNOWN);
            status.setDriftDetails(null);
        } else {
            StateSnapshot baseline = snapshots.get(0);
            List<DriftDetail> drifts = compareContainerState(baseline, inspect);

            if (drifts.isEmpty()) {
                status.setConfigDriftStatus(DriftStatus.SYNCED);
                status.setDriftDetails(null);
            } else {
                status.setConfigDriftStatus(DriftStatus.BEHIND);
                try {
                    status.setDriftDetails(objectMapper.writeValueAsString(drifts));
                } catch (Exception e) {
                    status.setDriftDetails("Error serializing drift details");
                }
            }
        }

        return containerDriftStatusRepository.save(status);
    }

    private List<DriftDetail> compareContainerState(
            StateSnapshot baseline, InspectContainerResponse current) {
        List<DriftDetail> drifts = new ArrayList<>();

        Set<String> baselineEnv = parseEnvSet(baseline.getEnvironmentVars());
        Set<String> currentEnv = new HashSet<>();
        if (current.getConfig() != null && current.getConfig().getEnv() != null) {
            currentEnv.addAll(Arrays.asList(current.getConfig().getEnv()));
        }

        if (!baselineEnv.equals(currentEnv)) {
            Set<String> added = new HashSet<>(currentEnv);
            added.removeAll(baselineEnv);
            Set<String> removed = new HashSet<>(baselineEnv);
            removed.removeAll(currentEnv);

            if (!added.isEmpty() || !removed.isEmpty()) {
                drifts.add(
                        new DriftDetail(
                                "environment",
                                "Environment variables changed",
                                "Added: " + added.size() + ", Removed: " + removed.size()));
            }
        }

        if (baseline.getImageId() != null && current.getImageId() != null) {
            if (!baseline.getImageId().equals(current.getImageId())) {
                drifts.add(
                        new DriftDetail(
                                "image",
                                "Image changed",
                                "From "
                                        + truncate(baseline.getImageId())
                                        + " to "
                                        + truncate(current.getImageId())));
            }
        }

        Set<String> baselineVolumes = parseVolumeSet(baseline.getVolumeBindings());
        Set<String> currentVolumes = new HashSet<>();
        if (current.getHostConfig() != null && current.getHostConfig().getBinds() != null) {
            for (var bind : current.getHostConfig().getBinds()) {
                currentVolumes.add(bind.toString());
            }
        }
        if (!baselineVolumes.equals(currentVolumes)) {
            drifts.add(new DriftDetail("volumes", "Volume bindings changed", null));
        }

        String baselinePorts = baseline.getPortBindings();
        String currentPorts = extractPortBindings(current);
        if (baselinePorts != null && currentPorts != null && !baselinePorts.equals(currentPorts)) {
            drifts.add(new DriftDetail("ports", "Port bindings changed", null));
        }

        return drifts;
    }

    private Set<String> parseEnvSet(String envJson) {
        if (envJson == null || envJson.isEmpty()) {
            return new HashSet<>();
        }
        try {
            List<String> envList =
                    objectMapper.readValue(envJson, new TypeReference<List<String>>() {});
            return new HashSet<>(envList);
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private Set<String> parseVolumeSet(String volumeJson) {
        if (volumeJson == null || volumeJson.isEmpty()) {
            return new HashSet<>();
        }
        try {
            List<String> volumeList =
                    objectMapper.readValue(volumeJson, new TypeReference<List<String>>() {});
            return new HashSet<>(volumeList);
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private String extractPortBindings(InspectContainerResponse inspect) {
        if (inspect.getHostConfig() == null || inspect.getHostConfig().getPortBindings() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(inspect.getHostConfig().getPortBindings());
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 16 ? s.substring(0, 16) + "..." : s;
    }

    public List<ContainerDriftStatus> getContainerDriftForHost(String hostId) {
        return containerDriftStatusRepository.findByDockerHostId(hostId);
    }

    public Optional<ContainerDriftStatus> getContainerDrift(String hostId, String containerId) {
        return containerDriftStatusRepository.findByDockerHostIdAndContainerId(hostId, containerId);
    }

    private void cleanupStaleContainerDrift(
            String hostId, List<ContainerDriftStatus> currentStatuses) {
        Set<String> currentContainerIds =
                currentStatuses.stream()
                        .map(ContainerDriftStatus::getContainerId)
                        .collect(java.util.stream.Collectors.toSet());

        List<ContainerDriftStatus> allForHost =
                containerDriftStatusRepository.findByDockerHostId(hostId);
        for (ContainerDriftStatus status : allForHost) {
            if (!currentContainerIds.contains(status.getContainerId())) {
                containerDriftStatusRepository.delete(status);
            }
        }
    }

    public record GitDriftInfo(
            String id,
            String name,
            DriftStatus driftStatus,
            String deployedCommitSha,
            String latestCommitSha,
            Long driftDetectedAt,
            Long lastCheckedAt) {}

    public record DriftDetail(String type, String description, String details) {}
}
