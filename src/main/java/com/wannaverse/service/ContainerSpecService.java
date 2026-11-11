package com.wannaverse.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.wannaverse.dto.ContainerSpec;
import com.wannaverse.security.InputValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Creates containers from a {@link ContainerSpec} and recreates existing ones with changes.
 *
 * <p>Recreating starts from the container's current configuration (from inspect) and applies the
 * spec on top, so settings the UI does not show (devices, ulimits, log driver, DNS, healthcheck,
 * extra hosts...) survive an edit. The swap is safe: the old container is stopped and renamed, the
 * new one is created and started, and only then is the old one removed. If anything fails, the new
 * container is removed and the old one is renamed back and restarted.
 */
@Service
public class ContainerSpecService {
    private static final Logger log = LoggerFactory.getLogger(ContainerSpecService.class);

    public static final String COMPOSE_PROJECT_LABEL = "com.docker.compose.project";

    private final ImagePolicyService imagePolicyService;
    private final DockerRegistryService registryService;

    public ContainerSpecService(
            ImagePolicyService imagePolicyService, DockerRegistryService registryService) {
        this.imagePolicyService = imagePolicyService;
        this.registryService = registryService;
    }

    // --- validation --------------------------------------------------------------------------

    /**
     * Rejects invalid specs, and settings that would let a non-admin escape the container onto the
     * host: privileged mode, added capabilities, host networking and bind mounts of host paths
     * (including the Docker socket, which is full control of the host).
     */
    public void validate(ContainerSpec spec, boolean isAdmin) {
        validateSettings(spec, isAdmin);
        imagePolicyService.validateImageForContainerCreation(spec.getImage().trim());
    }

    /** Everything {@link #validate} checks except the image policy. */
    public void validateSettings(ContainerSpec spec, boolean isAdmin) {
        if (spec.getImage() == null || !InputValidator.isValidImageName(spec.getImage().trim())) {
            throw bad("Enter a valid image name, for example nginx:latest");
        }
        if (spec.getName() != null
                && !spec.getName().isBlank()
                && !InputValidator.isValidContainerName(spec.getName().trim())) {
            throw bad("Container names may only use letters, digits, '_', '.' and '-'");
        }
        for (ContainerSpec.PortMapping p : nullSafe(spec.getPorts())) {
            if (p.getContainerPort() < 1 || p.getContainerPort() > 65535) {
                throw bad("Container port must be between 1 and 65535");
            }
            if (p.getHostPort() != null && (p.getHostPort() < 1 || p.getHostPort() > 65535)) {
                throw bad("Host port must be between 1 and 65535");
            }
            String proto = p.getProtocol() == null ? "tcp" : p.getProtocol().toLowerCase();
            if (!proto.equals("tcp") && !proto.equals("udp") && !proto.equals("sctp")) {
                throw bad("Port protocol must be tcp or udp");
            }
        }
        for (String env : nullSafe(spec.getEnv())) {
            if (env == null || env.isBlank() || env.startsWith("=")) {
                throw bad("Environment variables must look like NAME=value");
            }
        }
        for (String v : nullSafe(spec.getVolumes())) {
            try {
                Bind.parse(v);
            } catch (Exception e) {
                throw bad("Invalid volume '" + v + "'. Use name:/path or /host/path:/path[:ro]");
            }
        }
        String restart = spec.getRestartPolicy() == null ? "no" : spec.getRestartPolicy();
        if (!Set.of("no", "always", "unless-stopped", "on-failure").contains(restart)) {
            throw bad("Unknown restart policy: " + restart);
        }

        if (!isAdmin) {
            // Docker Manager's own labels hide containers or mark them as its helpers, and compose
            // labels would make a container pass for part of someone else's stack.
            for (String label :
                    spec.getLabels() == null ? Set.<String>of() : spec.getLabels().keySet()) {
                if (label.startsWith("com.wannaverse.")
                        || label.startsWith("com.docker.compose.")) {
                    throw forbidden("Only administrators can set the label " + label);
                }
            }
            if (spec.isPrivileged()) {
                throw forbidden("Only administrators can create privileged containers");
            }
            if (!nullSafe(spec.getCapAdd()).isEmpty()) {
                throw forbidden("Only administrators can add Linux capabilities");
            }
            // "host" and "container:<id>" share another namespace's network stack.
            if (nullSafe(spec.getNetworks()).stream()
                    .anyMatch(n -> n == null || n.equals("host") || n.contains(":"))) {
                throw forbidden(
                        "Only administrators can use host networking or another container's"
                                + " network");
            }
            for (String v : nullSafe(spec.getVolumes())) {
                String source = v.split(":", 2)[0];
                if (source.startsWith("/")
                        || source.startsWith(".")
                        || source.startsWith("~")
                        || source.contains("\\")) {
                    throw forbidden(
                            "Only administrators can mount folders from the host. Use a named"
                                    + " volume instead.");
                }
            }
        }
    }

