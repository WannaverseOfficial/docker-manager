package com.wannaverse.controllers;

import com.wannaverse.dns.DelegationChecker;
import com.wannaverse.dns.DnsHealthChecker;
import com.wannaverse.dns.DnsServer;
import com.wannaverse.dns.DnsService;
import com.wannaverse.iac.ConfigLocked;
import com.wannaverse.persistence.DnsHealthCheck;
import com.wannaverse.persistence.DnsRecord;
import com.wannaverse.persistence.DnsSettings;
import com.wannaverse.persistence.DnsZone;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.AdminOnly;
import com.wannaverse.security.Auditable;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DNS: the settings of the built-in DNS server, hosted zones and their records, and health checks.
 * Administrators only: whoever controls a domain's DNS can take over its web traffic and its
 * certificates.
 */
@RestController
@AdminOnly
@RequestMapping("/api/dns")
public class DnsController {
    private final DnsService dns;
    private final DnsServer server;
    private final DnsHealthChecker checker;
    private final DelegationChecker delegation;

    public DnsController(
            DnsService dns,
            DnsServer server,
            DnsHealthChecker checker,
            DelegationChecker delegation) {
        this.dns = dns;
        this.server = server;
        this.checker = checker;
        this.delegation = delegation;
    }

    // --- settings -------------------------------------------------------------------------------

    public record SettingsView(
            boolean enabled,
            List<String> nameservers,
            String publicIpv4,
            String publicIpv6,
            String effectiveIpv4,
            String effectiveIpv6,
            String hostmasterEmail,
            List<String> transferAllowed,
            int rateLimit,
            DnsServer.Status server) {}

    private SettingsView settingsView() {
        DnsSettings s = dns.settings();
        var self = dns.selfAddressesView();
        return new SettingsView(
                s.isEnabled(),
                DnsService.listOf(s.getNameserversJson()),
                s.getPublicIpv4(),
                s.getPublicIpv6(),
                self.ipv4(),
                self.ipv6(),
                s.getHostmasterEmail(),
                DnsService.listOf(s.getTransferAllowedJson()),
                s.getRateLimit(),
                server.status());
    }

    @GetMapping("/settings")
    public SettingsView settings() {
        return settingsView();
    }

    @PutMapping("/settings")
    @ConfigLocked(section = "dns")
    @Auditable(resource = Resource.DNS, action = "update_dns_settings", captureRequestBody = true)
    public SettingsView updateSettings(@RequestBody DnsService.SettingsRequest req) {
        dns.updateSettings(req);
        server.sync();
        return settingsView();
    }

    // --- zones ----------------------------------------------------------------------------------

    public record ZoneSummary(
            String id,
            String name,
            String comment,
            int records,
            boolean dnssec,
            long serial,
            String delegationStatus,
            Long delegationCheckedAt,
            int warnings) {}

    @GetMapping("/zones")
    public List<ZoneSummary> zones() {
        List<ZoneSummary> out = new ArrayList<>();
        for (DnsZone z : dns.listZones()) {
            out.add(
                    new ZoneSummary(
                            z.getId(),
                            z.getName(),
                            z.getComment(),
                            dns.records(z.getId()).size(),
                            z.isDnssecEnabled(),
                            z.getSerial(),
                            z.getDelegationStatus(),
                            z.getDelegationCheckedAt(),
                            dns.info(z.getId()).warnings().size()));
        }
        return out;
    }

    public record RecordView(
            String id,
            String name,
            String fqdn,
            String type,
            Integer ttl,
            List<String> values,
            String routingPolicy,
            String setIdentifier,
            Integer weight,
            String failoverRole,
            String healthCheckId,
            String comment) {}

    public record Nameserver(String name, boolean inZone, String ipv4, String ipv6) {}

    public record DsView(int keyTag, int algorithm, int digestType, String digest, String record) {}

    public record ZoneDetail(
            DnsZone zone,
            List<String> nameservers,
            boolean ownNameservers,
            List<Nameserver> glue,
            List<RecordView> records,
            List<DnsService.Generated> generated,
            List<String> warnings,
            DsView ds,
            boolean dnsEnabled) {}

