package com.wannaverse.dns;

import com.wannaverse.persistence.DnsZone;
import com.wannaverse.persistence.DnsZoneRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks what the domain's registry publishes for a hosted zone: its nameservers (NS) and DNSSEC
 * key digests (DS). The registry's own servers are asked directly, so the answer is what the
 * registrar set up, with no caches in between and whether or not this server is reachable yet.
 */
@Component
public class DelegationChecker {
    private static final Logger log = LoggerFactory.getLogger(DelegationChecker.class);

    private final DnsService dns;
    private final DnsZoneRepository zones;

    public DelegationChecker(DnsService dns, DnsZoneRepository zones) {
        this.dns = dns;
        this.zones = zones;
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 6 * 3600_000)
    public void checkAll() {
        for (DnsZone z : zones.findAll()) {
            try {
                check(z);
            } catch (Exception e) {
                log.debug("Delegation check of {} failed: {}", z.getName(), e.getMessage());
            }
        }
    }

    public record Result(
            String status,
            String detail,
            List<String> registryNameservers,
            List<String> registryDs) {}

    public synchronized Result check(DnsZone zone) {
        Result r;
        try {
            r = inspect(zone);
        } catch (Exception e) {
            r =
                    new Result(
                            "UNKNOWN",
                            "Could not ask the registry: " + e.getMessage(),
                            List.of(),
                            List.of());
        }
        zones.updateDelegation(zone.getId(), r.status(), r.detail(), System.currentTimeMillis());
        return r;
    }

    private Result inspect(DnsZone zone) throws Exception {
        Name name = DnsService.name(zone.getName());
        Message nsAnswer = walk(name, Type.NS);
        if (nsAnswer == null) {
            return new Result(
                    "UNKNOWN",
                    "The registry for "
                            + zone.getName()
                            + " didn't answer. Is outgoing DNS (port 53) allowed?",
                    List.of(),
                    List.of());
        }
        Message dsAnswer = null;
        if (lastParent != null) {
            try {
                dsAnswer = ask(lastParent.get(), name, Type.DS);
            } catch (Exception ignored) {
                // Reported as "no DS" below.
            }
        }
        if (nsAnswer.getRcode() == Rcode.NXDOMAIN) {
            return new Result(
                    "NO",
                    "The registry doesn't know " + zone.getName() + ". Is the domain registered?",
                    List.of(),
                    List.of());
        }

        Set<String> registry = new TreeSet<>();
        for (int section : new int[] {Section.ANSWER, Section.AUTHORITY}) {
            for (Record r : nsAnswer.getSection(section)) {
                if (r instanceof NSRecord ns && r.getName().equals(name)) {
                    registry.add(ns.getTarget().toString(true).toLowerCase(Locale.ROOT));
                }
            }
        }
        Set<String> ours = new TreeSet<>();
        for (Name n : dns.nameservers(zone, dns.settings()))
            ours.add(n.toString(true).toLowerCase(Locale.ROOT));

        List<String> ds = new ArrayList<>();
        if (dsAnswer != null) {
            for (Record r : dsAnswer.getSection(Section.ANSWER)) {
                if (r instanceof DSRecord d) ds.add(d.rdataToString());
            }
        }

        List<String> lines = new ArrayList<>();
        String status;
        Set<String> common = new LinkedHashSet<>(registry);
        common.retainAll(ours);
        if (registry.isEmpty()) {
            status = "NO";
            lines.add("The registry lists no nameservers for " + zone.getName() + ".");
        } else if (registry.equals(ours)) {
            status = "OK";
            lines.add("The registrar points " + zone.getName() + " at these nameservers.");
        } else if (!common.isEmpty()) {
            status = "PARTIAL";
            Set<String> other = new TreeSet<>(registry);
            other.removeAll(ours);
            Set<String> missing = new TreeSet<>(ours);
            missing.removeAll(registry);
            if (!other.isEmpty())
                lines.add("The registrar also lists " + String.join(", ", other) + ".");
            if (!missing.isEmpty())
                lines.add("Not listed at the registrar: " + String.join(", ", missing) + ".");
        } else {
            status = "NO";
            lines.add("The registrar still points at " + String.join(", ", registry) + ".");
        }

        var ourDs = dns.ds(zone);
        boolean here = !"NO".equals(status);
        if (!here) {
            // Still served elsewhere: DS records belong to that DNS host.
            if (!ds.isEmpty()) {
                lines.add(
                        "DNSSEC is on for the domain at its current DNS host. Before pointing the"
                                + " nameservers here, turn DNSSEC on for this zone and replace the"
                                + " DS record at your registrar with this zone's, or remove it;"
                                + " otherwise the domain stops resolving for most people.");
            }
        } else if (ourDs.isPresent()) {
            String digest =
                    HexFormat.of().formatHex(ourDs.get().getDigest()).toUpperCase(Locale.ROOT);
            boolean match =
                    ds.stream()
                            .anyMatch(
                                    d ->
                                            d.replace(" ", "")
                                                    .toUpperCase(Locale.ROOT)
                                                    .endsWith(digest));
            if (match) lines.add("DNSSEC: the registry has this zone's DS record.");
            else if (ds.isEmpty())
                lines.add("DNSSEC: add the DS record at your registrar to turn validation on.");
            else {
                lines.add(
                        "DNSSEC: the registry has a DS record that doesn't match this zone's key."
                            + " Validating resolvers will fail to resolve the domain until it is"
                            + " replaced.");
                status = "PARTIAL";
            }
        } else if (!ds.isEmpty()) {
            lines.add(
                    "DNSSEC: the registry has DS records but this zone isn't signed. Validating"
                            + " resolvers will fail to resolve the domain: remove the DS records at"
                            + " your registrar or turn DNSSEC on.");
            status = "PARTIAL";
        }
        return new Result(status, String.join(" ", lines), List.copyOf(registry), ds);
    }

