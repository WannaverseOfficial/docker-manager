package com.wannaverse.iac.handlers;

import com.wannaverse.dns.DnsService;
import com.wannaverse.dns.RecordValues;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.ConfigExporter;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.DnsHealthCheck;
import com.wannaverse.persistence.DnsHealthCheckRepository;
import com.wannaverse.persistence.DnsRecord;
import com.wannaverse.persistence.DnsRecordRepository;
import com.wannaverse.persistence.DnsZone;
import com.wannaverse.persistence.ManagedResource;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** {@code dnsZones.<domain>.records}: the record sets of a zone. */
@Component
public class DnsRecordsHandler implements ResourceHandler<DnsRecordsHandler.RecordSpec> {

    @Data
    @Doc(
            "A record set: one name and type with its values. Records made automatically for"
                    + " ingress routes don't need to be listed.")
    public static class RecordSpec {
        @Doc(
                "The name in the zone: www, @ for the domain itself, *.dev for a wildcard. Default:"
                        + " the entry's key.")
        private String name;

        @Doc(value = "A, AAAA, CNAME, MX, TXT, SRV, CAA, NS, PTR or DS", required = true)
        private String type;

        @Doc("Seconds resolvers may keep the answer (default: the zone's)")
        private Integer ttl;

        @Doc(
                value =
                        "The values as in a zone file, such as 203.0.113.10 or \"10"
                                + " mail.example.com\". Hostnames are always complete.",
                required = true)
        private List<String> values;

        @Doc("simple (default), weighted or failover")
        private String routing;

        @Doc("weighted/failover: tells record sets with the same name and type apart")
        private String setId;

        @Doc("weighted: share of answers, 0 to 255 (default 1)")
        private Integer weight;

        @Doc("failover: primary or secondary")
        private String failover;

        @Doc("Answer with this set only while this health check (a key of dnsHealthChecks) passes")
        private String healthCheck;

        @Doc("A note for people")
        private String comment;
    }

    private final DnsService dns;
    private final DnsRecordRepository records;
    private final DnsHealthCheckRepository checks;

    public DnsRecordsHandler(
            DnsService dns, DnsRecordRepository records, DnsHealthCheckRepository checks) {
        this.dns = dns;
        this.records = records;
        this.checks = checks;
    }

    @Override
    public String section() {
        return "dnsZones.records";
    }

    @Override
    public String parentSection() {
        return "dnsZones";
    }

    @Override
    public String parentField() {
        return "records";
    }

    @Override
    public int order() {
        return 143;
    }

    @Override
    public Class<RecordSpec> specType() {
        return RecordSpec.class;
    }

    @Override
    public String description() {
        return "Record sets of the zone, keyed by a name of your choice (the record's name by"
                + " default)";
    }

    @Override
    public void validate(Entry<RecordSpec> entry, ApplyContext ctx) {
        RecordSpec s = entry.spec();
        String zone = entry.parentKey();
        String name;
        try {
            name = RecordValues.relativeName(nameOf(entry), zone);
        } catch (IllegalArgumentException e) {
            throw new IacException("name: " + e.getMessage());
        }
        if (s.getType() == null || !RecordValues.supported(s.getType())) {
            throw new IacException("type: one of " + String.join(", ", RecordValues.TYPES));
        }
        try {
            RecordValues.parse(
                    RecordValues.absolute(name, zone),
                    s.getType(),
                    s.getTtl() == null ? 300 : s.getTtl(),
                    s.getValues());
        } catch (IllegalArgumentException e) {
            throw new IacException("values: " + e.getMessage());
        }
        String routing = routing(s);
        if (routing != null && !Set.of("WEIGHTED", "FAILOVER").contains(routing)) {
            throw new IacException("routing: simple, weighted or failover");
        }
        if (s.getFailover() != null
                && !Set.of("PRIMARY", "SECONDARY")
                        .contains(s.getFailover().toUpperCase(Locale.ROOT))) {
            throw new IacException("failover: primary or secondary");
        }
        if (s.getHealthCheck() != null) ctx.ref("dnsHealthChecks", s.getHealthCheck());
    }

