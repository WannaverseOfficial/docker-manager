package com.wannaverse.iac.handlers;

import com.wannaverse.controllers.DockerController;
import com.wannaverse.dns.DnsService;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.security.InputValidator;
import com.wannaverse.service.DockerService;
import com.wannaverse.service.HostConnections;

import lombok.Data;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code hosts}: the Docker daemons Docker Manager connects to. */
@Component
public class HostsHandler implements ResourceHandler<HostsHandler.HostSpec> {

    @Data
    @Doc("A Docker daemon. Other entries refer to it by its key (host: web-01).")
    public static class HostSpec {
        @Doc(
                value =
                        "Docker's address: unix:///var/run/docker.sock, tcp://server:2375 (2376"
                                + " with TLS) or ssh://user@server",
                required = true)
        private String url;

        @Doc("Name shown in Docker Manager. Defaults to the entry's key.")
        private String name;

        @Doc("TLS client certificates, for tcp:// addresses that need them")
        private TlsSpec tls;

        @Doc("Private key (without a passphrase) for ssh:// addresses")
        private Secret sshKey;

        @Doc(
                "The IPv4 address the internet reaches this host at. DNS records for its ingress"
                        + " routes point here.")
        private String publicIpv4;

        @Doc("The host's public IPv6 address, if it has one")
        private String publicIpv6;
    }

    @Data
    public static class TlsSpec {
        @Doc(value = "The CA certificate (ca.pem)", required = true)
        private Secret ca;

        @Doc(value = "The client certificate (cert.pem)", required = true)
        private Secret cert;

        @Doc(value = "The client key (key.pem)", required = true)
        private Secret key;
    }

    private final DockerHostRepository hosts;
    private final DockerController docker;
    private final DockerService dockerService;
    private final HostConnections connections;

    public HostsHandler(
            DockerHostRepository hosts,
            @Lazy DockerController docker,
            DockerService dockerService,
            HostConnections connections) {
        this.hosts = hosts;
        this.docker = docker;
        this.dockerService = dockerService;
        this.connections = connections;
    }

