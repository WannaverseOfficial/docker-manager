package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.HostedRegistry;
import com.wannaverse.persistence.HostedRegistryRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ManagedResourceRepository;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.RegistryHostingService;

import lombok.Data;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code hostedRegistries}: the private registry Docker Manager runs on a host, keyed by the host
 * (a key of {@code hosts}, or a host's name).
 */
@Component
public class HostedRegistriesHandler
        implements ResourceHandler<HostedRegistriesHandler.HostedRegistrySpec> {

    public enum Mode {
        /** Only on the host itself, at localhost:port. */
        LOCAL,
        /** Behind ingress with HTTPS, at a hostname. */
        PUBLIC
    }

    public enum OnRemove {
        /** Removing the entry stops managing the registry; it keeps running with its images. */
        KEEP,
        /** Removing the entry removes the registry and deletes its images. */
        DELETE
    }

    @Data
    @Doc("A private Docker registry run by Docker Manager on the host this entry is named after")
    public static class HostedRegistrySpec {
        @Doc(
                "local (reachable as localhost:port on the host) or public (behind ingress, with"
                        + " HTTPS at hostname). Default: local.")
        private Mode mode;

        @Doc("The registry's hostname, such as registry.example.com (public only)")
        private String hostname;

        @Doc("Port on the host, 1024-65535 (local only, default 5000)")
        private Integer port;

        @Doc(
                value =
                        "Who can push and pull: user name -> password (at least 10 characters)."
                                + " Users not listed are removed.",
                required = true)
        private Map<String, Secret> users = new LinkedHashMap<>();

        @Doc(
                "What removing this entry does: keep (default; the registry and its images stay)"
                        + " or delete (removes the registry and its images)")
        private OnRemove onRemove;
    }

    private final HostedRegistryRepository repository;
    private final RegistryHostingService service;
    private final DockerHostRepository hosts;

    public HostedRegistriesHandler(
            HostedRegistryRepository repository,
            RegistryHostingService service,
            DockerHostRepository hosts) {
        this.repository = repository;
        this.service = service;
        this.hosts = hosts;
    }

    @Override
    public String section() {
        return "hostedRegistries";
    }

    @Override
    public int order() {
        return 230;
    }

    @Override
    public Class<HostedRegistrySpec> specType() {
        return HostedRegistrySpec.class;
    }

    @Override
    public String description() {
        return "Private Docker registries run by Docker Manager, keyed by host";
    }

    private static Mode mode(HostedRegistrySpec s) {
        return s.getMode() == null ? Mode.LOCAL : s.getMode();
    }

    @Override
    public void validate(Entry<HostedRegistrySpec> entry, ApplyContext ctx) {
        HostedRegistrySpec s = entry.spec();
        ctx.hostId(entry.localKey());
        if (mode(s) == Mode.PUBLIC) {
            if (s.getHostname() == null || s.getHostname().isBlank()) {
                throw new IacException("hostname: a public registry needs one");
            }
            if (s.getPort() != null) throw new IacException("port: only used by local registries");
        } else {
            if (s.getHostname() != null) {
                throw new IacException("hostname: only used by public registries");
            }
            int port = s.getPort() == null ? 5000 : s.getPort();
            if (port < 1024 || port > 65535) {
                throw new IacException("port: choose a port between 1024 and 65535");
            }
        }
        if (s.getUsers().isEmpty()) throw new IacException("users: give at least one user");
        for (var u : s.getUsers().entrySet()) {
            if (u.getValue() == null)
                throw new IacException("users." + u.getKey() + ": give a password");
            try {
                RegistryHostingService.checkUser(u.getKey(), u.getValue().value());
            } catch (ResponseStatusException e) {
                throw new IacException("users." + u.getKey() + ": " + e.getReason());
            }
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<HostedRegistrySpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.localKey());
        if (hostId == null) return Optional.empty();
        return repository.findByDockerHostId(hostId).map(this::target);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return repository
                .findById(record.getTargetId())
                .map(service::containerExists)
                .orElse(false);
    }

    @Override
    public Target create(Entry<HostedRegistrySpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.localKey());
        // Re-creating after the container was removed by other means: clear the leftovers first.
        // The images are in a volume that is kept.
        repository.findByDockerHostId(hostId).ifPresent(r -> remove(hostId, false));
        return make(hostId, entry.spec());
    }

    private Target make(String hostId, HostedRegistrySpec s) {
        List<Map.Entry<String, Secret>> users = new ArrayList<>(s.getUsers().entrySet());
        var first = users.getFirst();
        HostedRegistry r =
                service.create(
                        hostId,
                        mode(s).name(),
                        s.getHostname(),
                        s.getPort(),
                        first.getKey(),
                        first.getValue().value(),
                        SecurityContextHolder.getCurrentUserId(),
                        SecurityContextHolder.getCurrentUsername());
        for (var u : users.subList(1, users.size())) {
            service.setUser(hostId, u.getKey(), u.getValue().value());
        }
        return target(r);
    }

    @Override
    public Target update(
            Entry<HostedRegistrySpec> entry,
            Changes changes,
            ManagedResource record,
            ApplyContext ctx) {
        HostedRegistrySpec s = entry.spec();
        String hostId = ctx.hostId(entry.localKey());
        HostedRegistry r =
                repository
                        .findByDockerHostId(hostId)
                        .orElseThrow(() -> new IacException("the host has no registry any more"));
        boolean sameShape =
                r.getMode().equals(mode(s).name())
                        && Objects.equals(
                                r.getHostname(),
                                s.getHostname() == null
                                        ? null
                                        : s.getHostname().trim().toLowerCase(Locale.ROOT))
                        && (mode(s) == Mode.PUBLIC
                                || Objects.equals(
                                        r.getLocalPort(),
                                        s.getPort() == null ? 5000 : s.getPort()));
        if (!sameShape) {
            // Mode, hostname and port are fixed when the registry starts: start it again with the
            // new ones. Its images stay (they are in a volume that is kept).
            remove(hostId, false);
            return make(hostId, s);
        }
        if (changes.has("users")) {
            for (var u : s.getUsers().entrySet())
                service.setUser(hostId, u.getKey(), u.getValue().value());
            for (String existing : service.users(hostId)) {
                if (!s.getUsers().containsKey(existing)) service.removeUser(hostId, existing);
            }
        }
        return target(repository.findByDockerHostId(hostId).orElse(r));
    }

    @Override
    public boolean deletesOnRemove(ManagedResource record, HostedRegistrySpec appliedSpec) {
        return appliedSpec != null && appliedSpec.getOnRemove() == OnRemove.DELETE;
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        repository.findById(record.getTargetId()).ifPresent(r -> remove(r.getDockerHostId(), true));
    }

    private void remove(String hostId, boolean deleteImages) {
        service.remove(
                hostId,
                deleteImages,
                SecurityContextHolder.getCurrentUserId(),
                SecurityContextHolder.getCurrentUsername());
    }

    /** Paths only carry the host: managed when this host's registry has a record. */
    @Override
    public boolean isManaged(String targetId, String hostId, ManagedResourceRepository managed) {
        String host = hostId != null ? hostId : targetId;
        if (host == null) return false;
        return managed.findBySection(section()).stream().anyMatch(r -> host.equals(r.getHostId()));
    }

    @Override
    public Map<String, HostedRegistrySpec> export(ExportContext ctx) {
        Map<String, HostedRegistrySpec> out = new LinkedHashMap<>();
        for (HostedRegistry r : repository.findAll()) {
            if (!hosts.existsById(r.getDockerHostId())) continue;
            String key = ctx.hostKey(r.getDockerHostId());
            HostedRegistrySpec s = new HostedRegistrySpec();
            boolean pub = "PUBLIC".equals(r.getMode());
            s.setMode(pub ? Mode.PUBLIC : Mode.LOCAL);
            if (pub) s.setHostname(r.getHostname());
            else if (r.getLocalPort() != null && r.getLocalPort() != 5000)
                s.setPort(r.getLocalPort());
            Map<String, Secret> users = new LinkedHashMap<>();
            for (String u : service.users(r.getDockerHostId())) {
                users.put(
                        u,
                        ctx.secret(
                                section(),
                                key,
                                "user_" + u,
                                "password of user " + u + " of the registry on " + key));
            }
            s.setUsers(users);
            out.put(key, s);
        }
        return out;
    }

    private Target target(HostedRegistry r) {
        return new Target(r.getId(), r.getDockerHostId(), RegistryHostingService.address(r));
    }
}