    /**
     * Named volumes a non-admin may not mount: Docker Manager's own (its database and secrets) and
     * pipeline workspaces. Mounting them in a container of your own would expose everything.
     */
    public void checkVolumesForNonAdmin(DockerAPI api, ContainerSpec spec, String selfId) {
        Set<String> protectedVolumes = new HashSet<>();
        if (selfId != null) {
            try {
                var self = api.inspectContainer(selfId);
                if (self.getMounts() != null) {
                    for (var m : self.getMounts()) {
                        if (m.getName() != null) protectedVolumes.add(m.getName());
                    }
                }
            } catch (Exception ignored) {
                // Docker Manager doesn't run on this host
            }
        }
        for (String v : nullSafe(spec.getVolumes())) {
            String source = v.split(":", 2)[0];
            if (protectedVolumes.contains(source) || source.startsWith("dm-pipeline-")) {
                throw forbidden("Only administrators can mount the volume " + source);
            }
        }
    }

    // --- create ------------------------------------------------------------------------------

    public String create(DockerAPI api, ContainerSpec spec) {
        ensureImage(api, spec.getImage().trim(), false);
        String id = createOnly(api.client(), spec, null, null);
        connectExtraNetworks(api.client(), id, spec, Map.of());
        if (spec.isStart()) {
            api.startContainer(id);
        }
        return id;
    }

    /** Recreates a container that no longer exists from its saved inspect data. */
    public String createFromInspect(DockerAPI api, InspectContainerResponse inspect) {
        ContainerSpec spec = specFrom(inspect);
        ensureImage(api, spec.getImage(), false);
        Map<String, ContainerNetwork> networks =
                inspect.getNetworkSettings() != null
                                && inspect.getNetworkSettings().getNetworks() != null
                        ? inspect.getNetworkSettings().getNetworks()
                        : Map.of();
        String id = createOnly(api.client(), spec, inspect, spec.getName());
        connectExtraNetworks(api.client(), id, spec, networks);
        api.startContainer(id);
        return id;
    }

    /** Pulls the image if missing (or always, when {@code alwaysPull}). */
    public void ensureImage(DockerAPI api, String image, boolean alwaysPull) {
        if (!alwaysPull) {
            try {
                api.inspectImage(image);
                return;
            } catch (NotFoundException ignored) {
                // pull below
            }
        }
        imagePolicyService.validateImageForPull(image);
        try {
            api.pullImage(withDefaultTag(image), registryService.getAuthConfig(image));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Image pull was interrupted");
        }
    }

    static String withDefaultTag(String image) {
        if (image.contains("@")) return image;
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        return colon > slash ? image : image + ":latest";
    }

