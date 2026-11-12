package com.wannaverse.iac.handlers;

import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.model.Network;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.DatabaseServerRepository;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ManagedResourceRepository;
import com.wannaverse.security.InputValidator;
import com.wannaverse.service.DockerAPI;
import com.wannaverse.service.DockerHostService;

import lombok.Data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** {@code networks}: user-defined Docker networks. */
@Component
public class NetworksHandler implements ResourceHandler<NetworksHandler.NetworkSpec> {

    private static final Logger log = LoggerFactory.getLogger(NetworksHandler.class);

    private static final Set<String> BUILT_IN =
            Set.of("bridge", "host", "none", "ingress", "docker_gwbridge");

    @Data
    @Doc("A user-defined Docker network")
    public static class NetworkSpec {
        @Doc("The Docker host (a key of hosts:, or a host's name). Default: the only host.")
        private String host;

        @Doc("The network's name in Docker. Defaults to the entry's key.")
        private String name;

        @Doc("Network driver (default bridge)")
        private String driver;

        @Doc("No access to the outside world (default false)")
        private Boolean internal;

        @Doc("Standalone containers can join an overlay network (default false)")
        private Boolean attachable;

        @Doc("Address range, such as 172.28.0.0/16. Default: Docker picks one.")
        private String subnet;

        @Doc("Gateway address within the subnet, such as 172.28.0.1")
        private String gateway;

        @Doc("Driver options, such as com.docker.network.bridge.name: br-apps")
        private Map<String, String> options;

        @Doc("Labels")
        private Map<String, String> labels;
    }

    private final DockerHostService dockerHosts;
    private final DockerHostRepository hosts;
    private final DatabaseServerRepository databases;

    public NetworksHandler(
            DockerHostService dockerHosts,
            DockerHostRepository hosts,
            DatabaseServerRepository databases) {
        this.dockerHosts = dockerHosts;
        this.hosts = hosts;
        this.databases = databases;
    }