    @GetMapping("/zones/{id}")
    public ZoneDetail zone(@PathVariable String id) {
        DnsZone z = dns.zone(id);
        DnsSettings s = dns.settings();
        var self = dns.selfAddressesView();
        List<String> ns = new ArrayList<>();
        List<Nameserver> glue = new ArrayList<>();
        for (var n : dns.nameservers(z, s)) {
            String name = n.toString(true);
            ns.add(name);
            boolean inside = name.endsWith("." + z.getName());
            glue.add(
                    new Nameserver(
                            name,
                            inside,
                            inside ? self.ipv4() : null,
                            inside ? self.ipv6() : null));
        }
        List<RecordView> records = new ArrayList<>();
        for (DnsRecord r : dns.records(z.getId())) {
            records.add(
                    new RecordView(
                            r.getId(),
                            r.getName(),
                            r.getName().equals("@") ? z.getName() : r.getName() + "." + z.getName(),
                            r.getType(),
                            r.getTtl(),
                            DnsService.values(r),
                            r.getRoutingPolicy(),
                            r.getSetIdentifier(),
                            r.getWeight(),
                            r.getFailoverRole(),
                            r.getHealthCheckId(),
                            r.getComment()));
        }
        DsView ds =
                dns.ds(z)
                        .map(
                                d ->
                                        new DsView(
                                                d.getFootprint(),
                                                d.getAlgorithm(),
                                                d.getDigestID(),
                                                HexFormat.of()
                                                        .formatHex(d.getDigest())
                                                        .toUpperCase(),
                                                d.toString()))
                        .orElse(null);
        var info = dns.info(z.getId());
        return new ZoneDetail(
                z,
                ns,
                z.getNameserversJson() != null,
                glue,
                records,
                info.generated(),
                info.warnings(),
                ds,
                s.isEnabled());
    }

    @PostMapping("/zones")
    @Auditable(resource = Resource.DNS, action = "create_dns_zone", captureRequestBody = true)
    public DnsZone createZone(@RequestBody DnsService.ZoneRequest req) {
        DnsZone z = dns.createZone(req);
        Thread.ofVirtual().start(() -> delegation.check(z));
        return z;
    }

    @PutMapping("/zones/{id}")
    @ConfigLocked(section = "dnsZones", id = "id")
    @Auditable(
            resource = Resource.DNS,
            action = "update_dns_zone",
            resourceIdParam = "id",
            captureRequestBody = true)
    public DnsZone updateZone(@PathVariable String id, @RequestBody DnsService.ZoneRequest req) {
        return dns.updateZone(id, req);
    }