    /**
     * Builds the create command from the spec. When {@code base} (an existing container's inspect)
     * is given, its configuration is the starting point and the spec is applied on top.
     */
    private String createOnly(
            DockerClient client,
            ContainerSpec spec,
            InspectContainerResponse base,
            String nameOverride) {
        CreateContainerCmd cmd = client.createContainerCmd(spec.getImage().trim());

        String name = nameOverride != null ? nameOverride : spec.getName();
        if (name != null && !name.isBlank()) cmd.withName(name.trim());

        HostConfig hostConfig =
                base != null && base.getHostConfig() != null
                        ? base.getHostConfig()
                        : HostConfig.newHostConfig();

        if (base != null && base.getConfig() != null) {
            ContainerConfig old = base.getConfig();
            if (old.getHealthcheck() != null) cmd.withHealthcheck(old.getHealthcheck());
            // A hostname someone chose (not the container ID Docker picks): some apps, such as
            // RabbitMQ, keep their data in a folder named after it.
            String hostname = old.getHostName();
            if (hostname != null
                    && !hostname.isBlank()
                    && base.getId() != null
                    && !base.getId().startsWith(hostname)) {
                cmd.withHostName(hostname);
            }
            if (old.getTty() != null) cmd.withTty(old.getTty());
            if (old.getStdinOpen() != null) cmd.withStdinOpen(old.getStdinOpen());
            if (old.getVolumes() != null && !old.getVolumes().isEmpty()) {
                cmd.withVolumes(
                        old.getVolumes().keySet().stream().map(Volume::new).toArray(Volume[]::new));
            }
            if (old.getDomainName() != null && !old.getDomainName().isBlank()) {
                cmd.withDomainName(old.getDomainName());
            }
        }

        if (!nullSafe(spec.getCommand()).isEmpty()) cmd.withCmd(spec.getCommand());
        if (!nullSafe(spec.getEntrypoint()).isEmpty()) cmd.withEntrypoint(spec.getEntrypoint());
        cmd.withEnv(nullSafe(spec.getEnv()));
        if (spec.getLabels() != null && !spec.getLabels().isEmpty()) {
            cmd.withLabels(spec.getLabels());
        }
        if (spec.getUser() != null && !spec.getUser().isBlank()) cmd.withUser(spec.getUser());
        if (spec.getWorkingDir() != null && !spec.getWorkingDir().isBlank()) {
            cmd.withWorkingDir(spec.getWorkingDir());
        }

        // Ports
        Ports bindings = new Ports();
        List<ExposedPort> exposed = new ArrayList<>();
        for (ContainerSpec.PortMapping p : nullSafe(spec.getPorts())) {
            ExposedPort port =
                    new ExposedPort(
                            p.getContainerPort(),
                            InternetProtocol.parse(
                                    p.getProtocol() == null ? "tcp" : p.getProtocol()));
            exposed.add(port);
            if (p.getHostPort() != null) {
                String ip = p.getHostIp() == null ? "" : p.getHostIp().trim();
                bindings.bind(port, new Ports.Binding(ip, String.valueOf(p.getHostPort())));
            }
        }
        cmd.withExposedPorts(exposed);
        hostConfig.withPortBindings(bindings);

        // Volumes. Mounts of type "volume"/"bind" created with --mount are also expressed as binds
        // here, so remove them to avoid duplicate mount points.
        hostConfig.withBinds(nullSafe(spec.getVolumes()).stream().map(Bind::parse).toList());
        hostConfig.withMounts(null);

        hostConfig.withRestartPolicy(restartPolicy(spec.getRestartPolicy()));
        hostConfig.withPrivileged(spec.isPrivileged());
        hostConfig.withCapAdd(
                nullSafe(spec.getCapAdd()).stream()
                        .map(c -> Capability.valueOf(c.toUpperCase(Locale.ROOT)))
                        .toArray(Capability[]::new));
        hostConfig.withMemory(spec.getMemoryLimitMb() > 0 ? spec.getMemoryLimitMb() << 20 : 0L);
        hostConfig.withNanoCPUs(spec.getCpus() > 0 ? (long) (spec.getCpus() * 1e9) : 0L);

        List<String> networks = nullSafe(spec.getNetworks());
        if (!networks.isEmpty()) {
            hostConfig.withNetworkMode(networks.get(0));
        } else if (base == null) {
            hostConfig.withNetworkMode("bridge");
        }
        // Aliases and a fixed address on the first network (the others are connected after).
        String primary = networks.isEmpty() ? hostConfig.getNetworkMode() : networks.get(0);
        ContainerNetwork before = previousNetwork(base, primary);
        if (before != null) {
            List<String> aliases = userAliases(before, base);
            if (!aliases.isEmpty()) cmd.withAliases(aliases);
            if (before.getIpamConfig() != null
                    && before.getIpamConfig().getIpv4Address() != null
                    && !before.getIpamConfig().getIpv4Address().isBlank()) {
                cmd.withIpv4Address(before.getIpamConfig().getIpv4Address());
            }
        }

        cmd.withHostConfig(hostConfig);
        return cmd.exec().getId();
    }

