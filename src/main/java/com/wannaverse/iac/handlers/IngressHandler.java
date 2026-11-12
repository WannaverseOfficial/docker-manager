package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.IngressConfig;
import com.wannaverse.persistence.IngressConfig.IngressStatus;
import com.wannaverse.persistence.IngressConfigRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ManagedResourceRepository;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.IngressService;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code ingress}: the reverse proxy of each host, keyed by the host's key. Removing an entry turns
 * the proxy off; its routes and certificates in the file are removed first.
 */
@Component
public class IngressHandler implements ResourceHandler<IngressHandler.IngressSpec> {

    @Data
    @Doc("The ingress proxy of the host this entry's key names")
    public static class IngressSpec {
        @Doc("Run the proxy (default true). False keeps the settings but stops it.")
        private Boolean enabled;

        @Doc("Port on the host for HTTP (default 80)")
        private Integer httpPort;

        @Doc("Port on the host for HTTPS (default 443)")
        private Integer httpsPort;

        @Doc("Email Let's Encrypt sends expiry warnings to")
        private String acmeEmail;

        @Doc("Use Let's Encrypt's staging environment, for testing (default false)")
        private Boolean staging;
    }

    static final String STAGING = "https://acme-staging-v02.api.letsencrypt.org/directory";

    private final IngressService ingress;
    private final IngressConfigRepository configs;
    private final DockerHostRepository hosts;

    public IngressHandler(
            IngressService ingress, IngressConfigRepository configs, DockerHostRepository hosts) {
        this.ingress = ingress;
        this.configs = configs;
        this.hosts = hosts;
    }

    @Override
    public String section() {
        return "ingress";
    }

    @Override
    public int order() {
        return 150;
    }

    @Override
    public Class<IngressSpec> specType() {
        return IngressSpec.class;
    }

    @Override
    public String description() {
        return "The ingress proxy of each host (keyed by the host), with its routes and"
                + " certificates";
    }

    @Override
    public void validate(Entry<IngressSpec> entry, ApplyContext ctx) {
        ctx.hostId(entry.localKey());
        IngressSpec s = entry.spec();
        int http = http(s);
        int https = https(s);
        if (http < 1 || http > 65535 || https < 1 || https > 65535) {
            throw new IacException("httpPort, httpsPort: numbers from 1 to 65535");
        }
        if (http == https) throw new IacException("httpPort and httpsPort must differ");
    }

    @Override
    public Optional<Target> findExisting(Entry<IngressSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.localKey());
        if (hostId == null) return Optional.empty();
        return configs.findByDockerHostId(hostId).map(c -> target(c, entry.localKey()));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return configs.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<IngressSpec> entry, ApplyContext ctx) {
        return apply(entry, ctx);
    }

    @Override
    public Target update(
            Entry<IngressSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        return apply(entry, ctx);
    }

    private Target apply(Entry<IngressSpec> entry, ApplyContext ctx) {
        IngressSpec s = entry.spec();
        String hostId = ctx.hostId(entry.localKey());
        String userId = SecurityContextHolder.getCurrentUserId();
        String user = ctx.actor();
        boolean enabled = s.getEnabled() == null || s.getEnabled();
        boolean staging = Boolean.TRUE.equals(s.getStaging());
        String email = s.getAcmeEmail() == null ? "" : s.getAcmeEmail().trim();
        IngressConfig config = configs.findByDockerHostId(hostId).orElse(null);

        if (enabled && (config == null || config.getStatus() != IngressStatus.ENABLED)) {
            if (config != null) {
                // Ports, email and environment of a stopped proxy are set before it starts.
                ingress.updateSettings(
                        hostId,
                        email,
                        stagingChange(config, staging),
                        http(s),
                        https(s),
                        userId,
                        user);
            }
            var result =
                    ingress.enableIngress(
                            hostId,
                            new IngressService.EnableIngressRequest(
                                    http(s), https(s), 0, email, staging),
                            userId,
                            user);
            if (!result.success()) {
                throw new IacException("could not turn ingress on: " + result.error());
            }
        } else if (config != null) {
            if (!enabled && config.getStatus() != IngressStatus.DISABLED) {
                var result = ingress.disableIngress(hostId, userId, user);
                if (!result.success()) {
                    throw new IacException("could not turn ingress off: " + result.error());
                }
            }
            ingress.updateSettings(
                    hostId,
                    email,
                    stagingChange(configs.findByDockerHostId(hostId).orElse(config), staging),
                    http(s),
                    https(s),
                    userId,
                    user);
        } else {
            // Off and never set up: keep the settings for when it is turned on.
            config = new IngressConfig();
            config.setDockerHostId(hostId);
            config.setStatus(IngressStatus.DISABLED);
            config.setHttpPort(http(s));
            config.setHttpsPort(https(s));
            config.setAcmeEmail(email.isEmpty() ? null : email);
            config.setAcmeDirectoryUrl(
                    staging ? STAGING : "https://acme-v02.api.letsencrypt.org/directory");
            configs.save(config);
        }
        return target(configs.findByDockerHostId(hostId).orElseThrow(), entry.localKey());
    }

    /** The staging flag to send, or null when it doesn't change (sending it resets the account). */
    private static Boolean stagingChange(IngressConfig config, boolean staging) {
        boolean current =
                config.getAcmeDirectoryUrl() != null
                        && config.getAcmeDirectoryUrl().contains("staging");
        return current == staging ? null : staging;
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        IngressConfig config = configs.findById(record.getTargetId()).orElse(null);
        if (config == null || config.getStatus() == IngressStatus.DISABLED) return;
        var result =
                ingress.disableIngress(
                        config.getDockerHostId(),
                        SecurityContextHolder.getCurrentUserId(),
                        ctx.actor());
        if (!result.success()) {
            throw new IacException("could not turn ingress off: " + result.error());
        }
    }

    @Override
    public Map<String, IngressSpec> export(ExportContext ctx) {
        Map<String, IngressSpec> out = new LinkedHashMap<>();
        for (IngressConfig c : configs.findAll()) {
            if (!hosts.existsById(c.getDockerHostId())) continue;
            IngressSpec s = new IngressSpec();
            if (c.getStatus() == IngressStatus.DISABLED) s.setEnabled(false);
            if (c.getHttpPort() != 80) s.setHttpPort(c.getHttpPort());
            if (c.getHttpsPort() != 443) s.setHttpsPort(c.getHttpsPort());
            s.setAcmeEmail(c.getAcmeEmail());
            if (c.getAcmeDirectoryUrl() != null && c.getAcmeDirectoryUrl().contains("staging")) {
                s.setStaging(true);
            }
            out.put(ctx.hostKey(c.getDockerHostId()), s);
        }
        return out;
    }

    /** Locked API requests name the host (path variable hostId), not the configuration's id. */
    @Override
    public boolean isManaged(String targetId, String hostId, ManagedResourceRepository managed) {
        String host = hostId != null ? hostId : targetId;
        if (host == null) return false;
        return managed.findBySection(section()).stream()
                .anyMatch(r -> host.equals(r.getHostId()) || host.equals(r.getTargetId()));
    }

    private static int http(IngressSpec s) {
        return s.getHttpPort() == null ? 80 : s.getHttpPort();
    }

    private static int https(IngressSpec s) {
        return s.getHttpsPort() == null ? 443 : s.getHttpsPort();
    }

    private static Target target(IngressConfig c, String hostKey) {
        return new Target(c.getId(), c.getDockerHostId(), "Ingress on " + hostKey);
    }
}