    @Override
    public String section() {
        return "networks";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Class<NetworkSpec> specType() {
        return NetworkSpec.class;
    }

    @Override
    public String description() {
        return "User-defined Docker networks that containers and stacks can join";
    }

    @Override
    public void validate(Entry<NetworkSpec> entry, ApplyContext ctx) {
        NetworkSpec s = entry.spec();
        String name = entry.nameOr(s.getName());
        DockerEntries.refuseMove(ctx, section(), entry.key(), s.getHost(), name, false, "network");
        if (!InputValidator.isValidContainerName(name)) {
            throw new IacException(
                    "name: network names may only use letters, digits, '_', '.' and '-'");
        }
        if (BUILT_IN.contains(name)) {
            throw new IacException("name: " + name + " is one of Docker's own networks");
        }
        if (s.getGateway() != null && s.getSubnet() == null) {
            throw new IacException("gateway: also give the subnet");
        }
        ctx.hostId(s.getHost());
    }

    @Override
    public Optional<Target> findExisting(Entry<NetworkSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        if (hostId == null) return Optional.empty();
        String name = entry.nameOr(entry.spec().getName());
        Network n = inspect(DockerEntries.api(dockerHosts, hostId), name);
        return n == null ? Optional.empty() : Optional.of(new Target(name, hostId, name));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        if (!hosts.existsById(record.getHostId())) return false;
        return inspect(DockerEntries.api(dockerHosts, record.getHostId()), record.getTargetId())
                != null;
    }

    @Override
    public Target create(Entry<NetworkSpec> entry, ApplyContext ctx) {
        NetworkSpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        String name = entry.nameOr(s.getName());
        DockerAPI api = DockerEntries.api(dockerHosts, hostId);
        if (inspect(api, name) != null) {
            throw new IacException("a network called " + name + " already exists on this host");
        }
        createNetwork(api, name, s);
        return new Target(name, hostId, name);
    }

    private static void createNetwork(DockerAPI api, String name, NetworkSpec s) {
        CreateNetworkCmd cmd = api.client().createNetworkCmd().withName(name);
        cmd.withDriver(driver(s));
        if (Boolean.TRUE.equals(s.getInternal())) cmd.withInternal(true);
        if (Boolean.TRUE.equals(s.getAttachable())) cmd.withAttachable(true);
        if (s.getSubnet() != null) {
            Network.Ipam.Config config = new Network.Ipam.Config().withSubnet(s.getSubnet().trim());
            if (s.getGateway() != null) config.withGateway(s.getGateway().trim());
            cmd.withIpam(new Network.Ipam().withConfig(config));
        }
        if (s.getOptions() != null && !s.getOptions().isEmpty()) cmd.withOptions(s.getOptions());
        if (s.getLabels() != null && !s.getLabels().isEmpty()) cmd.withLabels(s.getLabels());
        cmd.exec();
    }

    /**
     * Docker can't change a network in place. When the network differs from the entry it is
     * re-created, which is only possible while no container uses it.
     */
    @Override
    public Target update(
            Entry<NetworkSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        NetworkSpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        String name = entry.nameOr(s.getName());
        DockerAPI api = DockerEntries.api(dockerHosts, hostId);
        boolean moved = !hostId.equals(record.getHostId()) || !name.equals(record.getTargetId());
        Network n = inspect(api, name);
        if (n == null) {
            createNetwork(api, name, s);
        } else if (differs(n, s)) {
            if (n.getContainers() != null && !n.getContainers().isEmpty()) {
                throw new IacException(
                        "the network "
                                + name
                                + " must be re-created to apply these settings, but containers"
                                + " still use it ("
                                + String.join(
                                        ", ",
                                        n.getContainers().values().stream()
                                                .map(Network.ContainerNetworkConfig::getName)
                                                .toList())
                                + "). Stop or disconnect them and apply again.");
            }
            api.removeNetwork(n.getId());
            createNetwork(api, name, s);
        }
        if (moved) {
            // The entry now names another network: remove the old one, as Docker Manager made it.
            try {
                if (hosts.existsById(record.getHostId())) {
                    DockerAPI old = DockerEntries.api(dockerHosts, record.getHostId());
                    if (inspect(old, record.getTargetId()) != null) {
                        old.removeNetwork(record.getTargetId());
                    }
                }
            } catch (RuntimeException e) {
                ctx.note(
                        "Could not remove the old network "
                                + record.getTargetId()
                                + ": "
                                + e.getMessage());
            }
        }
        return new Target(name, hostId, name);
    }

    /** Whether the network has other settings than the entry asks for. */
    private static boolean differs(Network n, NetworkSpec s) {
        if (!driver(s).equals(n.getDriver())) return true;
        if (Boolean.TRUE.equals(s.getInternal()) != Boolean.TRUE.equals(n.getInternal())) {
            return true;
        }
        if (Boolean.TRUE.equals(s.getAttachable()) != Boolean.TRUE.equals(n.isAttachable())) {
            return true;
        }
        if (s.getSubnet() != null) {
            List<Network.Ipam.Config> configs =
                    n.getIpam() == null || n.getIpam().getConfig() == null
                            ? List.of()
                            : n.getIpam().getConfig();
            boolean match =
                    configs.stream()
                            .anyMatch(
                                    c ->
                                            s.getSubnet().trim().equals(c.getSubnet())
                                                    && (s.getGateway() == null
                                                            || s.getGateway()
                                                                    .trim()
                                                                    .equals(c.getGateway())));
            if (!match) return true;
        }
        if (s.getOptions() != null && !s.getOptions().isEmpty()) {
            Map<String, String> have = n.getOptions() == null ? Map.of() : n.getOptions();
            for (var e : s.getOptions().entrySet()) {
                if (!Objects.equals(e.getValue(), have.get(e.getKey()))) return true;
            }
        }
        Map<String, String> wantLabels = s.getLabels() == null ? Map.of() : s.getLabels();
        Map<String, String> haveLabels = n.getLabels() == null ? Map.of() : n.getLabels();
        return !wantLabels.isEmpty() && !wantLabels.equals(haveLabels);
    }

    private static String driver(NetworkSpec s) {
        String d = DockerEntries.blankToNull(s.getDriver());
        return d == null ? "bridge" : d;
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (!hosts.existsById(record.getHostId())) return;
        DockerAPI api = DockerEntries.api(dockerHosts, record.getHostId());
        Network n = inspect(api, record.getTargetId());
        if (n == null) return;
        try {
            api.removeNetwork(n.getId());
        } catch (RuntimeException e) {
            throw new IacException(
                    "could not delete the network "
                            + record.getTargetId()
                            + " (is a container still using it?): "
                            + e.getMessage());
        }
    }

    /** Requests name the network by id or by name. */
    @Override
    public boolean isManaged(String targetId, String hostId, ManagedResourceRepository managed) {
        if (targetId == null) return false;
        if (ResourceHandler.super.isManaged(targetId, hostId, managed)) return true;
        if (hostId == null || managed.findBySection(section()).isEmpty()) return false;
        try {
            Network n = inspect(dockerHosts.getDockerAPI(hostId), targetId);
            return n != null && ResourceHandler.super.isManaged(n.getName(), hostId, managed);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Map<String, NetworkSpec> export(ExportContext ctx) {
        Map<String, NetworkSpec> out = new LinkedHashMap<>();
        Set<String> internal = new HashSet<>();
        databases
                .findAll()
                .forEach(d -> internal.add(d.getDockerHostId() + "/" + d.getNetworkName()));
        for (DockerHost host : hosts.findAll()) {
            List<Network> list;
            try {
                list = dockerHosts.getDockerAPI(host).listNetworks();
            } catch (RuntimeException e) {
                log.warn("Could not list networks on {}: {}", host.getName(), e.getMessage());
                continue;
            }
            for (Network n : list) {
                String name = n.getName();
                if (BUILT_IN.contains(name)
                        || DockerEntries.internalName(name)
                        || DockerEntries.createdByTool(n.getLabels())
                        || internal.contains(host.getId() + "/" + name)
                        || "swarm".equals(n.getScope())) {
                    continue;
                }
                NetworkSpec s = new NetworkSpec();
                s.setHost(ctx.hostKey(host.getId()));
                if (!"bridge".equals(n.getDriver())) s.setDriver(n.getDriver());
                if (Boolean.TRUE.equals(n.getInternal())) s.setInternal(true);
                if (Boolean.TRUE.equals(n.isAttachable())) s.setAttachable(true);
                s.setOptions(DockerEntries.nullIfEmpty(n.getOptions()));
                s.setLabels(DockerEntries.nullIfEmpty(n.getLabels()));
                String key = ctx.key(section(), name, name);
                if (out.containsKey(key)) key = key + "-" + ctx.hostKey(host.getId());
                if (!key.equals(name)) s.setName(name);
                out.put(key, s);
            }
        }
        return out;
    }

    private static Network inspect(DockerAPI api, String name) {
        return DockerEntries.orNull(() -> api.inspectNetwork(name));
    }
}
