package com.wannaverse.iac.handlers;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.wannaverse.controllers.DockerController;
import com.wannaverse.dto.ContainerSpec;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.IacJson;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ManagedResourceRepository;
import com.wannaverse.security.InputValidator;
import com.wannaverse.service.ContainerSpecService;
import com.wannaverse.service.DockerAPI;
import com.wannaverse.service.DockerHostService;
import com.wannaverse.service.SelfContainer;

import lombok.Data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code containers}: standalone containers. Created and changed through the same code as the
 * Containers page (image rules, recreate with automatic restore on failure, history).
 */
@Component
public class ContainersHandler implements ResourceHandler<ContainersHandler.ContainerEntry> {

    private static final Logger log = LoggerFactory.getLogger(ContainersHandler.class);

    public enum Restart {
        NO,
        ALWAYS,
        UNLESS_STOPPED,
        ON_FAILURE
    }

    @Data
    @Doc("A standalone container")
    public static class ContainerEntry {
        @Doc("The Docker host (a key of hosts:, or a host's name). Default: the only host.")
        private String host;

        @Doc("The container's name in Docker. Defaults to the entry's key.")
        private String name;

        @Doc(value = "Image, such as nginx:1.27 or ghcr.io/me/app:2.1", required = true)
        private String image;

        @Doc("Command arguments. Default: the image's.")
        private List<String> command;

        @Doc("Entrypoint. Default: the image's.")
        private List<String> entrypoint;

        @Doc("Environment variables. Use ${env:NAME} or ${file:...} for secrets.")
        private Map<String, Secret> env;

        @Doc(
                "Published ports as \"host:container\", \"ip:host:container\" or \"container\""
                    + " (exposed only), with /udp for UDP: [\"8080:80\", \"127.0.0.1:5432:5432\","
                    + " \"53:53/udp\"]")
        private List<String> ports;

        @Doc(
                "Volumes as \"volume-name:/path\" or \"/host/path:/path\", with :ro for read-only."
                        + " Volumes made by Docker for the image are kept.")
        private List<String> volumes;

        @Doc("Networks to join (Docker names); the first is the main one. Default: bridge.")
        private List<String> networks;

        @Doc("Restart policy (default unless-stopped)")
        private Restart restart;

        @Doc("Labels")
        private Map<String, String> labels;

        @Doc("Run as this user (name or uid[:gid]). Default: the image's.")
        private String user;

        @Doc("Working directory. Default: the image's.")
        private String workingDir;

        @Doc("Full access to the host's devices (default false)")
        private Boolean privileged;

        @Doc("Linux capabilities to add, such as [NET_ADMIN]")
        private List<String> capAdd;

        @Doc("Memory limit in MiB (default: none)")
        private Long memoryMb;

        @Doc("CPU limit in cores, such as 1.5 (default: none)")
        private Double cpus;

        @Doc("Keep it running (default true). false creates it stopped, or stops it.")
        private Boolean running;

        @Doc(
                "Pull the image again when the container is re-created for a change (for moving"
                        + " tags such as latest). Default false: pull only when missing.")
        private Boolean pull;
    }

    private final DockerHostService dockerHosts;
    private final DockerHostRepository hosts;
    private final DockerController docker;
    private final ContainerSpecService specs;
    private final SelfContainer self;

    public ContainersHandler(
            DockerHostService dockerHosts,
            DockerHostRepository hosts,
            @Lazy DockerController docker,
            ContainerSpecService specs,
            SelfContainer self) {
        this.dockerHosts = dockerHosts;
        this.hosts = hosts;
        this.docker = docker;
        this.specs = specs;
        this.self = self;
    }

    @Override
    public String section() {
        return "containers";
    }

    @Override
    public int order() {
        return 210;
    }

    @Override
    public Class<ContainerEntry> specType() {
        return ContainerEntry.class;
    }

    @Override
    public String description() {
        return "Standalone containers (not part of a stack), keyed by container name";
    }