    @Override
    public Optional<Target> findExisting(Entry<RecordSpec> entry, ApplyContext ctx) {
        String zoneId = ctx.ref("dnsZones", entry.parentKey());
        if (zoneId == null) return Optional.empty();
        String name = RecordValues.relativeName(nameOf(entry), entry.parentKey());
        String type = entry.spec().getType().toUpperCase(Locale.ROOT);
        String setId = blank(entry.spec().getSetId()) ? null : entry.spec().getSetId().trim();
        return records.findByZoneId(zoneId).stream()
                .filter(r -> r.getName().equals(name) && r.getType().equals(type))
                .filter(
                        r ->
                                setId == null
                                        ? r.getSetIdentifier() == null
                                        : setId.equalsIgnoreCase(r.getSetIdentifier()))
                .findFirst()
                .map(r -> target(r, entry.parentKey()));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return records.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<RecordSpec> entry, ApplyContext ctx) {
        String zoneId = ctx.ref("dnsZones", entry.parentKey());
        return target(dns.createRecord(zoneId, request(entry, ctx)), entry.parentKey());
    }

    @Override
    public Target update(
            Entry<RecordSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        DnsRecord r =
                records.findById(record.getTargetId())
                        .orElseThrow(() -> new IacException("the record no longer exists"));
        String zoneId = ctx.ref("dnsZones", entry.parentKey());
        if (!r.getZoneId().equals(zoneId)) {
            throw new IacException("a record can't move to another zone; give it a new key there");
        }
        return target(dns.updateRecord(zoneId, r.getId(), request(entry, ctx)), entry.parentKey());
    }

    private DnsService.RecordRequest request(Entry<RecordSpec> entry, ApplyContext ctx) {
        RecordSpec s = entry.spec();
        String check =
                s.getHealthCheck() == null ? null : ctx.ref("dnsHealthChecks", s.getHealthCheck());
        String routing = routing(s);
        return new DnsService.RecordRequest(
                nameOf(entry),
                s.getType(),
                s.getTtl(),
                s.getValues(),
                routing == null ? "SIMPLE" : routing,
                s.getSetId(),
                s.getWeight(),
                s.getFailover(),
                check,
                s.getComment());
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        records.findById(record.getTargetId())
                .ifPresent(r -> dns.deleteRecord(r.getZoneId(), r.getId()));
    }

    @Override
    public Map<String, RecordSpec> export(ExportContext ctx) {
        Map<String, String> checkNames = new HashMap<>();
        for (DnsHealthCheck c : checks.findAll()) {
            checkNames.put(c.getId(), ctx.key("dnsHealthChecks", c.getId(), c.getName()));
        }
        Map<String, RecordSpec> out = new LinkedHashMap<>();
        for (DnsZone z : dns.listZones()) {
            Set<String> used = new HashSet<>();
            for (DnsRecord r : dns.records(z.getId())) {
                String fallback =
                        (r.getName().equals("@") ? "apex" : r.getName())
                                + (r.getType().equals("A") && r.getSetIdentifier() == null
                                        ? ""
                                        : "-" + r.getType().toLowerCase(Locale.ROOT))
                                + (r.getSetIdentifier() == null
                                        ? ""
                                        : "-" + ConfigExporter.slug(r.getSetIdentifier()));
                String key = ctx.key(section(), r.getId(), fallback);
                String base = key;
                for (int i = 2; !used.add(key); i++) key = base + "-" + i;
                RecordSpec s = new RecordSpec();
                if (!key.equals(r.getName())) s.setName(r.getName());
                s.setType(r.getType());
                s.setTtl(r.getTtl());
                s.setValues(new ArrayList<>(DnsService.values(r)));
                if (r.getRoutingPolicy() != null)
                    s.setRouting(r.getRoutingPolicy().toLowerCase(Locale.ROOT));
                s.setSetId(r.getSetIdentifier());
                s.setWeight(r.getWeight());
                if (r.getFailoverRole() != null)
                    s.setFailover(r.getFailoverRole().toLowerCase(Locale.ROOT));
                if (r.getHealthCheckId() != null)
                    s.setHealthCheck(checkNames.get(r.getHealthCheckId()));
                s.setComment(r.getComment());
                out.put(z.getName() + "/" + key, s);
            }
        }
        return out;
    }

    private static String nameOf(Entry<RecordSpec> entry) {
        return entry.nameOr(entry.spec().getName());
    }

    private static String routing(RecordSpec s) {
        if (blank(s.getRouting())) return null;
        String r = s.getRouting().trim().toUpperCase(Locale.ROOT);
        return r.equals("SIMPLE") ? null : r;
    }

    private static Target target(DnsRecord r, String zone) {
        return new Target(
                r.getId(),
                null,
                (r.getName().equals("@") ? zone : r.getName() + "." + zone)
                        + " "
                        + r.getType()
                        + (Objects.isNull(r.getSetIdentifier())
                                ? ""
                                : " (" + r.getSetIdentifier() + ")"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
