package com.wannaverse.dns;

import org.xbill.DNS.Name;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A zone ready to answer from: immutable, built by {@link ZoneBuilder} whenever something it
 * depends on changes (records, routes, health, challenges), and swapped in whole.
 *
 * @param nodes every owner name, in DNSSEC canonical order, including delegation points and the
 *     glue below them
 * @param existing owner names plus empty non-terminals (names that only exist because something
 *     below them does), outside delegations
 * @param soaNegative the SOA with the TTL of negative answers (RFC 2308), for NXDOMAIN and NODATA
 * @param nsec signed NSEC sets by owner, empty for an unsigned zone
 */
public record ZoneView(
        String id,
        Name origin,
        long serial,
        RRset soa,
        RRset soaNegative,
        long negativeTtl,
        NavigableMap<Name, Node> nodes,
        Set<Name> existing,
        NavigableMap<Name, RRset> nsec,
        boolean signed,
        String contentHash) {

    /** One owner name and what it answers per type. */
    public record Node(
            Name name, Map<Integer, Answer> answers, boolean delegation, boolean occluded) {
        public Answer get(int type) {
            return answers.get(type);
        }

        public boolean has(int type) {
            return answers.containsKey(type);
        }
    }

    /**
     * What a name answers for one type: usually one record set; with the weighted policy, one of
     * several, chosen per query by weight.
     */
    public record Answer(List<RRset> choices, int[] weights) {
        public static Answer of(RRset set) {
            return new Answer(List.of(set), new int[] {1});
        }

        public RRset pick() {
            if (choices.size() == 1) return choices.getFirst();
            int total = 0;
            for (int w : weights) total += w;
            if (total <= 0) {
                return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
            }
            int n = ThreadLocalRandom.current().nextInt(total);
            for (int i = 0; i < choices.size(); i++) {
                n -= weights[i];
                if (n < 0) return choices.get(i);
            }
            return choices.getLast();
        }
    }

    /** The NSEC set that proves {@code name} doesn't exist: the last owner at or before it. */
    public RRset nsecCovering(Name name) {
        var e = nsec.floorEntry(name);
        return e == null ? nsec.lastEntry().getValue() : e.getValue();
    }

    /** Everything in the zone, in zone-transfer order (without the closing SOA). */
    public List<RRset> allSets() {
        List<RRset> out = new ArrayList<>();
        out.add(soa);
        for (Node n : nodes.values()) {
            for (var e : n.answers().entrySet()) {
                if (e.getKey() == Type.SOA) continue;
                // A transfer can only hold one version of a name; secondaries get the first.
                out.add(e.getValue().choices().getFirst());
            }
            RRset ns = nsec.get(n.name());
            if (ns != null) out.add(ns);
        }
        return out;
    }
}