    @Override
    public String section() {
        return "hosts";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Class<HostSpec> specType() {
        return HostSpec.class;
    }

    @Override
    public String description() {
        return "Docker daemons to manage, keyed by the name other entries use for them";
    }

    @Override
    public void validate(Entry<HostSpec> entry, ApplyContext ctx) {
        String url = entry.spec().getUrl().trim();
        if (!InputValidator.isValidDockerHostUrl(url)) {
            throw new IacException(
                    "url: use unix:///var/run/docker.sock, tcp://server:2375 (or :2376 with TLS)"
                            + " or ssh://user@server");
        }
        TlsSpec tls = entry.spec().getTls();
        if (tls != null) {
            if (!url.startsWith("tcp://")) {
                throw new IacException("tls: certificates are only used with tcp:// addresses");
            }
            if (tls.getCa() == null || tls.getCert() == null || tls.getKey() == null) {
                throw new IacException("tls: give ca, cert and key");
            }
        }
        if (HostConnections.isSsh(url) && entry.spec().getSshKey() == null) {
            throw new IacException("sshKey: an ssh:// host needs a private key");
        }
        if (!HostConnections.isSsh(url) && entry.spec().getSshKey() != null) {
            throw new IacException("sshKey: only used with ssh:// addresses");
        }
        try {
            DnsService.ipv4(entry.spec().getPublicIpv4());
        } catch (ResponseStatusException e) {
            throw new IacException("publicIpv4: " + e.getReason());
        }
        try {
            DnsService.ipv6(entry.spec().getPublicIpv6());
        } catch (ResponseStatusException e) {
            throw new IacException("publicIpv6: " + e.getReason());
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<HostSpec> entry, ApplyContext ctx) {
        return hosts.findByDockerHostUrl(entry.spec().getUrl().trim()).map(this::target);
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        if (hosts.existsById(name)) return Optional.of(name);
        List<DockerHost> named =
                hosts.findAll().stream().filter(h -> name.equals(h.getName())).toList();
        if (named.size() > 1) {
            throw new IacException(
                    "several hosts are called \""
                            + name
                            + "\"; add them under hosts: to tell them apart");
        }
        return named.stream().findFirst().map(DockerHost::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return hosts.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<HostSpec> entry, ApplyContext ctx) {
        HostSpec spec = entry.spec();
        String url = spec.getUrl().trim();
        if (hosts.findByDockerHostUrl(url).isPresent()) {
            throw new IacException("a host with the address " + url + " already exists");
        }
        DockerHost host = new DockerHost();
        host.setDockerHostUrl(url);
        host.setName(entry.nameOr(spec.getName()));
        docker.applyCredentials(host, request(entry.nameOr(spec.getName()), spec));
        DockerController.applyPublicAddresses(host, request(entry.nameOr(spec.getName()), spec));
        docker.testConnection(host);
        return target(hosts.save(host));
    }

    @Override
    public Target update(
            Entry<HostSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        HostSpec spec = entry.spec();
        DockerHost host =
                hosts.findById(record.getTargetId())
                        .orElseThrow(() -> new IacException("the host no longer exists"));
        String url = spec.getUrl().trim();
        host.setName(entry.nameOr(spec.getName()));
        boolean reconnect = false;
        if (!url.equals(host.getDockerHostUrl())) {
            var clash = hosts.findByDockerHostUrl(url);
            if (clash.isPresent() && !clash.get().getId().equals(host.getId())) {
                throw new IacException("another host already has the address " + url);
            }
            dockerService.close(host.getDockerHostUrl());
            connections.forget(host);
            host.setDockerHostUrl(url);
            reconnect = true;
        }
        if (changes.has("url", "tls", "sshKey")) {
            if (spec.getTls() == null) {
                host.setTlsCaPem(null);
                host.setTlsCertPem(null);
                host.setTlsKeyPem(null);
            }
            docker.applyCredentials(host, request(host.getName(), spec));
            reconnect = true;
        }
        // Left out of the file: left as set on the Hosts page.
        DockerController.applyPublicAddresses(host, request(host.getName(), spec));
        if (reconnect) {
            connections.forget(host);
            dockerService.close(host.getDockerHostUrl());
            docker.testConnection(host);
        }
        return target(hosts.save(host));
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        hosts.findById(record.getTargetId())
                .ifPresent(
                        host -> {
                            dockerService.close(host.getDockerHostUrl());
                            connections.forget(host);
                            hosts.deleteById(host.getId());
                        });
    }

    @Override
    public Map<String, HostSpec> export(ExportContext ctx) {
        Map<String, HostSpec> out = new LinkedHashMap<>();
        for (DockerHost h : hosts.findAll()) {
            String key = ctx.hostKey(h.getId());
            HostSpec spec = new HostSpec();
            spec.setUrl(h.getDockerHostUrl());
            if (h.getName() != null && !h.getName().equals(key)) spec.setName(h.getName());
            if (h.hasTls()) {
                TlsSpec tls = new TlsSpec();
                tls.setCa(ctx.secret("hosts", key, "tls_ca", "CA certificate of host " + key));
                tls.setCert(
                        ctx.secret("hosts", key, "tls_cert", "client certificate of host " + key));
                tls.setKey(ctx.secret("hosts", key, "tls_key", "client key of host " + key));
                spec.setTls(tls);
            }
            spec.setPublicIpv4(h.getPublicIpv4());
            spec.setPublicIpv6(h.getPublicIpv6());
            if (h.hasSshKey()) {
                spec.setSshKey(
                        ctx.secret("hosts", key, "ssh_key", "SSH private key of host " + key));
            }
            out.put(key, spec);
        }
        return out;
    }

    private static DockerController.HostRequest request(String name, HostSpec spec) {
        TlsSpec tls = spec.getTls();
        return new DockerController.HostRequest(
                name,
                spec.getUrl().trim(),
                tls == null ? null : Secret.valueOf(tls.getCa()),
                tls == null ? null : Secret.valueOf(tls.getCert()),
                tls == null ? null : Secret.valueOf(tls.getKey()),
                Secret.valueOf(spec.getSshKey()),
                spec.getPublicIpv4(),
                spec.getPublicIpv6());
    }

    private Target target(DockerHost h) {
        return new Target(
                h.getId(), h.getId(), h.getName() == null ? h.getDockerHostUrl() : h.getName());
    }
}
