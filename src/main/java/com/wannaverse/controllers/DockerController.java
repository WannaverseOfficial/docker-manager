package com.wannaverse.controllers;

import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.model.Statistics;
import com.wannaverse.dto.ContainerSpec;
import com.wannaverse.dto.ContainerStatsResponse;
import com.wannaverse.dto.ExecCommandRequest;
import com.wannaverse.dto.HostInfoResponse;
import com.wannaverse.iac.ConfigLocked;
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
import com.wannaverse.service.ContainerSpecService;
import com.wannaverse.service.DockerAPI;
import com.wannaverse.service.DockerRegistryService;
import com.wannaverse.service.DockerService;
import com.wannaverse.service.DriftDetectionService;
import com.wannaverse.service.EncryptionService;
import com.wannaverse.service.HostConnections;
import com.wannaverse.service.ImagePolicyService;
import com.wannaverse.service.OperationTrackingService;
import com.wannaverse.service.PermissionService;
import com.wannaverse.service.SelfContainer;
import com.wannaverse.service.StatsCalculator;
import com.wannaverse.service.StatsService;
import com.wannaverse.service.VolumeBrowserService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

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
    private final StatsService statsService;
    private final ContainerSpecService specService;
    private final SelfContainer self;
    private final HostConnections hostConnections;
    private final EncryptionService encryptionService;

    public DockerController(
            DockerService dockerService,
            DockerHostRepository hostRepository,
            PermissionService permissionService,
            OperationTrackingService trackingService,
            ImagePolicyService imagePolicyService,
            DockerRegistryService registryService,
            DriftDetectionService driftDetectionService,
            StatsService statsService,
            ContainerSpecService specService,
            SelfContainer self,
            HostConnections hostConnections,
            EncryptionService encryptionService) {
        this.self = self;
        this.hostConnections = hostConnections;
        this.encryptionService = encryptionService;
        this.statsService = statsService;
        this.specService = specService;
        this.dockerService = dockerService;
        this.hostRepository = hostRepository;
        this.permissionService = permissionService;
        this.trackingService = trackingService;
        this.imagePolicyService = imagePolicyService;
        this.registryService = registryService;
        this.driftDetectionService = driftDetectionService;
    }

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

    /**
     * The hosts the caller can use: all of them for admins, otherwise those they have been given
     * any permission on (so a user allowed to manage stacks on one host can pick that host).
     */
    @GetMapping("/hosts")
    public ResponseEntity<List<DockerHost>> getAllHosts() {
        var ctx = SecurityContextHolder.getContext();
        Set<String> allowed = permissionService.accessibleHostIds(ctx.getUserId());
        List<DockerHost> hosts = hostRepository.findAll();
        if (allowed != null) {
            hosts = hosts.stream().filter(h -> allowed.contains(h.getId())).toList();
        }
        return ResponseEntity.ok(hosts);
    }

    @GetMapping("/hosts/{hostId}")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<DockerHost> getHost(@PathVariable String hostId) {
        return hostRepository
                .findById(hostId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * A Docker host. TLS fields (PEM text) are for tcp:// hosts with --tlsverify; the private key
     * for ssh://user@server hosts. Leaving them out on an update keeps the saved ones.
     */
    public record HostRequest(
            String name,
            String dockerHostUrl,
            String tlsCaPem,
            String tlsCertPem,
            String tlsKeyPem,
            String sshPrivateKey) {}

    /**
     * Applies the connection details of a request to a host, encrypted. Also used by the
     * configuration file.
     */
    public void applyCredentials(DockerHost host, HostRequest request) {
        String url = host.getDockerHostUrl();
        if (HostConnections.isSsh(url)) {
            if (notBlank(request.sshPrivateKey())) {
                String key = request.sshPrivateKey().strip();
                if (!key.contains("PRIVATE KEY")) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Paste the private key, starting with -----BEGIN ... PRIVATE KEY-----");
                }
                if (key.contains("ENCRYPTED")) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Use a key without a passphrase: Docker Manager connects unattended");
                }
                host.setSshPrivateKey(encryptionService.encrypt(key));
            }
            if (host.getSshPrivateKey() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "An SSH host needs a private key");
            }
            host.setTlsCaPem(null);
            host.setTlsCertPem(null);
            host.setTlsKeyPem(null);
            return;
        }
        host.setSshPrivateKey(null);
        boolean anyTls =
                notBlank(request.tlsCaPem())
                        || notBlank(request.tlsCertPem())
                        || notBlank(request.tlsKeyPem());
        if (!anyTls) return;
        if (!url.startsWith("tcp://")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "TLS certificates are only used with tcp:// hosts");
        }
        if (!(notBlank(request.tlsCaPem())
                && notBlank(request.tlsCertPem())
                && notBlank(request.tlsKeyPem()))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "TLS needs all three: the CA certificate, the client certificate and its key"
                            + " (ca.pem, cert.pem, key.pem)");
        }
        host.setTlsCaPem(encryptionService.encrypt(pem(request.tlsCaPem())));
        host.setTlsCertPem(encryptionService.encrypt(pem(request.tlsCertPem())));
        host.setTlsKeyPem(encryptionService.encrypt(pem(request.tlsKeyPem())));
    }

    private static String pem(String text) {
        return text.replace("\r\n", "\n").strip() + "\n";
    }

    /** Pings the host with these details before they are saved. */
    public void testConnection(DockerHost host) {
        com.github.dockerjava.api.DockerClient client;
        try {
            client = dockerService.createClient(host);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        try {
            if (!dockerService.dockerAPI(client).ping()) {
                String hint =
                        HostConnections.isSsh(host.getDockerHostUrl())
                                ? " Signing in over SSH worked, but Docker didn't answer through"
                                      + " it: the SSH server must allow forwarding"
                                      + " (AllowTcpForwarding yes in sshd_config) and the user must"
                                      + " be able to use /var/run/docker.sock (root, or in the"
                                      + " docker group)."
                                : host.hasTls()
                                        ? " Check that the server name matches its certificate and"
                                                + " that the certificates belong together."
                                        : " Check the address and that the port is reachable.";
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Could not reach Docker at " + host.getDockerHostUrl() + "." + hint);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Could not reach Docker at " + host.getDockerHostUrl() + ": " + e.getMessage());
        } finally {
            dockerService.close(client);
            if (host.getId() == null) hostConnections.forget(host);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @PostMapping("/hosts")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "create")
    @Auditable(resource = Resource.DOCKER_HOSTS, action = "create", captureRequestBody = true)
    public ResponseEntity<DockerHost> addHost(@RequestBody HostRequest request) {
        String url = request.dockerHostUrl() == null ? "" : request.dockerHostUrl().trim();
        if (!InputValidator.isValidDockerHostUrl(url)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid Docker host address. Use unix:///var/run/docker.sock,"
                            + " tcp://server:2375 (or :2376 with TLS) or ssh://user@server");
        }
        if (hostRepository.findByDockerHostUrl(url).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This host is already added");
        }
        DockerHost host = new DockerHost();
        host.setDockerHostUrl(url);
        host.setName(
                request.name() == null || request.name().isBlank() ? null : request.name().trim());
        applyCredentials(host, request);
        testConnection(host);
        return ResponseEntity.status(HttpStatus.CREATED).body(hostRepository.save(host));
    }

    @PutMapping("/hosts/{hostId}")
    @ConfigLocked(section = "hosts", id = "hostId")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "update", hostIdParam = "hostId")
    @Auditable(resource = Resource.DOCKER_HOSTS, action = "update", resourceIdParam = "hostId")
    public ResponseEntity<DockerHost> updateHost(
            @PathVariable String hostId, @RequestBody HostRequest request) {
        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Docker host not found"));
        if (request.name() != null) {
            host.setName(request.name().isBlank() ? null : request.name().trim());
        }
        boolean newCredentials =
                notBlank(request.sshPrivateKey())
                        || notBlank(request.tlsCaPem())
                        || notBlank(request.tlsCertPem())
                        || notBlank(request.tlsKeyPem());
        if (newCredentials) {
            applyCredentials(host, request);
            // Reconnect with the new details.
            hostConnections.forget(host);
            dockerService.close(host.getDockerHostUrl());
            testConnection(host);
        }
        return ResponseEntity.ok(hostRepository.save(host));
    }

    @DeleteMapping("/hosts/{hostId}")
    @ConfigLocked(section = "hosts", id = "hostId")
    @RequirePermission(resource = Resource.DOCKER_HOSTS, action = "delete", hostIdParam = "hostId")
    @Auditable(resource = Resource.DOCKER_HOSTS, action = "delete", resourceIdParam = "hostId")
    public ResponseEntity<Void> removeHost(@PathVariable String hostId) {
        hostRepository
                .findById(hostId)
                .ifPresent(
                        host -> {
                            dockerService.close(host.getDockerHostUrl());
                            hostConnections.forget(host);
                            hostRepository.deleteById(hostId);
                        });
        return ResponseEntity.noContent().build();
    }

    /** Whether the host is reachable; open to anyone who can use the host at all. */
    @GetMapping("/hosts/{hostId}/ping")
    public ResponseEntity<Map<String, Boolean>> ping(@PathVariable String hostId) {
        Set<String> allowed =
                permissionService.accessibleHostIds(SecurityContextHolder.getContext().getUserId());
        if (allowed != null && !allowed.contains(hostId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to this host");
        }
        boolean result = getDockerAPI(hostId).ping();
        return ResponseEntity.ok(Map.of("connected", result));
    }

    @GetMapping("/hosts/{hostId}/containers")
    @RequirePermission(resource = Resource.CONTAINERS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<Container>> listContainers(
            @PathVariable String hostId, @RequestParam(defaultValue = "false") boolean all) {
        DockerAPI api = getDockerAPI(hostId);
        List<Container> containers =
                withoutHelpers(all ? api.listAllContainers() : api.listRunningContainers());

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
            if (allowedIds.contains(c.getId()) || allowedIds.contains(c.getId().substring(0, 12))) {
                filtered.add(c);
                continue;
            }
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
        InspectContainerResponse inspect = getDockerAPI(hostId).inspectContainer(containerId);
        selfIsAdminOnly(inspect.getId(), "inspect");
        return ResponseEntity.ok(inspect);
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
        DockerAPI api = getDockerAPI(hostId);
        // Its log shows the first admin's temporary password, among other things.
        if (!isAdmin())
            selfIsAdminOnly(api.inspectContainer(containerId).getId(), "read the logs of");
        // A huge or negative ("all") tail would load the whole log into memory on every poll.
        int lines = tail <= 0 || tail > 20_000 ? 20_000 : tail;
        String logs = api.getContainerLogs(containerId, lines, timestamps);
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
    public ResponseEntity<Map<String, String>> createContainer(
            @PathVariable String hostId, @RequestBody ContainerSpec spec) {
        specService.validate(spec, isAdmin());
        DockerAPI dockerAPI = getDockerAPI(hostId);
        if (!isAdmin()) specService.checkVolumesForNonAdmin(dockerAPI, spec, self.id());

        DockerOperation operation =
                trackingService.beginOperation(
                        hostId,
                        DockerOperation.OperationType.CONTAINER_CREATE,
                        null,
                        spec.getName());
        try {
            String id = specService.create(dockerAPI, spec);
            operation.setResourceId(id);
            trackingService.captureContainerState(
                    operation, id, StateSnapshot.SnapshotType.AFTER, hostId);
            trackingService.completeOperation(operation, true, null);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (RuntimeException e) {
            trackingService.completeOperation(operation, false, e.getMessage());
            throw e;
        }
    }

    /** The container's current settings, as the edit form shows them. */
    @GetMapping("/hosts/{hostId}/containers/{containerId}/spec")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "inspect",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    public ResponseEntity<ContainerSpec> getContainerSpec(
            @PathVariable String hostId, @PathVariable String containerId) {
        DockerAPI api = getDockerAPI(hostId);
        InspectContainerResponse inspect = api.inspectContainer(containerId);
        selfIsAdminOnly(inspect.getId(), "read the settings of");
        return ResponseEntity.ok(specService.specFrom(api, inspect));
    }

    /**
     * Applies new settings by recreating the container. Volumes (and so the container's data) are
     * kept, and if anything fails the original container is restored.
     */
    @PutMapping("/hosts/{hostId}/containers/{containerId}")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "update",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @ConfigLocked(section = "containers", id = "containerId", host = "hostId")
    @Auditable(resource = Resource.CONTAINERS, action = "update", resourceIdParam = "containerId")
    public ResponseEntity<Map<String, String>> updateContainer(
            @PathVariable String hostId,
            @PathVariable String containerId,
            @RequestBody ContainerSpec spec,
            @RequestParam(defaultValue = "false") boolean pull) {
        protectSelf(hostId, containerId, "replace");
        specService.validate(spec, isAdmin());
        if (!isAdmin()) specService.checkVolumesForNonAdmin(getDockerAPI(hostId), spec, self.id());
        return ResponseEntity.ok(
                Map.of("id", recreateTracked(hostId, containerId, spec, pull, isAdmin())));
    }

    /** Pulls the newest version of the container's image and recreates it with it. */
    @PostMapping("/hosts/{hostId}/containers/{containerId}/update-image")
    @RequirePermission(
            resource = Resource.CONTAINERS,
            action = "update",
            hostIdParam = "hostId",
            resourceIdParam = "containerId")
    @Auditable(
            resource = Resource.CONTAINERS,
            action = "update_image",
            resourceIdParam = "containerId")
    public ResponseEntity<Map<String, String>> updateImage(
            @PathVariable String hostId, @PathVariable String containerId) {
        protectSelf(hostId, containerId, "replace");
        DockerAPI api = getDockerAPI(hostId);
        // Without the old image's defaults, so the new image's own values (versions, paths)
        // are used.
        ContainerSpec spec = specService.specFrom(api, api.inspectContainer(containerId));
        // Same image name and settings, only a newer pull: nothing here is chosen by the caller.
        return ResponseEntity.ok(
                Map.of("id", recreateTracked(hostId, containerId, spec, true, true)));
    }

    private String recreateTracked(
            String hostId, String containerId, ContainerSpec spec, boolean pull, boolean trusted) {
        DockerAPI api = getDockerAPI(hostId);
        DockerOperation operation =
                trackingService.beginOperation(
                        hostId,
                        DockerOperation.OperationType.CONTAINER_UPDATE,
                        containerId,
                        spec.getName());
        trackingService.captureContainerState(
                operation, containerId, StateSnapshot.SnapshotType.BEFORE, hostId);
        try {
            String newId = specService.recreate(api, containerId, spec, pull, trusted);
            operation.setResourceId(newId);
            trackingService.captureContainerState(
                    operation, newId, StateSnapshot.SnapshotType.AFTER, hostId);
            trackingService.completeOperation(operation, true, null);
            return newId;
        } catch (RuntimeException e) {
            trackingService.completeOperation(operation, false, e.getMessage());
            throw e;
        }
    }

    /** Only admins may look inside Docker Manager's own container: it holds its secrets. */
    private void selfIsAdminOnly(String containerId, String action) {
        if (!isAdmin() && self.is(containerId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only administrators can " + action + " Docker Manager's own container.");
        }
    }

    /** Docker Manager must not stop, remove or replace its own container. */
    private void protectSelf(String hostId, String containerRef, String action) {
        if (self.shortId() == null) return;
        String id;
        try {
            id = getDockerAPI(hostId).inspectContainer(containerRef).getId();
        } catch (RuntimeException e) {
            return; // the action itself reports a missing container
        }
        self.protect(id, action);
    }

    /** Short-lived containers Docker Manager runs for itself (browsing a volume, reading files). */
    static List<Container> withoutHelpers(List<Container> containers) {
        return containers.stream()
                .filter(
                        c ->
                                c.getLabels() == null
                                        || !c.getLabels()
                                                .containsKey(VolumeBrowserService.HELPER_LABEL))
                .toList();
    }

    private static boolean isAdmin() {
        var ctx = SecurityContextHolder.getContext();
        return ctx != null && ctx.isAdmin();
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
        protectSelf(hostId, containerId, "stop");
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
        protectSelf(hostId, containerId, "pause");
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
        protectSelf(hostId, containerId, "kill");
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
    @ConfigLocked(section = "containers", id = "containerId", host = "hostId")
    @Auditable(resource = Resource.CONTAINERS, action = "delete", resourceIdParam = "containerId")
    public ResponseEntity<Void> removeContainer(
            @PathVariable String hostId,
            @PathVariable String containerId,
            @RequestParam(defaultValue = "false") boolean force) {
        protectSelf(hostId, containerId, "remove");
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
    @ConfigLocked(section = "containers", id = "containerId", host = "hostId")
    @Auditable(resource = Resource.CONTAINERS, action = "rename", resourceIdParam = "containerId")
    public ResponseEntity<Void> renameContainer(
            @PathVariable String hostId,
            @PathVariable String containerId,
            @RequestParam String newName) {
        protectSelf(hostId, containerId, "rename");
        if (!InputValidator.isValidContainerName(newName)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Container names may only use letters, digits, '_', '.' and '-'");
        }
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
        if (!InputValidator.isCommandSafe(request.getCommand())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Command not allowed. Dangerous commands and shell operators are blocked "
                            + "for security reasons.");
        }
        if (!isAdmin()) {
            // A shell in Docker Manager, or in any container with host access (such as a mounted
            // docker.sock), is a shell on the host. The command filter above can't prevent that.
            InspectContainerResponse target = getDockerAPI(hostId).inspectContainer(containerId);
            selfIsAdminOnly(target.getId(), "run commands in");
            List<String> hostLevel = ContainerSpecService.hostLevelSettings(target);
            if (!hostLevel.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "This container has host-level access ("
                                + String.join(", ", hostLevel)
                                + "), so only administrators can run commands in it.");
            }
        }

        String output = getDockerAPI(hostId).execCommand(containerId, request.getCommand());
        return ResponseEntity.ok(Map.of("output", output));
    }

    @GetMapping("/hosts/{hostId}/images")
    @RequirePermission(resource = Resource.IMAGES, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<Image>> listImages(
            @PathVariable String hostId, @RequestParam(defaultValue = "false") boolean dangling) {
        DockerAPI api = getDockerAPI(hostId);
        List<Image> images = dangling ? api.listDanglingImages() : api.listImages();

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
            if (allowedIds.contains(img.getId())) {
                filtered.add(img);
                continue;
            }
            if (img.getRepoTags() != null) {
                for (String tag : img.getRepoTags()) {
                    if (allowedIds.contains(tag)) {
                        filtered.add(img);
                        break;
                    }
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
        imagePolicyService.validateImageForPull(imageName);

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

    @GetMapping("/hosts/{hostId}/volumes")
    @RequirePermission(resource = Resource.VOLUMES, action = "list", hostIdParam = "hostId")
    public ResponseEntity<ListVolumesResponse> listVolumes(@PathVariable String hostId) {
        ListVolumesResponse response = getDockerAPI(hostId).listVolumes();

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
        if (!InputValidator.isValidContainerName(volumeName)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Volume names may only use letters, digits, '_', '.' and '-'");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(getDockerAPI(hostId).createVolume(volumeName));
    }

    @DeleteMapping("/hosts/{hostId}/volumes/{volumeName}")
    @RequirePermission(
            resource = Resource.VOLUMES,
            action = "delete",
            hostIdParam = "hostId",
            resourceIdParam = "volumeName")
    @ConfigLocked(section = "volumes", id = "volumeName", host = "hostId")
    @Auditable(resource = Resource.VOLUMES, action = "delete", resourceIdParam = "volumeName")
    public ResponseEntity<Void> removeVolume(
            @PathVariable String hostId, @PathVariable String volumeName) {
        getDockerAPI(hostId).removeVolume(volumeName);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/hosts/{hostId}/networks")
    @RequirePermission(resource = Resource.NETWORKS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<Network>> listNetworks(@PathVariable String hostId) {
        List<Network> networks = getDockerAPI(hostId).listNetworks();

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
        if (!InputValidator.isValidContainerName(networkName)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Network names may only use letters, digits, '_', '.' and '-'");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(getDockerAPI(hostId).createNetwork(networkName));
    }

    @DeleteMapping("/hosts/{hostId}/networks/{networkId}")
    @RequirePermission(
            resource = Resource.NETWORKS,
            action = "delete",
            hostIdParam = "hostId",
            resourceIdParam = "networkId")
    @ConfigLocked(section = "networks", id = "networkId", host = "hostId")
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
        // Cut off from its network, Docker Manager's own UI would stop answering.
        protectSelf(hostId, containerId, "disconnect");
        getDockerAPI(hostId).disconnectContainerFromNetwork(networkId, containerId);
        return ResponseEntity.ok().build();
    }

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
        List<Container> running = withoutHelpers(api.listRunningContainers());
        return ResponseEntity.ok(statsService.collect(api, running, this::buildFromContainer));
    }

    private ContainerStatsResponse buildFromContainer(Container container, Statistics stats) {
        String name =
                container.getNames() != null && container.getNames().length > 0
                        ? container.getNames()[0]
                        : container.getId().substring(0, 12);
        return buildContainerStatsResponse(container.getId(), name, stats);
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

        ContainerStatsResponse response =
                buildContainerStatsResponse(containerId, inspection.getName(), stats);
        return ResponseEntity.ok(response);
    }

    private ContainerStatsResponse buildContainerStatsResponse(
            String containerId, String containerName, Statistics stats) {
        if (containerName != null && containerName.startsWith("/")) {
            containerName = containerName.substring(1);
        }
        return ContainerStatsResponse.builder()
                .containerId(containerId)
                .containerName(containerName)
                .cpuPercent(StatsCalculator.cpuPercent(stats))
                .cpuUsage(
                        stats.getCpuStats() != null && stats.getCpuStats().getCpuUsage() != null
                                ? stats.getCpuStats().getCpuUsage().getTotalUsage()
                                : 0L)
                .systemCpuUsage(
                        stats.getCpuStats() != null ? stats.getCpuStats().getSystemCpuUsage() : 0L)
                .onlineCpus(StatsCalculator.onlineCpus(stats))
                .memoryUsage(StatsCalculator.memoryUsage(stats))
                .memoryLimit(StatsCalculator.memoryLimit(stats))
                .memoryPercent(StatsCalculator.memoryPercent(stats))
                .networkRxBytes(StatsCalculator.networkRx(stats))
                .networkTxBytes(StatsCalculator.networkTx(stats))
                .blockReadBytes(StatsCalculator.blockRead(stats))
                .blockWriteBytes(StatsCalculator.blockWrite(stats))
                .timestamp(System.currentTimeMillis())
                .pids(StatsCalculator.pids(stats))
                .build();
    }

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