    private void connectExtraNetworks(
            DockerClient client,
            String containerId,
            ContainerSpec spec,
            Map<String, ContainerNetwork> previous) {
        List<String> networks = nullSafe(spec.getNetworks());
        for (int i = 1; i < networks.size(); i++) {
            String network = networks.get(i);
            var connect =
                    client.connectToNetworkCmd()
                            .withNetworkId(network)
                            .withContainerId(containerId);
            ContainerNetwork old = previous.get(network);
            if (old != null) {
                ContainerNetwork settings = new ContainerNetwork();
                List<String> aliases = old.getAliases() == null ? List.of() : old.getAliases();
                if (!aliases.isEmpty()) settings.withAliases(aliases);
                if (old.getIpamConfig() != null && old.getIpamConfig().getIpv4Address() != null) {
                    settings.withIpamConfig(
                            new ContainerNetwork.Ipam()
                                    .withIpv4Address(old.getIpamConfig().getIpv4Address()));
                }
                connect.withContainerNetwork(settings);
            }
            connect.exec();
        }
    }

    /** The old container's settings on a network, if it was on it. */
    private static ContainerNetwork previousNetwork(InspectContainerResponse base, String network) {
        if (base == null || network == null || base.getNetworkSettings() == null) return null;
        Map<String, ContainerNetwork> nets = base.getNetworkSettings().getNetworks();
        return nets == null ? null : nets.get(network);
    }

    /** Aliases someone set, without the container ID Docker adds on its own. */
    private static List<String> userAliases(ContainerNetwork net, InspectContainerResponse base) {
        if (net.getAliases() == null) return List.of();
        String shortId =
                base.getId() == null
                        ? ""
                        : base.getId().substring(0, Math.min(12, base.getId().length()));
        return net.getAliases().stream().filter(a -> !a.equals(shortId)).toList();
    }

    // --- read --------------------------------------------------------------------------------

    /**
     * Like {@link #specFrom(InspectContainerResponse)}, but leaves out environment variables and
     * labels that only repeat the image's own defaults. Docker adds those back when the container
     * is created, so the form only shows what was actually set.
     */
    public ContainerSpec specFrom(DockerAPI api, InspectContainerResponse inspect) {
        ContainerSpec spec = specFrom(inspect);
        try {
            var image = api.inspectImage(inspect.getImageId()).getConfig();
            if (image != null) {
                Set<String> imageEnv =
                        image.getEnv() == null ? Set.of() : new HashSet<>(List.of(image.getEnv()));
                spec.setEnv(spec.getEnv().stream().filter(e -> !imageEnv.contains(e)).toList());
                if (image.getLabels() != null) {
                    Map<String, String> labels = new LinkedHashMap<>(spec.getLabels());
                    image.getLabels().forEach((k, v) -> labels.remove(k, v));
                    spec.setLabels(labels);
                }
                if (image.getCmd() != null
                        && Arrays.equals(image.getCmd(), spec.getCommand().toArray())) {
                    spec.setCommand(new ArrayList<>());
                }
                if (image.getEntrypoint() != null
                        && Arrays.equals(image.getEntrypoint(), spec.getEntrypoint().toArray())) {
                    spec.setEntrypoint(new ArrayList<>());
                }
                if (Objects.equals(image.getWorkingDir(), spec.getWorkingDir()))
                    spec.setWorkingDir(null);
                if (Objects.equals(image.getUser(), spec.getUser())) spec.setUser(null);
            }
        } catch (Exception e) {
            // Image removed or unreadable: show the full configuration.
        }
        return spec;
    }

