package com.wannaverse.controllers;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Info;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.CleanupService;
import com.wannaverse.service.DockerAPI;
import com.wannaverse.service.DockerService;
import com.wannaverse.service.PermissionService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** The dashboard summary for a host, and cleanup of unused Docker objects. */
@RestController
@RequestMapping("/api/docker/hosts/{hostId}")
public class HostOverviewController {
    private static final Set<String> BUILTIN_NETWORKS = Set.of("bridge", "host", "none");

    private final DockerHostRepository hostRepository;
    private final DockerService dockerService;
    private final CleanupService cleanupService;
    private final PermissionService permissionService;

    public HostOverviewController(
            DockerHostRepository hostRepository,
            DockerService dockerService,
            CleanupService cleanupService,
            PermissionService permissionService) {
        this.hostRepository = hostRepository;
        this.dockerService = dockerService;
        this.cleanupService = cleanupService;
        this.permissionService = permissionService;
    }

    public record ContainerCounts(
            int total, int running, int paused, int stopped, int restarting, int unhealthy) {}

    public record HostSummary(
            String name,
            String dockerVersion,
            String operatingSystem,
            String kernelVersion,
            String architecture,
            int cpus,
            long memoryBytes,
            String storageDriver,
            String dockerRootDir) {}

    public record Overview(
            HostSummary host,
            ContainerCounts containers,
            int images,
            long imagesBytes,
            int danglingImages,
            int volumes,
            int unusedVolumes,
            int networks,
            int stacks,
            List<Map<String, Object>> recentContainers) {}

    /**
     * Counts are computed from the same lists the other pages show, and only include what the user
     * may see. Previously the dashboard counted intermediate image layers as images and read the
     * volume count from an object that has no length, so it showed wrong numbers.
     */
    @GetMapping("/overview")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<Overview> overview(@PathVariable String hostId) {
        DockerAPI api = api(hostId);
        Info info = api.getHostInfo();

        List<Container> containers =
                visibleContainers(hostId, DockerController.withoutHelpers(api.listAllContainers()));
        int running = 0, paused = 0, stopped = 0, restarting = 0, unhealthy = 0;
        Set<String> stacks = new HashSet<>();
        for (Container c : containers) {
            String state = c.getState() == null ? "" : c.getState();
            switch (state) {
                case "running" -> running++;
                case "paused" -> paused++;
                case "restarting" -> restarting++;
                default -> stopped++;
            }
            if (c.getStatus() != null && c.getStatus().contains("(unhealthy)")) unhealthy++;
            if (c.getLabels() != null && c.getLabels().get("com.docker.compose.project") != null) {
                stacks.add(c.getLabels().get("com.docker.compose.project"));
            }
        }

        List<Image> images = api.listImages();
        long imageBytes =
                images.stream().mapToLong(i -> i.getSize() == null ? 0 : i.getSize()).sum();
        int dangling =
                (int)
                        images.stream()
                                .filter(
                                        i ->
                                                i.getRepoTags() == null
                                                        || i.getRepoTags().length == 0
                                                        || Arrays.asList(i.getRepoTags())
                                                                .contains("<none>:<none>"))
                                .count();

        var volumes = api.listVolumes().getVolumes();
        int volumeCount = volumes == null ? 0 : volumes.size();
        int unusedVolumes = api.listUnusedVolumes().size();
        int networks =
                (int)
                        api.listNetworks().stream()
                                .filter(n -> !BUILTIN_NETWORKS.contains(n.getName()))
                                .count();

        List<Map<String, Object>> recent =
                containers.stream()
                        .sorted(
                                Comparator.comparing(
                                                (Container c) ->
                                                        c.getCreated() == null
                                                                ? 0L
                                                                : c.getCreated())
                                        .reversed())
                        .limit(6)
                        .map(
                                c -> {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("id", c.getId());
                                    m.put(
                                            "name",
                                            c.getNames() != null && c.getNames().length > 0
                                                    ? c.getNames()[0].replaceFirst("^/", "")
                                                    : c.getId().substring(0, 12));
                                    m.put("image", c.getImage());
                                    m.put("state", c.getState());
                                    m.put("status", c.getStatus());
                                    m.put("created", c.getCreated());
                                    return m;
                                })
                        .toList();

        HostSummary host =
                new HostSummary(
                        info.getName(),
                        info.getServerVersion(),
                        info.getOperatingSystem(),
                        info.getKernelVersion(),
                        info.getArchitecture(),
                        info.getNCPU() == null ? 0 : info.getNCPU(),
                        info.getMemTotal() == null ? 0 : info.getMemTotal(),
                        info.getDriver(),
                        info.getDockerRootDir());

        return ResponseEntity.ok(
                new Overview(
                        host,
                        new ContainerCounts(
                                containers.size(), running, paused, stopped, restarting, unhealthy),
                        images.size(),
                        imageBytes,
                        dangling,
                        volumeCount,
                        unusedVolumes,
                        networks,
                        stacks.size(),
                        recent));
    }

    // Cleanup removes other people's containers, images and volumes, so it is for admins.
    @com.wannaverse.security.AdminOnly
    @PostMapping("/cleanup/preview")
    @RequirePermission(
            resource = Resource.DOCKER_OPERATIONS,
            action = "prune",
            hostIdParam = "hostId")
    public ResponseEntity<CleanupService.Preview> previewCleanup(
            @PathVariable String hostId, @RequestBody CleanupService.Options options) {
        return ResponseEntity.ok(cleanupService.preview(hostId, api(hostId), options));
    }

    @com.wannaverse.security.AdminOnly
    @PostMapping("/cleanup")
    @RequirePermission(
            resource = Resource.DOCKER_OPERATIONS,
            action = "prune",
            hostIdParam = "hostId")
    @Auditable(resource = Resource.DOCKER_OPERATIONS, action = "prune", captureRequestBody = true)
    public ResponseEntity<CleanupService.Result> cleanup(
            @PathVariable String hostId, @RequestBody CleanupService.Options options) {
        return ResponseEntity.ok(cleanupService.run(hostId, api(hostId), options));
    }

    private List<Container> visibleContainers(String hostId, List<Container> containers) {
        String userId = SecurityContextHolder.getCurrentUserId();
        var ctx = SecurityContextHolder.getContext();
        if (userId == null || (ctx != null && ctx.isAdmin())) return containers;
        Set<String> allowed =
                permissionService.getAllowedResourceIds(
                        userId, Resource.CONTAINERS, "list", hostId);
        if (allowed == null) return containers;
        return containers.stream()
                .filter(
                        c ->
                                allowed.contains(c.getId())
                                        || allowed.contains(c.getId().substring(0, 12))
                                        || (c.getNames() != null
                                                && Arrays.stream(c.getNames())
                                                        .anyMatch(
                                                                n ->
                                                                        allowed.contains(
                                                                                n.replaceFirst(
                                                                                        "^/",
                                                                                        "")))))
                .toList();
    }

    private DockerAPI api(String hostId) {
        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Docker host not found"));
        return dockerService.dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()));
    }
}