    /** Root servers (a, c, d, e, f, k, m.root-servers.net), where the walk starts. */
    private static final List<String> ROOTS =
            List.of(
                    "198.41.0.4",
                    "192.33.4.12",
                    "199.7.91.13",
                    "192.203.230.10",
                    "192.5.5.241",
                    "193.0.14.129",
                    "202.12.27.33");

    /** The parent zone's server that gave the last delegation, for asking about DS. */
    private java.util.function.Supplier<InetAddress> lastParent;

    /**
     * Asks the way a resolver does, from the root down, without relying on this machine's resolver
     * (some, such as Docker Desktop's, don't answer NS questions). Stops at the answer of the
     * zone's parent: the referral that names the zone's nameservers.
     */
    private Message walk(Name name, int type) throws Exception {
        List<InetAddress> servers = new ArrayList<>();
        for (String r : ROOTS) servers.add(InetAddress.getByName(r));
        lastParent = null;
        for (int depth = 0; depth < 10 && !servers.isEmpty(); depth++) {
            Message reply = null;
            InetAddress used = null;
            for (InetAddress server : servers.subList(0, Math.min(4, servers.size()))) {
                try {
                    reply = ask(server, name, type);
                    used = server;
                    break;
                } catch (Exception ignored) {
                    // Try the next one.
                }
            }
            if (reply == null) return null;
            InetAddress from = used;
            if (reply.getRcode() == Rcode.NXDOMAIN || !reply.getSection(Section.ANSWER).isEmpty()) {
                lastParent = () -> from;
                return reply;
            }
            Name cut = null;
            List<Name> next = new ArrayList<>();
            for (Record r : reply.getSection(Section.AUTHORITY)) {
                if (r instanceof NSRecord ns) {
                    cut = r.getName();
                    next.add(ns.getTarget());
                }
            }
            if (cut == null) {
                lastParent = () -> from;
                return reply;
            }
            if (cut.equals(name)) {
                // The parent's referral to the zone itself: what the registrar set up.
                lastParent = () -> from;
                return reply;
            }
            List<InetAddress> glue = new ArrayList<>();
            for (Record r : reply.getSection(Section.ADDITIONAL)) {
                if (r instanceof ARecord a && next.contains(r.getName())) glue.add(a.getAddress());
            }
            if (glue.isEmpty()) {
                for (Name n : next) {
                    try {
                        for (InetAddress a : InetAddress.getAllByName(n.toString(true))) {
                            if (a instanceof java.net.Inet4Address) glue.add(a);
                        }
                    } catch (Exception ignored) {
                        // Next name.
                    }
                    if (glue.size() >= 3) break;
                }
            }
            servers = glue;
        }
        return null;
    }

    private static Message ask(InetAddress server, Name name, int type) throws Exception {
        SimpleResolver res = new SimpleResolver(server);
        res.setTimeout(Duration.ofSeconds(4));
        Message q = Message.newQuery(Record.newRecord(name, type, DClass.IN));
        q.getHeader().unsetFlag(org.xbill.DNS.Flags.RD);
        return res.send(q);
    }
}