    /** The edit form's starting values, derived from a running or stopped container. */
    public ContainerSpec specFrom(InspectContainerResponse inspect) {
        ContainerSpec spec = new ContainerSpec();
        ContainerConfig config = inspect.getConfig();
        HostConfig host = inspect.getHostConfig();

        spec.setImage(config.getImage());
        spec.setName(stripSlash(inspect.getName()));
        spec.setCommand(config.getCmd() == null ? new ArrayList<>() : List.of(config.getCmd()));
        spec.setEntrypoint(
                config.getEntrypoint() == null
                        ? new ArrayList<>()
                        : List.of(config.getEntrypoint()));
        spec.setEnv(config.getEnv() == null ? new ArrayList<>() : List.of(config.getEnv()));
        spec.setLabels(
                config.getLabels() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(config.getLabels()));
        spec.setUser(config.getUser());
        spec.setWorkingDir(config.getWorkingDir());

        List<ContainerSpec.PortMapping> ports = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (host != null && host.getPortBindings() != null) {
            host.getPortBindings()
                    .getBindings()
                    .forEach(
                            (exposed, binds) -> {
                                if (binds == null || binds.length == 0) return;
                                for (Ports.Binding b : binds) {
                                    String key =
                                            exposed.getPort()
                                                    + exposed.getProtocol().toString()
                                                    + b.getHostIp()
                                                    + b.getHostPortSpec();
                                    if (!seen.add(key)) continue;
                                    Integer hostPort = null;
                                    try {
                                        hostPort = Integer.parseInt(b.getHostPortSpec());
                                    } catch (Exception ignored) {
                                    }
                                    String ip = b.getHostIp();
                                    ports.add(
                                            new ContainerSpec.PortMapping(
                                                    ip == null || ip.equals("0.0.0.0") ? "" : ip,
                                                    hostPort,
                                                    exposed.getPort(),
                                                    exposed.getProtocol().toString()));
                                }
                            });
        }
        spec.setPorts(ports);

        List<String> volumes = new ArrayList<>();
        if (inspect.getMounts() != null) {
            for (InspectContainerResponse.Mount m : inspect.getMounts()) {
                String dest = m.getDestination() != null ? m.getDestination().getPath() : null;
                if (dest == null) continue;
                String source =
                        m.getName() != null && !m.getName().isBlank() ? m.getName() : m.getSource();
                // Anonymous volumes have 64-hex names; keep them so data survives a recreate.
                String mode = Boolean.FALSE.equals(m.getRW()) ? ":ro" : "";
                volumes.add(source + ":" + dest + mode);
            }
        }
        spec.setVolumes(volumes);

        List<String> networks = new ArrayList<>();
        String mode = host != null ? host.getNetworkMode() : null;
        if (mode != null && !mode.isBlank() && !mode.equals("default")) networks.add(mode);
        if (inspect.getNetworkSettings() != null
                && inspect.getNetworkSettings().getNetworks() != null) {
            for (String n : inspect.getNetworkSettings().getNetworks().keySet()) {
                if (!networks.contains(n)) networks.add(n);
            }
        }
        spec.setNetworks(networks);

        RestartPolicy restart = host != null ? host.getRestartPolicy() : null;
        spec.setRestartPolicy(
                restart == null || restart.getName() == null || restart.getName().isBlank()
                        ? "no"
                        : restart.getName());
        spec.setPrivileged(host != null && Boolean.TRUE.equals(host.getPrivileged()));
        spec.setCapAdd(
                host == null || host.getCapAdd() == null
                        ? new ArrayList<>()
                        : Arrays.stream(host.getCapAdd()).map(Enum::name).toList());
        spec.setMemoryLimitMb(
                host != null && host.getMemory() != null ? host.getMemory() >> 20 : 0);
        spec.setCpus(host != null && host.getNanoCPUs() != null ? host.getNanoCPUs() / 1e9 : 0);
        spec.setStart(true);
        return spec;
    }

