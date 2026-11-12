package com.wannaverse.iac.handlers;

import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.DatabaseServerRepository;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.HostedRegistryRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.security.InputValidator;
import com.wannaverse.service.DockerAPI;
import com.wannaverse.service.DockerHostService;

import lombok.Data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** {@code volumes}: named Docker volumes. */
@Component
public class VolumesHandler implements ResourceHandler<VolumesHandler.VolumeSpec> {

    private static final Logger log = LoggerFactory.getLogger(VolumesHandler.class);

    public enum OnRemove {
        KEEP,
        DELETE
    }

    @Data
    @Doc("A named Docker volume")
    public static class VolumeSpec {
        @Doc("The Docker host (a key of hosts:, or a host's name). Default: the only host.")
        private String host;

        @Doc("The volume's name in Docker. Defaults to the entry's key.")
        private String name;

        @Doc("Volume driver (default local)")
        private String driver;

        @Doc("Driver options, such as type: nfs, o: addr=10.0.0.5, device: :/exports/data")
        private Map<String, String> options;

        @Doc("Labels")
        private Map<String, String> labels;

        @Doc(
                "What removing the entry does: keep (default) leaves the volume and its data in"
                        + " place and stops managing it; delete removes it")
        private OnRemove onRemove;
    }

    private final DockerHostService dockerHosts;
    private final DockerHostRepository hosts;
    private final DatabaseServerRepository databases;
    private final HostedRegistryRepository registries;

    public VolumesHandler(
            DockerHostService dockerHosts,
            DockerHostRepository hosts,
            DatabaseServerRepository databases,
            HostedRegistryRepository registries) {
        this.dockerHosts = dockerHosts;
        this.hosts = hosts;
        this.databases = databases;
        this.registries = registries;
    }

    @Override
    public String section() {
        return "volumes";
    }

    @Override
    public int order() {
        return 110;
    }

    @Override
    public Class<VolumeSpec> specType() {
        return VolumeSpec.class;
    }

    @Override
    public String description() {
        return "Named Docker volumes. Removing an entry keeps the volume unless onRemove: delete";
    }

    @Override
    public void validate(Entry<VolumeSpec> entry, ApplyContext ctx) {
        String name = entry.nameOr(entry.spec().getName());
        if (!InputValidator.isValidContainerName(name)) {
            throw new IacException(
                    "name: volume names may only use letters, digits, '_', '.' and '-'");
        }
        ctx.hostId(entry.spec().getHost());
    }

    @Override
    public Optional<Target> findExisting(Entry<VolumeSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        if (hostId == null) return Optional.empty();
        String name = entry.nameOr(entry.spec().getName());
        InspectVolumeResponse v = inspect(DockerEntries.api(dockerHosts, hostId), name);
        return v == null ? Optional.empty() : Optional.of(new Target(name, hostId, name));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        if (!hosts.existsById(record.getHostId())) return false;
        return inspect(DockerEntries.api(dockerHosts, record.getHostId()), record.getTargetId())
                != null;
    }

    @Override
    public Target create(Entry<VolumeSpec> entry, ApplyContext ctx) {
        VolumeSpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        String name = entry.nameOr(s.getName());
        DockerAPI api = DockerEntries.api(dockerHosts, hostId);
        if (inspect(api, name) != null) {
            throw new IacException("a volume called " + name + " already exists on this host");
        }
        CreateVolumeCmd cmd = api.client().createVolumeCmd().withName(name);
        if (DockerEntries.blankToNull(s.getDriver()) != null) cmd.withDriver(s.getDriver().trim());
        if (s.getOptions() != null && !s.getOptions().isEmpty()) cmd.withDriverOpts(s.getOptions());
        if (s.getLabels() != null && !s.getLabels().isEmpty()) cmd.withLabels(s.getLabels());
        cmd.exec();
        return new Target(name, hostId, name);
    }