    @Override
    public void validate(Entry<ContainerEntry> entry, ApplyContext ctx) {
        String name = entry.nameOr(entry.spec().getName());
        // Renaming keeps its volumes (it is re-created like any change); moving hosts can't.
        DockerEntries.refuseMove(
                ctx, section(), entry.key(), entry.spec().getHost(), name, true, "container");
        if (!InputValidator.isValidContainerName(name)) {
            throw new IacException(
                    "name: container names may only use letters, digits, '_', '.' and '-'");
        }
        ctx.hostId(entry.spec().getHost());
        ContainerSpec cs = toContainerSpec(entry);
        try {
            specs.validate(cs, true);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw new IacException(e.getReason() == null ? e.getMessage() : e.getReason());
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<ContainerEntry> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        if (hostId == null) return Optional.empty();
        String name = entry.nameOr(entry.spec().getName());
        InspectContainerResponse c = inspect(DockerEntries.api(dockerHosts, hostId), name);
        if (c == null) return Optional.empty();
        Map<String, String> labels = c.getConfig() == null ? null : c.getConfig().getLabels();
        if (labels != null && labels.containsKey(ContainerSpecService.COMPOSE_PROJECT_LABEL)) {
            throw new IacException(
                    "the container "
                            + name
                            + " belongs to the compose project "
                            + labels.get(ContainerSpecService.COMPOSE_PROJECT_LABEL)
                            + "; manage it as a stack (stacks:) instead");
        }
        if (self.is(c.getId())) {
            throw new IacException(
                    name + " is Docker Manager itself; it can't manage its own container");
        }
        return Optional.of(new Target(name, hostId, name));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        if (!hosts.existsById(record.getHostId())) return false;
        return inspect(DockerEntries.api(dockerHosts, record.getHostId()), record.getTargetId())
                != null;
    }

    @Override
    public Target create(Entry<ContainerEntry> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        String name = entry.nameOr(entry.spec().getName());
        DockerAPI api = DockerEntries.api(dockerHosts, hostId);
        if (inspect(api, name) != null) {
            throw new IacException("a container called " + name + " already exists on this host");
        }
        if (Boolean.TRUE.equals(entry.spec().getPull())) {
            specs.ensureImage(api, entry.spec().getImage().trim(), true);
        }
        docker.createContainer(hostId, toContainerSpec(entry));
        return new Target(name, hostId, name);
    }

    @Override
    public Target update(
            Entry<ContainerEntry> entry,
            Changes changes,
            ManagedResource record,
            ApplyContext ctx) {
        ContainerEntry s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        String name = entry.nameOr(s.getName());

        if (!hostId.equals(record.getHostId())) {
            // Moved to another host: make it there, then remove the old one.
            Target t = create(entry, ctx);
            delete(record, ctx);
            return t;
        }
        DockerAPI api = DockerEntries.api(dockerHosts, hostId);
        InspectContainerResponse live = inspect(api, record.getTargetId());
        if (live == null) return create(entry, ctx);

        ContainerSpec desired = toContainerSpec(entry);
        ContainerSpec current = specs.specFrom(api, live);
        // Networks that ingress, databases and observability attach are theirs, not the entry's:
        // they don't count as a difference, and a re-created container keeps them.
        List<String> attached =
                current.getNetworks().stream().filter(ContainersHandler::featureNetwork).toList();
        current.getNetworks().removeIf(ContainersHandler::featureNetwork);
        boolean renamed = !name.equals(record.getTargetId());
        if (renamed || !fingerprint(desired).equals(fingerprint(current))) {
            // Keep the volumes Docker made for the image, so their data survives.
            Set<String> targets = new TreeSet<>();
            for (String v : desired.getVolumes()) targets.add(mountTarget(v));
            for (String v : current.getVolumes()) {
                String source = v.substring(0, v.indexOf(':'));
                if (DockerEntries.ANONYMOUS.matcher(source).matches()
                        && !targets.contains(mountTarget(v))) {
                    desired.getVolumes().add(v);
                }
            }
            for (String n : attached) {
                if (!desired.getNetworks().contains(n)) desired.getNetworks().add(n);
            }
            docker.updateContainer(
                    hostId, record.getTargetId(), desired, Boolean.TRUE.equals(s.getPull()));
            live = inspect(api, name);
            if (live == null) throw new IacException("the container was not re-created");
        }

        boolean wantRunning = s.getRunning() == null || s.getRunning();
        boolean isRunning =
                live.getState() != null && Boolean.TRUE.equals(live.getState().getRunning());
        if (wantRunning && !isRunning) {
            api.startContainer(live.getId());
        } else if (!wantRunning && isRunning) {
            api.stopContainer(live.getId());
        }
        return new Target(name, hostId, name);
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (!hosts.existsById(record.getHostId())) return;
        DockerAPI api = DockerEntries.api(dockerHosts, record.getHostId());
        if (inspect(api, record.getTargetId()) == null) return;
        docker.removeContainer(record.getHostId(), record.getTargetId(), true);
    }

    /** Requests name the container by id, short id or name. */
    @Override
    public boolean isManaged(String targetId, String hostId, ManagedResourceRepository managed) {
        if (targetId == null) return false;
        if (ResourceHandler.super.isManaged(targetId, hostId, managed)) return true;
        if (hostId == null || managed.findBySection(section()).isEmpty()) return false;
        try {
            InspectContainerResponse c = inspect(dockerHosts.getDockerAPI(hostId), targetId);
            return c != null
                    && ResourceHandler.super.isManaged(stripSlash(c.getName()), hostId, managed);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Map<String, ContainerEntry> export(ExportContext ctx) {
        Map<String, ContainerEntry> out = new LinkedHashMap<>();
        for (DockerHost host : hosts.findAll()) {
            DockerAPI api;
            List<Container> list;
            try {
                api = dockerHosts.getDockerAPI(host);
                list = api.listAllContainers();
            } catch (RuntimeException e) {
                log.warn("Could not list containers on {}: {}", host.getName(), e.getMessage());
                continue;
            }
            for (Container c : list) {
                String name =
                        c.getNames() == null || c.getNames().length == 0
                                ? c.getId()
                                : stripSlash(c.getNames()[0]);
                if (DockerEntries.createdByTool(c.getLabels())
                        || DockerEntries.internalName(name)
                        || self.is(c.getId())) {
                    continue;
                }
                InspectContainerResponse inspect = inspect(api, c.getId());
                if (inspect == null) continue;
                ContainerEntry e = fromContainerSpec(specs.specFrom(api, inspect));
                e.setHost(ctx.hostKey(host.getId()));
                if (!"running".equals(c.getState())) e.setRunning(false);
                String key = ctx.key(section(), name, name);
                if (out.containsKey(key)) key = key + "-" + ctx.hostKey(host.getId());
                if (e.getEnv() != null) {
                    e.setEnv(
                            DockerEntries.hideSecrets(
                                    e.getEnv(), ctx, section(), key, "container " + name));
                }
                if (!key.equals(name)) e.setName(name);
                out.put(key, e);
            }
        }
        return out;
    }

    // --- conversions -------------------------------------------------------------------------

    static ContainerSpec toContainerSpec(Entry<ContainerEntry> entry) {
        ContainerEntry s = entry.spec();
        ContainerSpec cs = new ContainerSpec();
        cs.setImage(s.getImage() == null ? null : s.getImage().trim());
        cs.setName(entry.nameOr(s.getName()));
        cs.setCommand(s.getCommand() == null ? new ArrayList<>() : new ArrayList<>(s.getCommand()));
        cs.setEntrypoint(
                s.getEntrypoint() == null ? new ArrayList<>() : new ArrayList<>(s.getEntrypoint()));
        List<String> env = new ArrayList<>();
        if (s.getEnv() != null) {
            s.getEnv()
                    .forEach(
                            (k, v) ->
                                    env.add(
                                            k
                                                    + "="
                                                    + (v == null || v.value() == null
                                                            ? ""
                                                            : v.value())));
        }
        cs.setEnv(env);
        List<ContainerSpec.PortMapping> ports = new ArrayList<>();
        if (s.getPorts() != null) {
            for (String p : s.getPorts()) ports.add(parsePort(p));
        }
        cs.setPorts(ports);
        cs.setVolumes(s.getVolumes() == null ? new ArrayList<>() : new ArrayList<>(s.getVolumes()));
        cs.setNetworks(
                s.getNetworks() == null ? new ArrayList<>() : new ArrayList<>(s.getNetworks()));
        cs.setRestartPolicy(
                s.getRestart() == null ? "unless-stopped" : IacJson.enumName(s.getRestart()));
        cs.setLabels(
                s.getLabels() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.getLabels()));
        cs.setUser(DockerEntries.blankToNull(s.getUser()));
        cs.setWorkingDir(DockerEntries.blankToNull(s.getWorkingDir()));
        cs.setPrivileged(Boolean.TRUE.equals(s.getPrivileged()));
        cs.setCapAdd(s.getCapAdd() == null ? new ArrayList<>() : new ArrayList<>(s.getCapAdd()));
        cs.setMemoryLimitMb(s.getMemoryMb() == null ? 0 : s.getMemoryMb());
        cs.setCpus(s.getCpus() == null ? 0 : s.getCpus());
        cs.setStart(s.getRunning() == null || s.getRunning());
        return cs;
    }

    static ContainerEntry fromContainerSpec(ContainerSpec cs) {
        ContainerEntry e = new ContainerEntry();
        e.setImage(cs.getImage());
        if (!cs.getCommand().isEmpty()) e.setCommand(new ArrayList<>(cs.getCommand()));
        if (!cs.getEntrypoint().isEmpty()) e.setEntrypoint(new ArrayList<>(cs.getEntrypoint()));
        if (!cs.getEnv().isEmpty()) e.setEnv(DockerEntries.envMap(cs.getEnv()));
        if (!cs.getPorts().isEmpty()) {
            e.setPorts(
                    cs.getPorts().stream().map(ContainersHandler::formatPort).distinct().toList());
        }
        List<String> volumes =
                cs.getVolumes().stream()
                        .filter(
                                v ->
                                        !DockerEntries.ANONYMOUS
                                                .matcher(
                                                        v.substring(0, Math.max(0, v.indexOf(':'))))
                                                .matches())
                        .toList();
        if (!volumes.isEmpty()) e.setVolumes(volumes);
        // Feature networks are exported where they belong (a database's containers: list...).
        List<String> networks =
                normalNetworks(cs.getNetworks()).stream().filter(n -> !featureNetwork(n)).toList();
        if (!networks.isEmpty()) e.setNetworks(networks);
        Restart restart = restart(cs.getRestartPolicy());
        if (restart != Restart.UNLESS_STOPPED) e.setRestart(restart);
        e.setLabels(DockerEntries.nullIfEmpty(cs.getLabels()));
        e.setUser(DockerEntries.blankToNull(cs.getUser()));
        e.setWorkingDir(DockerEntries.blankToNull(cs.getWorkingDir()));
        if (cs.isPrivileged()) e.setPrivileged(true);
        if (!cs.getCapAdd().isEmpty()) e.setCapAdd(new ArrayList<>(cs.getCapAdd()));
        if (cs.getMemoryLimitMb() > 0) e.setMemoryMb(cs.getMemoryLimitMb());
        if (cs.getCpus() > 0) e.setCpus(cs.getCpus());
        return e;
    }

    /** What decides whether the container must be re-created, in a comparable form. */
    static Map<String, Object> fingerprint(ContainerSpec cs) {
        Map<String, Object> m = new TreeMap<>();
        m.put("image", cs.getImage() == null ? null : cs.getImage().trim());
        m.put("command", nonNull(cs.getCommand()));
        m.put("entrypoint", nonNull(cs.getEntrypoint()));
        m.put("env", new TreeSet<>(nonNull(cs.getEnv())));
        m.put(
                "ports",
                new TreeSet<>(
                        nonNull(cs.getPorts()).stream()
                                .map(ContainersHandler::formatPort)
                                .toList()));
        TreeSet<String> volumes = new TreeSet<>();
        for (String v : nonNull(cs.getVolumes())) {
            int colon = v.indexOf(':');
            if (colon > 0 && DockerEntries.ANONYMOUS.matcher(v.substring(0, colon)).matches()) {
                continue;
            }
            volumes.add(v);
        }
        m.put("volumes", volumes);
        m.put("networks", new TreeSet<>(normalNetworks(cs.getNetworks())));
        m.put("restart", restart(cs.getRestartPolicy()));
        m.put("labels", new TreeMap<>(cs.getLabels() == null ? Map.of() : cs.getLabels()));
        m.put("user", DockerEntries.blankToNull(cs.getUser()));
        m.put("workingDir", DockerEntries.blankToNull(cs.getWorkingDir()));
        m.put("privileged", cs.isPrivileged());
        m.put("capAdd", new TreeSet<>(nonNull(cs.getCapAdd())));
        m.put("memory", cs.getMemoryLimitMb());
        m.put("cpus", Math.round(cs.getCpus() * 1000));
        m.values().removeIf(Objects::isNull);
        return m;
    }

    private static <T> List<T> nonNull(List<T> l) {
        return l == null ? List.of() : l;
    }

    /**
     * A network a Docker Manager feature connects containers to: ingress
     * (managed-ingress-network-*), a database server (dm-db-*) or observability (dm-obs-*).
     */
    static boolean featureNetwork(String name) {
        return name != null
                && (name.startsWith(com.wannaverse.service.IngressService.INGRESS_NETWORK_PREFIX)
                        || name.startsWith("dm-db-")
                        || name.startsWith("dm-obs-"));
    }

    private static List<String> normalNetworks(Collection<String> networks) {
        List<String> out = new ArrayList<>();
        if (networks == null) return out;
        for (String n : networks) {
            if (n == null || n.isBlank() || n.equals("default")) continue;
            if (!out.contains(n)) out.add(n);
        }
        if (out.equals(List.of("bridge"))) out.clear();
        return out;
    }

    private static Restart restart(String policy) {
        if (policy == null || policy.isBlank()) return Restart.NO;
        String norm = policy.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (Restart r : Restart.values()) {
            if (r.name().equals(norm)) return r;
        }
        return Restart.UNLESS_STOPPED;
    }

    /** "8080:80", "127.0.0.1:8080:80", "[::1]:8080:80", "80" and "/udp" suffixes. */
    static ContainerSpec.PortMapping parsePort(String text) {
        String s = text == null ? "" : text.trim();
        String protocol = "tcp";
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            protocol = s.substring(slash + 1).trim().toLowerCase(java.util.Locale.ROOT);
            s = s.substring(0, slash);
        }
        String ip = "";
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0 || close + 1 >= s.length() || s.charAt(close + 1) != ':') {
                throw bad(text);
            }
            ip = s.substring(1, close);
            s = s.substring(close + 2);
        }
        String[] parts = s.split(":");
        try {
            return switch (parts.length) {
                case 1 -> {
                    if (!ip.isEmpty()) throw bad(text);
                    yield new ContainerSpec.PortMapping(
                            "", null, Integer.parseInt(parts[0]), protocol);
                }
                case 2 ->
                        new ContainerSpec.PortMapping(
                                ip,
                                Integer.parseInt(parts[0]),
                                Integer.parseInt(parts[1]),
                                protocol);
                case 3 -> {
                    if (!ip.isEmpty()) throw bad(text);
                    yield new ContainerSpec.PortMapping(
                            parts[0],
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]),
                            protocol);
                }
                default -> throw bad(text);
            };
        } catch (NumberFormatException e) {
            throw bad(text);
        }
    }

    static String formatPort(ContainerSpec.PortMapping p) {
        StringBuilder sb = new StringBuilder();
        String ip = p.getHostIp();
        if (p.getHostPort() != null) {
            // 0.0.0.0 and :: both mean every interface (Docker lists the port once for each).
            if (ip != null && !ip.isBlank() && !ip.equals("0.0.0.0") && !ip.equals("::")) {
                sb.append(ip.contains(":") ? "[" + ip + "]" : ip).append(':');
            }
            sb.append(p.getHostPort()).append(':');
        }
        sb.append(p.getContainerPort());
        String proto =
                p.getProtocol() == null
                        ? "tcp"
                        : p.getProtocol().toLowerCase(java.util.Locale.ROOT);
        if (!proto.equals("tcp")) sb.append('/').append(proto);
        return sb.toString();
    }

    private static IacException bad(String port) {
        return new IacException(
                "ports: \""
                        + port
                        + "\" is not a port. Use \"8080:80\", \"127.0.0.1:8080:80\", \"80\" or"
                        + " \"53:53/udp\"");
    }

    private static String mountTarget(String volume) {
        String[] parts = volume.split(":");
        return parts.length >= 2 ? parts[1] : volume;
    }

    private static String stripSlash(String name) {
        return name != null && name.startsWith("/") ? name.substring(1) : name;
    }

    private static InspectContainerResponse inspect(DockerAPI api, String ref) {
        return DockerEntries.orNull(() -> api.inspectContainer(ref));
    }
}