    // --- recreate ----------------------------------------------------------------------------

    /**
     * Replaces a container with one built from {@code spec}, keeping its other settings. Returns
     * the new container's ID.
     */
    public String recreate(
            DockerAPI api, String containerRef, ContainerSpec spec, boolean pull, boolean isAdmin) {
        DockerClient client = api.client();
        InspectContainerResponse old = api.inspectContainer(containerRef);
        // Work with the ID from here on: once the old container is renamed, its name belongs to
        // the new one, and acting on the name would remove the new container instead.
        String containerId = old.getId();

        // Settings the form does not show are carried over from the old container. Some of them
        // give host access, and a non-admin could otherwise run their own image or command with
        // them.
        if (!isAdmin) {
            List<String> hostLevel = hostLevelSettings(old);
            if (!hostLevel.isEmpty()) {
                throw forbidden(
                        "This container uses host-level settings ("
                                + String.join(", ", hostLevel)
                                + "). Only administrators can change it.");
            }
        }

        String project =
                old.getConfig().getLabels() != null
                        ? old.getConfig().getLabels().get(COMPOSE_PROJECT_LABEL)
                        : null;
        if (project != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This container belongs to the stack '"
                            + project
                            + "'. Edit the stack's compose file instead.");
        }

        // Stopping a --rm container deletes it, so it could never be put back if creating the new
        // one failed.
        if (old.getHostConfig() != null
                && Boolean.TRUE.equals(old.getHostConfig().getAutoRemove())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This container was started with --rm, so it is deleted as soon as it stops"
                            + " and can't be safely replaced. Create a new container instead.");
        }

        String image = spec.getImage().trim();
        ensureImage(api, image, pull);

        String name =
                spec.getName() != null && !spec.getName().isBlank()
                        ? spec.getName().trim()
                        : stripSlash(old.getName());
        String backupName = stripSlash(old.getName()) + "-old-" + System.currentTimeMillis();
        boolean wasRunning = Boolean.TRUE.equals(old.getState().getRunning());
        Map<String, ContainerNetwork> oldNetworks =
                old.getNetworkSettings() != null && old.getNetworkSettings().getNetworks() != null
                        ? old.getNetworkSettings().getNetworks()
                        : Map.of();

        if (wasRunning) {
            client.stopContainerCmd(containerId).withTimeout(30).exec();
        }
        client.renameContainerCmd(containerId).withName(backupName).exec();

        String newId = null;
        try {
            newId = createOnly(client, spec, old, name);
            connectExtraNetworks(client, newId, spec, oldNetworks);
            if (spec.isStart() || wasRunning) {
                client.startContainerCmd(newId).exec();
                // Only let go of the original once the new one has shown it works.
                waitUntilHealthy(api, newId);
            }
        } catch (RuntimeException e) {
            log.warn("Recreating {} failed, restoring the original: {}", name, e.getMessage());
            if (newId != null) {
                try {
                    client.removeContainerCmd(newId).withForce(true).exec();
                } catch (Exception ignored) {
                }
            }
            try {
                client.renameContainerCmd(containerId).withName(stripSlash(old.getName())).exec();
                if (wasRunning) client.startContainerCmd(containerId).exec();
            } catch (Exception restoreError) {
                log.error("Could not restore {}: {}", name, restoreError.getMessage());
            }
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Could not apply the changes, the original container was kept: "
                            + e.getMessage());
        }

        // Keep volumes: they hold the container's data and are reused by the new container.
        try {
            client.removeContainerCmd(containerId).withRemoveVolumes(false).withForce(true).exec();
        } catch (Exception e) {
            log.warn(
                    "New container started but the old one could not be removed: {}",
                    e.getMessage());
        }
        return newId;
    }

    /** How long a container without a health check must keep running to count as started. */
    static final long SETTLE_MS = 5_000;

    /** The longest to wait for a health check to pass. Still "starting" after that is accepted. */
    static final long HEALTH_WAIT_MS = 120_000;

    /**
     * Waits until a just-started container is up: healthy if it has a health check, otherwise still
     * running after a few seconds. Throws if it stops, keeps restarting or reports itself
     * unhealthy, so the caller can put the original container back.
     */
    void waitUntilHealthy(DockerAPI api, String containerId) {
        long start = System.currentTimeMillis();
        while (true) {
            var state = api.inspectContainer(containerId).getState();
            if (Boolean.TRUE.equals(state.getRestarting())
                    || !Boolean.TRUE.equals(state.getRunning())) {
                throw new IllegalStateException(
                        "the new container stopped right after starting (exit code "
                                + state.getExitCodeLong()
                                + "). Check its logs");
            }
            var health = state.getHealth();
            long waited = System.currentTimeMillis() - start;
            if (health == null || health.getStatus() == null) {
                if (waited >= SETTLE_MS) return;
            } else if ("healthy".equalsIgnoreCase(health.getStatus())) {
                return;
            } else if ("unhealthy".equalsIgnoreCase(health.getStatus())) {
                throw new IllegalStateException("the new container failed its health check");
            } else if (waited >= HEALTH_WAIT_MS) {
                log.info("{} is still starting after {}s; keeping it", containerId, waited / 1000);
                return;
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the new container");
            }
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    static RestartPolicy restartPolicy(String name) {
        return switch (name == null ? "no" : name) {
            case "always" -> RestartPolicy.alwaysRestart();
            case "unless-stopped" -> RestartPolicy.unlessStoppedRestart();
            case "on-failure" -> RestartPolicy.onFailureRestart(5);
            default -> RestartPolicy.noRestart();
        };
    }

    static String stripSlash(String name) {
        return name != null && name.startsWith("/") ? name.substring(1) : name;
    }

    /** Carried-over settings that reach outside the container's own sandbox. */
    public static List<String> hostLevelSettings(InspectContainerResponse inspect) {
        List<String> found = new ArrayList<>();
        HostConfig h = inspect.getHostConfig();
        if (h == null) return found;
        if (Boolean.TRUE.equals(h.getPrivileged())) found.add("privileged");
        if (h.getCapAdd() != null && h.getCapAdd().length > 0) found.add("added capabilities");
        if (h.getDevices() != null && h.getDevices().length > 0) found.add("devices");
        if (h.getDeviceCgroupRules() != null && !h.getDeviceCgroupRules().isEmpty()) {
            found.add("device rules");
        }
        if (notDefault(h.getPidMode())) found.add("pid mode");
        if (h.getIpcMode() != null
                && (h.getIpcMode().equals("host") || h.getIpcMode().startsWith("container:"))) {
            found.add("ipc mode");
        }
        if (notDefault(h.getUtSMode())) found.add("uts mode");
        if (notDefault(h.getUsernsMode())) found.add("user namespace mode");
        if (notDefault(h.getCgroupParent())) found.add("cgroup parent");
        if (h.getSecurityOpts() != null && !h.getSecurityOpts().isEmpty()) {
            found.add("security options");
        }
        if (h.getVolumesFrom() != null && h.getVolumesFrom().length > 0) found.add("volumes from");
        String net = h.getNetworkMode();
        if (net != null && (net.equals("host") || net.startsWith("container:"))) {
            found.add("network mode " + (net.equals("host") ? "host" : "container"));
        }
        if (h.getMounts() != null
                && h.getMounts().stream()
                        .anyMatch(
                                m ->
                                        m.getType() != null
                                                && m.getType()
                                                        != com.github.dockerjava.api.model.MountType
                                                                .VOLUME)) {
            found.add("host mounts");
        }
        if (h.getBinds() != null) {
            for (Bind b : h.getBinds()) {
                if (b.getPath() != null && b.getPath().startsWith("/")) {
                    found.add("host folders");
                    break;
                }
            }
        }
        return found;
    }

    private static boolean notDefault(String mode) {
        return mode != null && !mode.isBlank();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