    @DeleteMapping("/zones/{id}")
    @ConfigLocked(section = "dnsZones", id = "id")
    @Auditable(resource = Resource.DNS, action = "delete_dns_zone", resourceIdParam = "id")
    public ResponseEntity<Void> deleteZone(@PathVariable String id) {
        dns.deleteZone(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/zones/{id}/check-delegation")
    public DelegationChecker.Result checkDelegation(@PathVariable String id) {
        return delegation.check(dns.zone(id));
    }

    @GetMapping(value = "/zones/{id}/export", produces = MediaType.TEXT_PLAIN_VALUE)
    public String export(@PathVariable String id) {
        return dns.exportZone(id);
    }

    public record ImportRequest(String text) {}

    @PostMapping("/zones/{id}/import")
    @Auditable(resource = Resource.DNS, action = "import_dns_zone", resourceIdParam = "id")
    public DnsService.ImportResult importZone(
            @PathVariable String id, @RequestBody ImportRequest req) {
        return dns.importZone(id, req.text());
    }

    // --- records --------------------------------------------------------------------------------

    @PostMapping("/zones/{id}/records")
    @Auditable(
            resource = Resource.DNS,
            action = "create_dns_record",
            resourceIdParam = "id",
            captureRequestBody = true)
    public DnsRecord createRecord(
            @PathVariable String id, @RequestBody DnsService.RecordRequest req) {
        return dns.createRecord(id, req);
    }

    @PutMapping("/zones/{id}/records/{recordId}")
    @ConfigLocked(section = "dnsZones.records", id = "recordId")
    @Auditable(
            resource = Resource.DNS,
            action = "update_dns_record",
            resourceIdParam = "recordId",
            captureRequestBody = true)
    public DnsRecord updateRecord(
            @PathVariable String id,
            @PathVariable String recordId,
            @RequestBody DnsService.RecordRequest req) {
        return dns.updateRecord(id, recordId, req);
    }

    @DeleteMapping("/zones/{id}/records/{recordId}")
    @ConfigLocked(section = "dnsZones.records", id = "recordId")
    @Auditable(resource = Resource.DNS, action = "delete_dns_record", resourceIdParam = "recordId")
    public ResponseEntity<Void> deleteRecord(
            @PathVariable String id, @PathVariable String recordId) {
        dns.deleteRecord(id, recordId);
        return ResponseEntity.noContent().build();
    }

    // --- health checks --------------------------------------------------------------------------

    public record HealthCheckView(DnsHealthCheck check, List<String> usedBy) {}

    @GetMapping("/health-checks")
    public List<HealthCheckView> healthChecks() {
        Map<String, List<String>> users = new LinkedHashMap<>();
        for (DnsZone z : dns.listZones()) {
            for (DnsRecord r : dns.records(z.getId())) {
                if (r.getHealthCheckId() == null) continue;
                String label =
                        (r.getName().equals("@") ? z.getName() : r.getName() + "." + z.getName())
                                + " "
                                + r.getType()
                                + (r.getSetIdentifier() == null
                                        ? ""
                                        : " (" + r.getSetIdentifier() + ")");
                users.computeIfAbsent(r.getHealthCheckId(), k -> new ArrayList<>()).add(label);
            }
        }
        List<HealthCheckView> out = new ArrayList<>();
        for (DnsHealthCheck c : dns.listHealthChecks()) {
            out.add(new HealthCheckView(c, users.getOrDefault(c.getId(), List.of())));
        }
        return out;
    }

    @PostMapping("/health-checks")
    @Auditable(
            resource = Resource.DNS,
            action = "create_dns_health_check",
            captureRequestBody = true)
    public DnsHealthCheck createHealthCheck(@RequestBody DnsService.HealthCheckRequest req) {
        return dns.saveHealthCheck(null, req);
    }

    @PutMapping("/health-checks/{id}")
    @ConfigLocked(section = "dnsHealthChecks", id = "id")
    @Auditable(
            resource = Resource.DNS,
            action = "update_dns_health_check",
            resourceIdParam = "id",
            captureRequestBody = true)
    public DnsHealthCheck updateHealthCheck(
            @PathVariable String id, @RequestBody DnsService.HealthCheckRequest req) {
        return dns.saveHealthCheck(id, req);
    }

    @DeleteMapping("/health-checks/{id}")
    @ConfigLocked(section = "dnsHealthChecks", id = "id")
    @Auditable(resource = Resource.DNS, action = "delete_dns_health_check", resourceIdParam = "id")
    public ResponseEntity<Void> deleteHealthCheck(@PathVariable String id) {
        dns.deleteHealthCheck(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/health-checks/{id}/run")
    public DnsHealthChecker.Result runHealthCheck(@PathVariable String id) {
        return checker.checkNow(dns.healthCheck(id));
    }

    // --- test tool ------------------------------------------------------------------------------

    public record LookupResult(
            String rcode,
            boolean authoritative,
            List<String> answer,
            List<String> authority,
            List<String> additional) {}

    /** Asks this server, as a resolver would: shows what the internet will get. */
    @GetMapping("/lookup")
    public LookupResult lookup(
            @RequestParam String name,
            @RequestParam(defaultValue = "A") String type,
            @RequestParam(defaultValue = "false") boolean dnssec) {
        Message m = dns.ask(name, type, dnssec);
        return new LookupResult(
                Rcode.string(m.getRcode()),
                m.getHeader().getFlag(org.xbill.DNS.Flags.AA),
                lines(m, Section.ANSWER),
                lines(m, Section.AUTHORITY),
                lines(m, Section.ADDITIONAL));
    }

    private static List<String> lines(Message m, int section) {
        List<String> out = new ArrayList<>();
        for (Record r : m.getSection(section)) {
            if (r.getType() == org.xbill.DNS.Type.OPT) continue;
            out.add(r.toString());
        }
        return out;
    }
}
