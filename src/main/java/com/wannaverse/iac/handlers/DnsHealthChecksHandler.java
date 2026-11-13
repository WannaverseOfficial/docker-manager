package com.wannaverse.iac.handlers;

import com.wannaverse.dns.DnsService;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.DnsHealthCheck;
import com.wannaverse.persistence.DnsHealthCheckRepository;
import com.wannaverse.persistence.ManagedResource;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@code dnsHealthChecks}: checks DNS record sets follow (failover and weighted routing). */
@Component
public class DnsHealthChecksHandler
        implements ResourceHandler<DnsHealthChecksHandler.HealthCheckSpec> {

    @Data
    @Doc(
            "Checks that an address answers. Record sets that use it (healthCheck: key) are left"
                    + " out of DNS answers while it fails.")
    public static class HealthCheckSpec {
        @Doc("http (default), https or tcp")
        private String type;

        @Doc(value = "IP address or hostname to check", required = true)
        private String address;

        @Doc("Port (default 80, or 443 for https)")
        private Integer port;

        @Doc("http/https: the path requested (default /); 2xx and 3xx answers pass")
        private String path;

        @Doc("http/https: the Host header and TLS name to send (default: address)")
        private String hostHeader;

        @Doc("Seconds between checks (default 30, at least 10)")
        private Integer interval;

        @Doc("Seconds to wait for an answer (default 5)")
        private Integer timeout;

        @Doc("Failures in a row before unhealthy, and successes before healthy (default 3)")
        private Integer failureThreshold;

        @Doc("Email administrators when the status changes (default true)")
        private Boolean notify;

        @Doc("Run the check (default true)")
        private Boolean enabled;
    }

    private final DnsService dns;
    private final DnsHealthCheckRepository repo;

    public DnsHealthChecksHandler(DnsService dns, DnsHealthCheckRepository repo) {
        this.dns = dns;
        this.repo = repo;
    }

    @Override
    public String section() {
        return "dnsHealthChecks";
    }

    @Override
    public int order() {
        return 141;
    }

    @Override
    public Class<HealthCheckSpec> specType() {
        return HealthCheckSpec.class;
    }

    @Override
    public String description() {
        return "Health checks for DNS failover and weighted records, keyed by name";
    }

    @Override
    public void validate(Entry<HealthCheckSpec> entry, ApplyContext ctx) {
        HealthCheckSpec s = entry.spec();
        if (s.getType() != null
                && !Set.of("HTTP", "HTTPS", "TCP").contains(s.getType().toUpperCase(Locale.ROOT))) {
            throw new IacException("type: http, https or tcp");
        }
        if (entry.localKey().length() > 64)
            throw new IacException("the key is the check's name: at most 64 characters");
    }

    @Override
    public Optional<Target> findExisting(Entry<HealthCheckSpec> entry, ApplyContext ctx) {
        return repo.findByName(entry.localKey()).map(this::target);
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        return repo.findByName(name).map(DnsHealthCheck::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return repo.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<HealthCheckSpec> entry, ApplyContext ctx) {
        return target(dns.saveHealthCheck(null, request(entry)));
    }

    @Override
    public Target update(
            Entry<HealthCheckSpec> entry,
            Changes changes,
            ManagedResource record,
            ApplyContext ctx) {
        return target(dns.saveHealthCheck(record.getTargetId(), request(entry)));
    }

    private static DnsService.HealthCheckRequest request(Entry<HealthCheckSpec> entry) {
        HealthCheckSpec s = entry.spec();
        return new DnsService.HealthCheckRequest(
                entry.localKey(),
                s.getType() == null ? "HTTP" : s.getType().toUpperCase(Locale.ROOT),
                s.getAddress(),
                s.getPort(),
                s.getPath(),
                s.getHostHeader() == null ? "" : s.getHostHeader(),
                s.getInterval(),
                s.getTimeout(),
                s.getFailureThreshold(),
                s.getNotify() == null || s.getNotify(),
                s.getEnabled() == null || s.getEnabled());
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (repo.existsById(record.getTargetId())) dns.deleteHealthCheck(record.getTargetId());
    }

    @Override
    public Map<String, HealthCheckSpec> export(ExportContext ctx) {
        Map<String, HealthCheckSpec> out = new LinkedHashMap<>();
        for (DnsHealthCheck c : dns.listHealthChecks()) {
            HealthCheckSpec s = new HealthCheckSpec();
            if (!c.getType().equals("HTTP")) s.setType(c.getType().toLowerCase(Locale.ROOT));
            s.setAddress(c.getAddress());
            s.setPort(c.getPort());
            if (!c.getType().equals("TCP") && !"/".equals(c.getPath())) s.setPath(c.getPath());
            s.setHostHeader(c.getHostHeader());
            if (c.getIntervalSeconds() != 30) s.setInterval(c.getIntervalSeconds());
            if (c.getTimeoutSeconds() != 5) s.setTimeout(c.getTimeoutSeconds());
            if (c.getFailureThreshold() != 3) s.setFailureThreshold(c.getFailureThreshold());
            if (!c.isNotify()) s.setNotify(false);
            if (!c.isEnabled()) s.setEnabled(false);
            out.put(ctx.key(section(), c.getId(), c.getName()), s);
        }
        return out;
    }

    private Target target(DnsHealthCheck c) {
        return new Target(c.getId(), null, c.getName());
    }
}
