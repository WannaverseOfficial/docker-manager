package com.wannaverse.iac.handlers;

import com.wannaverse.dns.DnsService;
import com.wannaverse.dns.RecordValues;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.DnsZone;
import com.wannaverse.persistence.DnsZoneRepository;
import com.wannaverse.persistence.ManagedResource;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code dnsZones}: domains the DNS server answers for, keyed by the domain name, with their
 * records under {@code records}.
 */
@Component
public class DnsZonesHandler implements ResourceHandler<DnsZonesHandler.ZoneSpec> {

    public enum OnRemove {
        /** Removing the entry stops managing the zone; it keeps answering with its records. */
        KEEP,
        /** Removing the entry deletes the zone and all its records. */
        DELETE
    }

    @Data
    @Doc("A domain this server answers DNS for. The key is the domain name, such as example.com.")
    public static class ZoneSpec {
        @Doc("A note for people")
        private String comment;

        @Doc("TTL of records that don't set one, in seconds (default 300)")
        private Integer defaultTtl;

        @Doc("How long resolvers remember that a name doesn't exist, in seconds (default 300)")
        private Integer negativeTtl;

        @Doc("This zone's nameservers, if not the ones of the dns section")
        private List<String> nameservers;

        @Doc("Ingress routes under this domain get records pointing at their host (default true)")
        private Boolean autoRecords;

        @Doc(
                "Sign the zone (DNSSEC, default false). Give the DS record shown on the zone's"
                        + " Setup tab to your registrar.")
        private Boolean dnssec;

        @Doc(
                "What removing the entry does: keep (default) leaves the zone answering and stops"
                        + " managing it; delete removes it with all its records")
        private OnRemove onRemove;
    }

    private final DnsService dns;
    private final DnsZoneRepository zones;

    public DnsZonesHandler(DnsService dns, DnsZoneRepository zones) {
        this.dns = dns;
        this.zones = zones;
    }

    @Override
    public String section() {
        return "dnsZones";
    }

    @Override
    public int order() {
        return 142;
    }

    @Override
    public Class<ZoneSpec> specType() {
        return ZoneSpec.class;
    }

    @Override
    public String description() {
        return "Domains the DNS server answers for (hosted zones), keyed by domain name, with their"
                + " records";
    }

    @Override
    public void validate(Entry<ZoneSpec> entry, ApplyContext ctx) {
        try {
            String name = RecordValues.zoneName(entry.localKey());
            if (!name.equals(entry.localKey())) {
                throw new IacException(
                        "write the domain name in lower case, without a final dot: " + name);
            }
        } catch (IllegalArgumentException e) {
            throw new IacException("the key must be a domain name, such as example.com");
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<ZoneSpec> entry, ApplyContext ctx) {
        return zones.findByName(entry.localKey()).map(this::target);
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        return zones.findByName(name).map(DnsZone::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return zones.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<ZoneSpec> entry, ApplyContext ctx) {
        return target(dns.createZone(request(entry, true)));
    }

    @Override
    public Target update(
            Entry<ZoneSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        DnsZone z =
                zones.findById(record.getTargetId())
                        .orElseThrow(() -> new IacException("the zone no longer exists"));
        if (!z.getName().equals(entry.localKey())) {
            throw new IacException("a zone can't be renamed; add the new domain as another entry");
        }
        return target(dns.updateZone(z.getId(), request(entry, false)));
    }

    private static DnsService.ZoneRequest request(Entry<ZoneSpec> entry, boolean withName) {
        ZoneSpec s = entry.spec();
        return new DnsService.ZoneRequest(
                withName ? entry.localKey() : null,
                s.getComment() == null ? "" : s.getComment(),
                s.getDefaultTtl() == null ? 300 : s.getDefaultTtl(),
                s.getNegativeTtl() == null ? 300 : s.getNegativeTtl(),
                s.getNameservers() == null ? List.of() : s.getNameservers(),
                s.getAutoRecords() == null || s.getAutoRecords(),
                Boolean.TRUE.equals(s.getDnssec()));
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (zones.existsById(record.getTargetId())) dns.deleteZone(record.getTargetId());
    }

    @Override
    public boolean deletesOnRemove(ManagedResource record, ZoneSpec appliedSpec) {
        return appliedSpec != null && appliedSpec.getOnRemove() == OnRemove.DELETE;
    }

    @Override
    public Map<String, ZoneSpec> export(ExportContext ctx) {
        Map<String, ZoneSpec> out = new LinkedHashMap<>();
        for (DnsZone z : dns.listZones()) {
            ZoneSpec s = new ZoneSpec();
            s.setComment(z.getComment());
            if (z.getDefaultTtl() != 300) s.setDefaultTtl(z.getDefaultTtl());
            if (z.getNegativeTtl() != 300) s.setNegativeTtl(z.getNegativeTtl());
            List<String> ns = DnsService.listOf(z.getNameserversJson());
            if (!ns.isEmpty()) s.setNameservers(new ArrayList<>(ns));
            if (!z.isAutoRecords()) s.setAutoRecords(false);
            if (z.isDnssecEnabled()) s.setDnssec(true);
            out.put(z.getName(), s);
        }
        return out;
    }

    private Target target(DnsZone z) {
        return new Target(z.getId(), null, z.getName());
    }
}
