package com.wannaverse.dns;

import com.wannaverse.dns.ZoneSource.Kind;
import com.wannaverse.dns.ZoneSource.Policy;
import com.wannaverse.dns.ZoneSource.RecordSet;

import org.xbill.DNS.DClass;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.NSECRecord;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Type;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a {@link ZoneSource} into a {@link ZoneView}: resolves routing policies against health,
 * lets records made by hand win over automatic ones, and signs the zone (DNSSEC) when it has a key.
 *
 * <p>Building happens in two steps so the caller can pick the SOA serial from the content: {@link
 * #prepare} works out the answers and their fingerprint, {@link #finish} adds the SOA and signs.
 */
public final class ZoneBuilder {

    /** SOA timers: refresh, retry and expire, in seconds (as most providers use). */
    static final long SOA_TTL = 900, REFRESH = 3600, RETRY = 600, EXPIRE = 1_209_600;

    static final long NS_TTL = 3600, DNSKEY_TTL = 3600;

    /** Signatures last this long, and are made again when less than half of it is left. */
    static final Duration SIGNATURE_LIFETIME = Duration.ofDays(14);

    static final Duration RESIGN_BEFORE = Duration.ofDays(7);

    /**
     * Signatures by the text of the set they sign, so a rebuild only signs what changed. Shared by
     * every zone (the text includes the owner name and the key tag).
     */
    private final Map<String, RRSIGRecord> signatures = new ConcurrentHashMap<>();

    /** The answers of a zone before the SOA and signatures are added. */
    public record Prepared(
            ZoneSource source,
            Map<Name, Map<Integer, List<Choice>>> data,
            Set<Name> delegations,
            String contentHash) {}

    /** One possible answer for a name and type, with its weight (weighted policy). */
    record Choice(RRset set, int weight) {}

    public Prepared prepare(ZoneSource src) {
        Name origin = src.origin();
        // name -> type -> record sets
        Map<Name, Map<Integer, List<RecordSet>>> byName = new TreeMap<>();
        for (RecordSet s : src.sets()) {
            if (s.records().isEmpty() || !s.name().subdomain(origin)) continue;
            byName.computeIfAbsent(s.name(), k -> new HashMap<>())
                    .computeIfAbsent(s.type(), k -> new ArrayList<>())
                    .add(s);
        }

        // NS at the apex comes from the zone's nameservers.
        List<Record> ns = new ArrayList<>();
        for (Name n : src.nameservers()) ns.add(new NSRecord(origin, DClass.IN, NS_TTL, n));
        if (!ns.isEmpty()) {
            Map<Integer, List<RecordSet>> apex =
                    byName.computeIfAbsent(origin, k -> new HashMap<>());
            apex.put(
                    Type.NS,
                    List.of(RecordSet.simple(origin, Type.NS, NS_TTL, ns, Kind.MANUAL, "zone")));
        }

        // Delegations: NS sets below the apex, made by hand.
        Set<Name> delegations = new HashSet<>();
        for (var e : byName.entrySet()) {
            if (!e.getKey().equals(origin) && e.getValue().containsKey(Type.NS)) {
                delegations.add(e.getKey());
            }
        }

        Map<Name, Map<Integer, List<Choice>>> data = new TreeMap<>();
        for (var e : byName.entrySet()) {
            Name name = e.getKey();
            Map<Integer, List<RecordSet>> types = e.getValue();
            boolean manualCname =
                    types.getOrDefault(Type.CNAME, List.of()).stream()
                            .anyMatch(s -> s.kind() == Kind.MANUAL);
            boolean belowDelegation = underDelegation(name, delegations, true);
            Map<Integer, List<Choice>> out = new TreeMap<>();
            for (var t : types.entrySet()) {
                int type = t.getKey();
                List<RecordSet> sets = t.getValue();
                boolean hasManual = sets.stream().anyMatch(s -> s.kind() == Kind.MANUAL);
                List<RecordSet> kept = new ArrayList<>();
                for (RecordSet s : sets) {
                    if (s.kind() != Kind.MANUAL) {
                        // Records made by hand win: an automatic A record never sits next to a
                        // hand-made CNAME, A record or delegation for the same name.
                        if (manualCname && type != Type.CNAME) continue;
                        if (belowDelegation && s.kind() != Kind.NAMESERVER) continue;
                        if (hasManual && s.kind() != Kind.CHALLENGE) continue;
                    }
                    kept.add(s);
                }
                if (kept.isEmpty()) continue;
                List<Choice> choices = resolve(name, type, kept);
                if (!choices.isEmpty()) out.put(type, choices);
            }
            if (!out.isEmpty()) data.put(name, out);
        }
        return new Prepared(src, data, delegations, hash(src, data));
    }

    /** Applies the routing policy: which record sets are answered, and how. */
    private static List<Choice> resolve(Name name, int type, List<RecordSet> sets) {
        Policy policy =
                sets.stream()
                        .map(RecordSet::policy)
                        .filter(p -> p != Policy.SIMPLE)
                        .findFirst()
                        .orElse(Policy.SIMPLE);
        switch (policy) {
            case FAILOVER -> {
                RecordSet primary = null, secondary = null;
                for (RecordSet s : sets) {
                    if (s.policy() != Policy.FAILOVER) continue;
                    if (s.primary()) primary = s;
                    else secondary = s;
                }
                RecordSet use;
                if (primary != null && primary.up()) use = primary;
                else if (secondary != null && secondary.up()) use = secondary;
                // Both down: answer with the primary anyway (Route 53 does the same).
                else use = primary != null ? primary : secondary;
                return use == null
                        ? List.of()
                        : List.of(new Choice(rrset(name, type, List.of(use)), 1));
            }
            case WEIGHTED -> {
                List<RecordSet> weighted =
                        sets.stream().filter(s -> s.policy() == Policy.WEIGHTED).toList();
                List<RecordSet> up = weighted.stream().filter(RecordSet::up).toList();
                // None healthy: answer as if all were, rather than not at all.
                List<RecordSet> use = up.isEmpty() ? weighted : up;
                List<Choice> out = new ArrayList<>();
                for (RecordSet s : use) {
                    out.add(new Choice(rrset(name, type, List.of(s)), Math.max(0, s.weight())));
                }
                return out;
            }
            default -> {
                // Simple sets with the same name and type are one set (several routes on one
                // hostname on different hosts become round robin). Unhealthy ones drop out
                // unless all are.
                List<RecordSet> up = sets.stream().filter(RecordSet::up).toList();
                return List.of(new Choice(rrset(name, type, up.isEmpty() ? sets : up), 1));
            }
        }
    }

    private static RRset rrset(Name name, int type, List<RecordSet> sets) {
        long ttl = sets.stream().mapToLong(RecordSet::ttl).min().orElse(300);
        Set<String> seen = new HashSet<>();
        RRset rr = new RRset();
        for (RecordSet s : sets) {
            for (Record r : s.records()) {
                Record fixed =
                        Record.newRecord(name, type, DClass.IN, ttl, r.rdataToWireCanonical());
                if (seen.add(fixed.rdataToString())) rr.addRR(fixed);
            }
        }
        return rr;
    }

    private static boolean underDelegation(Name name, Set<Name> delegations, boolean orAt) {
        for (Name d : delegations) {
            if (name.subdomain(d) && (orAt || !name.equals(d))) return true;
        }
        return false;
    }

    private static String hash(ZoneSource src, Map<Name, Map<Integer, List<Choice>>> data) {
        StringBuilder sb = new StringBuilder();
        sb.append(src.origin()).append('|').append(src.negativeTtl()).append('|');
        sb.append(src.hostmaster()).append('|');
        if (src.key() != null) sb.append(src.key().dnskey().rdataToString()).append('|');
        for (var e : data.entrySet()) {
            for (var t : e.getValue().entrySet()) {
                for (Choice c : t.getValue()) {
                    List<String> lines = new ArrayList<>();
                    for (Record r : c.set().rrs(false)) lines.add(r.toString());
                    Collections.sort(lines);
                    sb.append(lines).append('w').append(c.weight()).append('\n');
                }
            }
        }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Adds the SOA with this serial and signs everything when the zone has a key. */
    public ZoneView finish(Prepared p, long serial) {
        ZoneSource src = p.source();
        Name origin = src.origin();
        Name mname = src.nameservers().isEmpty() ? origin : src.nameservers().getFirst();
        SOARecord soaRecord =
                new SOARecord(
                        origin,
                        DClass.IN,
                        SOA_TTL,
                        mname,
                        src.hostmaster(),
                        serial,
                        REFRESH,
                        RETRY,
                        EXPIRE,
                        src.negativeTtl());
        ZoneSource.SigningKey key = src.key();
        Instant now = Instant.now();

        NavigableMap<Name, ZoneView.Node> nodes = new TreeMap<>();
        Set<Name> existing = new HashSet<>();
        for (var e : p.data().entrySet()) {
            Name name = e.getKey();
            boolean delegation = p.delegations().contains(name);
            boolean occluded = !delegation && underDelegation(name, p.delegations(), false);
            Map<Integer, ZoneView.Answer> answers = new LinkedHashMap<>();
            for (var t : e.getValue().entrySet()) {
                int type = t.getKey();
                List<RRset> sets = new ArrayList<>();
                int[] weights = new int[t.getValue().size()];
                int i = 0;
                for (Choice c : t.getValue()) {
                    RRset set = c.set();
                    // Signed: everything the zone is authoritative for. Not the NS set of a
                    // delegation (the child signs it) and not glue.
                    boolean sign = key != null && !occluded && !(delegation && type != Type.DS);
                    if (sign) set = signed(set, key, now);
                    sets.add(set);
                    weights[i++] = c.weight();
                }
                answers.put(type, new ZoneView.Answer(List.copyOf(sets), weights));
            }
            nodes.put(name, new ZoneView.Node(name, answers, delegation, occluded));
        }

        RRset soa = new RRset(soaRecord);
        if (key != null) soa = signed(soa, key, now);
        ZoneView.Node apex = nodes.get(origin);
        Map<Integer, ZoneView.Answer> apexAnswers =
                new LinkedHashMap<>(apex == null ? Map.of() : apex.answers());
        apexAnswers.put(Type.SOA, ZoneView.Answer.of(soa));
        if (key != null) {
            RRset dnskey = new RRset(key.dnskey());
            apexAnswers.put(Type.DNSKEY, ZoneView.Answer.of(signed(dnskey, key, now)));
        }
        nodes.put(origin, new ZoneView.Node(origin, apexAnswers, false, false));

        // Names that exist: owners outside delegations, and the empty names between them and the
        // apex.
        for (ZoneView.Node n : nodes.values()) {
            if (n.occluded()) continue;
            Name name = n.name();
            existing.add(name);
            for (int strip = 1; name.labels() - strip >= origin.labels(); strip++) {
                existing.add(new Name(name, strip));
            }
        }

        NavigableMap<Name, RRset> nsec = new TreeMap<>();
        if (key != null) {
            long nsecTtl = Math.min(SOA_TTL, src.negativeTtl());
            List<Name> owners = new ArrayList<>();
            for (ZoneView.Node n : nodes.values()) if (!n.occluded()) owners.add(n.name());
            // TreeMap order is DNSSEC canonical order (Name.compareTo).
            for (int i = 0; i < owners.size(); i++) {
                Name owner = owners.get(i);
                Name next = owners.get((i + 1) % owners.size());
                ZoneView.Node n = nodes.get(owner);
                TreeSet<Integer> types = new TreeSet<>(n.answers().keySet());
                types.add(Type.NSEC);
                types.add(Type.RRSIG);
                int[] bitmap = types.stream().mapToInt(Integer::intValue).toArray();
                RRset set = new RRset(new NSECRecord(owner, DClass.IN, nsecTtl, next, bitmap));
                nsec.put(owner, signed(set, key, now));
            }
        }

        long negTtl = Math.min(SOA_TTL, src.negativeTtl());
        return new ZoneView(
                src.id(),
                origin,
                serial,
                soa,
                withTtl(soa, negTtl),
                negTtl,
                Collections.unmodifiableNavigableMap(nodes),
                Set.copyOf(existing),
                Collections.unmodifiableNavigableMap(nsec),
                key != null,
                p.contentHash());
    }

    /** The same set and signatures with a lower TTL (the signatures keep the original TTL). */
    static RRset withTtl(RRset set, long ttl) {
        RRset out = new RRset();
        for (Record r : set.rrs(false)) {
            out.addRR(
                    Record.newRecord(
                            r.getName(),
                            r.getType(),
                            r.getDClass(),
                            ttl,
                            r.rdataToWireCanonical()));
        }
        for (RRSIGRecord s : set.sigs()) {
            out.addRR(
                    new RRSIGRecord(
                            s.getName(),
                            s.getDClass(),
                            ttl,
                            s.getTypeCovered(),
                            s.getAlgorithm(),
                            s.getOrigTTL(),
                            s.getExpire(),
                            s.getTimeSigned(),
                            s.getFootprint(),
                            s.getSigner(),
                            s.getSignature()));
        }
        return out;
    }

    /** A copy of the set with its signature, reusing one made earlier while it has time left. */
    private RRset signed(RRset set, ZoneSource.SigningKey key, Instant now) {
        String cacheKey = key.dnskey().getFootprint() + "|" + set;
        RRSIGRecord sig = signatures.get(cacheKey);
        if (sig == null
                || sig.getExpire().isBefore(now.plus(RESIGN_BEFORE))
                || sig.getTimeSigned().isAfter(now)) {
            try {
                // Valid from an hour ago, for resolvers whose clocks are a little behind.
                sig =
                        DNSSEC.sign(
                                set,
                                key.dnskey(),
                                key.privateKey(),
                                now.minus(Duration.ofHours(1)),
                                now.plus(SIGNATURE_LIFETIME));
            } catch (DNSSEC.DNSSECException e) {
                throw new IllegalStateException("Could not sign " + set.getName() + ": " + e, e);
            }
            signatures.put(cacheKey, sig);
        }
        RRset out = new RRset(set);
        out.addRR(sig);
        return out;
    }

    /** Forgets signatures that are no longer used or about to expire. */
    public void pruneSignatures() {
        Instant soon = Instant.now().plus(RESIGN_BEFORE);
        signatures.values().removeIf(s -> s.getExpire().isBefore(soon));
        if (signatures.size() > 200_000) signatures.clear();
    }
}
