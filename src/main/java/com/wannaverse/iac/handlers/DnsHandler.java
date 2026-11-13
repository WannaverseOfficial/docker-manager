package com.wannaverse.iac.handlers;

import com.wannaverse.dns.DnsServer;
import com.wannaverse.dns.DnsService;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.DnsSettings;
import com.wannaverse.persistence.ManagedResource;

import lombok.Data;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code dns}: the built-in DNS server. */
@Component
public class DnsHandler implements ResourceHandler<DnsHandler.DnsSpec> {

    @Data
    @Doc("The built-in DNS server, which answers for the zones under dnsZones")
    public static class DnsSpec {
        @Doc("Answer DNS queries (default true)")
        private Boolean enabled;

        @Doc(
                "The nameservers to give your registrar, such as ns1.example.com. Default: ns1 and"
                        + " ns2 of each zone.")
        private List<String> nameservers;

        @Doc(
                "This server's public IPv4 address, for nameserver records. Default: the public"
                        + " address of the host Docker Manager runs on.")
        private String publicIpv4;

        @Doc("This server's public IPv6 address")
        private String publicIpv6;

        @Doc("The zones' contact address (SOA), such as hostmaster@example.com")
        private String hostmasterEmail;

        @Doc(
                "Secondary nameservers allowed to copy the zones (AXFR): IP addresses or networks."
                        + " Single addresses are also told about changes (NOTIFY).")
        private List<String> transferAllowed;

        @Doc("Most answers per second to one address over UDP (default 100; 0 turns it off)")
        private Integer rateLimit;
    }

    private final DnsService dns;
    private final DnsServer server;

    public DnsHandler(DnsService dns, DnsServer server) {
        this.dns = dns;
        this.server = server;
    }

    @Override
    public String section() {
        return "dns";
    }

    @Override
    public int order() {
        return 140;
    }

    @Override
    public boolean singleton() {
        return true;
    }

    @Override
    public Class<DnsSpec> specType() {
        return DnsSpec.class;
    }

    @Override
    public String description() {
        return "The built-in DNS server (an authoritative nameserver for your domains)";
    }

    @Override
    public void validate(Entry<DnsSpec> entry, ApplyContext ctx) {
        DnsSpec s = entry.spec();
        try {
            DnsService.ipv4(s.getPublicIpv4());
        } catch (ResponseStatusException e) {
            throw new IacException("publicIpv4: " + e.getReason());
        }
        try {
            DnsService.ipv6(s.getPublicIpv6());
        } catch (ResponseStatusException e) {
            throw new IacException("publicIpv6: " + e.getReason());
        }
        if (s.getRateLimit() != null && (s.getRateLimit() < 0 || s.getRateLimit() > 100_000)) {
            throw new IacException("rateLimit: 0 (off) to 100000");
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<DnsSpec> entry, ApplyContext ctx) {
        return Optional.of(target(dns.settings()));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return true;
    }

    @Override
    public Target create(Entry<DnsSpec> entry, ApplyContext ctx) {
        return save(entry.spec());
    }

    @Override
    public Target update(
            Entry<DnsSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        return save(entry.spec());
    }

    /** The file is the whole setting: what it leaves out goes back to the default. */
    private Target save(DnsSpec s) {
        DnsSettings saved =
                dns.updateSettings(
                        new DnsService.SettingsRequest(
                                s.getEnabled() == null || s.getEnabled(),
                                s.getNameservers() == null ? List.of() : s.getNameservers(),
                                s.getPublicIpv4() == null ? "" : s.getPublicIpv4(),
                                s.getPublicIpv6() == null ? "" : s.getPublicIpv6(),
                                s.getHostmasterEmail() == null ? "" : s.getHostmasterEmail(),
                                s.getTransferAllowed() == null ? List.of() : s.getTransferAllowed(),
                                s.getRateLimit() == null ? 100 : s.getRateLimit()));
        server.sync();
        return target(saved);
    }

    /** Removing the section turns the DNS server off; zones and records stay. */
    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        dns.updateSettings(
                new DnsService.SettingsRequest(false, null, null, null, null, null, null));
        server.sync();
    }

    @Override
    public Map<String, DnsSpec> export(ExportContext ctx) {
        DnsSettings s = dns.settings();
        List<String> ns = DnsService.listOf(s.getNameserversJson());
        if (!s.isEnabled() && ns.isEmpty() && dns.listZones().isEmpty()) return Map.of();
        DnsSpec spec = new DnsSpec();
        spec.setEnabled(s.isEnabled());
        if (!ns.isEmpty()) spec.setNameservers(new ArrayList<>(ns));
        spec.setPublicIpv4(s.getPublicIpv4());
        spec.setPublicIpv6(s.getPublicIpv6());
        spec.setHostmasterEmail(s.getHostmasterEmail());
        List<String> allowed = DnsService.listOf(s.getTransferAllowedJson());
        if (!allowed.isEmpty()) spec.setTransferAllowed(new ArrayList<>(allowed));
        if (s.getRateLimit() != 100) spec.setRateLimit(s.getRateLimit());
        return Map.of("default", spec);
    }

    private static Target target(DnsSettings s) {
        return new Target(s.getId(), null, "DNS server");
    }
}