    /**
     * Docker can't change a volume after it is created, and re-creating it would lose its data. The
     * volume is checked against the entry; a different driver or options are an error, and labels
     * that differ are reported but left alone.
     */
    @Override
    public Target update(
            Entry<VolumeSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        VolumeSpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        String name = entry.nameOr(s.getName());
        if (!hostId.equals(record.getHostId()) || !name.equals(record.getTargetId())) {
            // The entry now names another volume. The old one keeps its data and is let go of.
            ctx.note(
                    "Volume "
                            + record.getTargetId()
                            + " was left in place: the entry now names the volume "
                            + name);
        }
        DockerAPI api = DockerEntries.api(dockerHosts, hostId);
        InspectVolumeResponse v = inspect(api, name);
        if (v == null) return create(entry, ctx);
        String driver = DockerEntries.blankToNull(s.getDriver());
        if (driver != null && !driver.equals(v.getDriver())) {
            throw new IacException(
                    "the volume uses the "
                            + v.getDriver()
                            + " driver; Docker can't change that without deleting the volume and"
                            + " its data. Use driver: "
                            + v.getDriver()
                            + ", or give the entry a new name");
        }
        if (s.getOptions() != null
                && !s.getOptions().isEmpty()
                && !Objects.equals(s.getOptions(), v.getOptions())) {
            throw new IacException(
                    "Docker can't change a volume's options without deleting the volume and its"
                            + " data. Give the entry a new name to make a new volume, or match its"
                            + " options: "
                            + v.getOptions());
        }
        if (s.getLabels() != null
                && !s.getLabels().isEmpty()
                && !Objects.equals(
                        s.getLabels(), v.getLabels() == null ? Map.of() : v.getLabels())) {
            ctx.note(
                    "Volume "
                            + name
                            + ": Docker can't change the labels of an existing volume; the new"
                            + " labels apply only if it is created again");
        }
        return new Target(name, hostId, name);
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (!hosts.existsById(record.getHostId())) return;
        DockerAPI api = DockerEntries.api(dockerHosts, record.getHostId());
        if (inspect(api, record.getTargetId()) == null) return;
        try {
            api.removeVolume(record.getTargetId());
        } catch (RuntimeException e) {
            throw new IacException(
                    "could not delete the volume "
                            + record.getTargetId()
                            + " (is a container still using it?): "
                            + e.getMessage());
        }
    }

    @Override
    public boolean deletesOnRemove(ManagedResource record, VolumeSpec appliedSpec) {
        return appliedSpec != null && appliedSpec.getOnRemove() == OnRemove.DELETE;
    }

    @Override
    public Map<String, VolumeSpec> export(ExportContext ctx) {
        Map<String, VolumeSpec> out = new LinkedHashMap<>();
        Set<String> internal = new HashSet<>();
        databases
                .findAll()
                .forEach(d -> internal.add(d.getDockerHostId() + "/" + d.getVolumeName()));
        registries
                .findAll()
                .forEach(r -> internal.add(r.getDockerHostId() + "/" + r.getVolumeName()));
        for (DockerHost host : hosts.findAll()) {
            var list = listVolumes(host);
            if (list == null) continue;
            for (InspectVolumeResponse v : list) {
                String name = v.getName();
                if (DockerEntries.internalName(name)
                        || DockerEntries.createdByTool(v.getLabels())
                        || internal.contains(host.getId() + "/" + name)) {
                    continue;
                }
                VolumeSpec s = new VolumeSpec();
                s.setHost(ctx.hostKey(host.getId()));
                if (v.getDriver() != null && !v.getDriver().equals("local")) {
                    s.setDriver(v.getDriver());
                }
                s.setOptions(DockerEntries.nullIfEmpty(v.getOptions()));
                s.setLabels(DockerEntries.nullIfEmpty(v.getLabels()));
                String key = ctx.key(section(), name, name);
                if (out.containsKey(key)) key = key + "-" + ctx.hostKey(host.getId());
                if (!key.equals(name)) s.setName(name);
                out.put(key, s);
            }
        }
        return out;
    }

    private java.util.List<InspectVolumeResponse> listVolumes(DockerHost host) {
        try {
            var r = dockerHosts.getDockerAPI(host).listVolumes();
            return r.getVolumes();
        } catch (RuntimeException e) {
            log.warn("Could not list volumes on {}: {}", host.getName(), e.getMessage());
            return null;
        }
    }

    private static InspectVolumeResponse inspect(DockerAPI api, String name) {
        return DockerEntries.orNull(() -> api.client().inspectVolumeCmd(name).exec());
    }
}
