package com.wannaverse.dns;

import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedFlags;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.NameTooLongException;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Answers DNS queries from the hosted zones, as an authoritative-only server (RFC 1034 section
 * 4.3.2, with the DNSSEC additions of RFC 4035 section 3.1). It never looks anything up elsewhere,
 * so it can't be used as an open resolver.
 */
public final class QueryHandler {

    /** Largest UDP answer: the size recommended to avoid IP fragmentation (DNS Flag Day 2020). */
    public static final int MAX_UDP = 1232;

    private static final Name WILD = Name.fromConstantString("*");

    private static final int MAX_CNAME_CHAIN = 8;

    private final Supplier<Map<Name, ZoneView>> zones;

    public QueryHandler(Supplier<Map<Name, ZoneView>> zones) {
        this.zones = zones;
    }

    /** The zone a name belongs to: the hosted zone with the longest matching name. */
    public static ZoneView zoneFor(Map<Name, ZoneView> zones, Name name) {
        if (zones.isEmpty()) return null;
        for (int strip = 0; strip < name.labels(); strip++) {
            ZoneView z = zones.get(strip == 0 ? name : new Name(name, strip));
            if (z != null) return z;
        }
        return null;
    }

    /**
     * The answer to a query in wire format, or null when nothing should be sent (the packet is a
     * response itself or not DNS at all).
     */
    public byte[] handle(byte[] packet, boolean tcp) {
        Message query;
        try {
            query = new Message(packet);
        } catch (Exception e) {
            return formatError(packet);
        }
        Message resp = answer(query, tcp);
        if (resp == null) return null;
        int max = tcp ? 65535 : udpLimit(query);
        return resp.toWire(max);
    }

    static int udpLimit(Message query) {
        OPTRecord opt = query.getOPT();
        if (opt == null) return 512;
        return Math.max(512, Math.min(MAX_UDP, opt.getPayloadSize()));
    }

    /** A FORMERR answer to a packet that has a header but couldn't be read. */
    private static byte[] formatError(byte[] packet) {
        if (packet.length < Header.LENGTH) return null;
        try {
            Header h = new Header(packet);
            if (h.getFlag(Flags.QR)) return null;
            Message m = new Message(h.getID());
            m.getHeader().setFlag(Flags.QR);
            m.getHeader().setOpcode(h.getOpcode());
            m.getHeader().setRcode(Rcode.FORMERR);
            return m.toWire();
        } catch (Exception e) {
            return null;
        }
    }

    public Message answer(Message query) {
        return answer(query, false);
    }

    Message answer(Message query, boolean tcp) {
        Header qh = query.getHeader();
        if (qh.getFlag(Flags.QR)) return null;

        Message resp = new Message(qh.getID());
        Header h = resp.getHeader();
        h.setFlag(Flags.QR);
        h.setOpcode(qh.getOpcode());
        if (qh.getFlag(Flags.RD)) h.setFlag(Flags.RD);
        if (qh.getFlag(Flags.CD)) h.setFlag(Flags.CD);

        Record question = query.getQuestion();
        if (question != null) resp.addRecord(question, Section.QUESTION);

        OPTRecord opt = query.getOPT();
        boolean dnssec = false;
        if (opt != null) {
            if (opt.getVersion() > 0) {
                // Only EDNS version 0 exists.
                h.setRcode(Rcode.BADVERS & 0xF);
                resp.addRecord(
                        new OPTRecord(MAX_UDP, Rcode.BADVERS >>> 4, 0, 0), Section.ADDITIONAL);
                return resp;
            }
            dnssec = (opt.getFlags() & ExtendedFlags.DO) != 0;
            resp.addRecord(
                    new OPTRecord(MAX_UDP, 0, 0, dnssec ? ExtendedFlags.DO : 0),
                    Section.ADDITIONAL);
        }

        if (qh.getOpcode() != Opcode.QUERY) {
            h.setRcode(Rcode.NOTIMP);
            return resp;
        }
        if (question == null || qh.getCount(Section.QUESTION) != 1) {
            h.setRcode(Rcode.FORMERR);
            return resp;
        }
        int qtype = question.getType();
        if (question.getDClass() != DClass.IN && question.getDClass() != DClass.ANY) {
            h.setRcode(Rcode.REFUSED);
            return resp;
        }
        if (qtype == Type.AXFR
                || qtype == Type.IXFR
                || qtype == Type.MAILB
                || qtype == Type.MAILA) {
            // Zone transfers are answered over TCP by the server, to allowed addresses only.
            h.setRcode(tcp ? Rcode.REFUSED : Rcode.FORMERR);
            return resp;
        }

        Map<Name, ZoneView> all = zones.get();
        ZoneView zone = zoneFor(all, question.getName());
        if (zone == null) {
            // Not ours, and we don't recurse.
            h.setRcode(Rcode.REFUSED);
            return resp;
        }
        new Lookup(resp, all, dnssec).run(zone, question.getName(), qtype);
        return resp;
    }

    /** One query's lookup; follows CNAMEs within the hosted zones. */
    private static final class Lookup {
        final Message resp;
        final Map<Name, ZoneView> zones;
        final boolean dnssec;
        final Set<String> added = new HashSet<>();
        boolean first = true;

        Lookup(Message resp, Map<Name, ZoneView> zones, boolean dnssec) {
            this.resp = resp;
            this.zones = zones;
            this.dnssec = dnssec;
        }

        void run(ZoneView zone, Name qname, int qtype) {
            for (int depth = 0; depth <= MAX_CNAME_CHAIN; depth++) {
                Name next = step(zone, qname, qtype);
                first = false;
                if (next == null) return;
                ZoneView target = zoneFor(zones, next);
                // A CNAME to a name we don't serve: the resolver follows it.
                if (target == null) return;
                zone = target;
                qname = next;
            }
        }

        /** Answers qname in zone; returns the CNAME target to continue with, or null. */
        Name step(ZoneView zone, Name qname, int qtype) {
            Header h = resp.getHeader();

            ZoneView.Node cut = delegationAbove(zone, qname);
            if (cut != null && !(cut.name().equals(qname) && qtype == Type.DS)) {
                referral(zone, cut);
                return null;
            }
            if (first) h.setFlag(Flags.AA);

            ZoneView.Node node = zone.nodes().get(qname);
            if (node != null && !node.occluded()) {
                return fromNode(zone, node, qname, qtype, false);
            }
            if (zone.existing().contains(qname)) {
                // An empty non-terminal: the name exists, but has no records.
                noData(zone, null, qname, null);
                return null;
            }
            Name encloser = closestEncloser(zone, qname);
            ZoneView.Node wild = zone.nodes().get(wildcard(encloser));
            if (wild != null && !wild.occluded()) {
                return fromNode(zone, wild, qname, qtype, true);
            }
            // The name doesn't exist. After a CNAME, the rcode still describes the last name.
            h.setRcode(Rcode.NXDOMAIN);
            add(zone.soaNegative(), Section.AUTHORITY);
            if (dnssec && zone.signed()) {
                add(zone.nsecCovering(qname), Section.AUTHORITY);
                add(zone.nsecCovering(wildcard(encloser)), Section.AUTHORITY);
            }
            return null;
        }

        Name fromNode(ZoneView zone, ZoneView.Node node, Name qname, int qtype, boolean wild) {
            ZoneView.Answer answer;
            if (qtype == Type.ANY) {
                // RFC 8482: answer ANY with one of the name's record sets, not all of them.
                answer =
                        node.answers().entrySet().stream()
                                .filter(e -> e.getKey() != Type.DNSKEY)
                                .map(Map.Entry::getValue)
                                .findFirst()
                                .orElse(null);
            } else {
                answer = node.get(qtype);
            }
            if (answer != null) {
                RRset set = answer.pick();
                add(wild ? rename(set, qname) : set, Section.ANSWER);
                if (wild && dnssec && zone.signed()) {
                    // Proves that no closer name matched.
                    add(zone.nsecCovering(qname), Section.AUTHORITY);
                }
                additional(zone, set);
                return null;
            }
            ZoneView.Answer cname = node.get(Type.CNAME);
            if (cname != null) {
                RRset set = cname.pick();
                add(wild ? rename(set, qname) : set, Section.ANSWER);
                if (wild && dnssec && zone.signed()) {
                    add(zone.nsecCovering(qname), Section.AUTHORITY);
                }
                return ((CNAMERecord) set.first()).getTarget();
            }
            noData(zone, node, qname, wild ? node.name() : null);
            return null;
        }

        /** The name exists (or a wildcard matched) but has nothing of the asked type. */
        void noData(ZoneView zone, ZoneView.Node node, Name qname, Name wildOwner) {
            add(zone.soaNegative(), Section.AUTHORITY);
            if (!dnssec || !zone.signed()) return;
            if (wildOwner != null) {
                add(zone.nsecCovering(qname), Section.AUTHORITY);
                add(zone.nsec().get(wildOwner), Section.AUTHORITY);
            } else if (node != null && zone.nsec().containsKey(node.name())) {
                add(zone.nsec().get(node.name()), Section.AUTHORITY);
            } else {
                add(zone.nsecCovering(qname), Section.AUTHORITY);
            }
        }

        /** Sends the resolver to the nameservers of a delegated subdomain. */
        void referral(ZoneView zone, ZoneView.Node cut) {
            RRset ns = cut.get(Type.NS).pick();
            add(ns, Section.AUTHORITY);
            if (dnssec && zone.signed()) {
                ZoneView.Answer ds = cut.get(Type.DS);
                if (ds != null) add(ds.pick(), Section.AUTHORITY);
                else add(zone.nsec().get(cut.name()), Section.AUTHORITY);
            }
            glue(zone, ns);
        }

        void additional(ZoneView zone, RRset set) {
            if (set.getType() == Type.NS) glue(zone, set);
        }

        /** Addresses of nameservers whose names are in this zone. */
        void glue(ZoneView zone, RRset ns) {
            for (Record r : ns.rrs(false)) {
                Name target = ((NSRecord) r).getTarget();
                if (!target.subdomain(zone.origin())) continue;
                ZoneView.Node n = zone.nodes().get(target);
                if (n == null) continue;
                for (int t : new int[] {Type.A, Type.AAAA}) {
                    ZoneView.Answer a = n.get(t);
                    if (a == null) continue;
                    RRset set = a.pick();
                    // Glue below a delegation is never signed; addresses in the zone are.
                    add(set, Section.ADDITIONAL);
                }
            }
        }

        void add(RRset set, int section) {
            if (set == null) return;
            String key = section + "|" + set.getName() + "|" + set.getType();
            if (!added.add(key)) return;
            for (Record r : set.rrs(false)) resp.addRecord(r, section);
            if (dnssec) for (RRSIGRecord s : set.sigs()) resp.addRecord(s, section);
        }

        static RRset rename(RRset set, Name name) {
            RRset out = new RRset();
            for (Record r : set.rrs(false)) out.addRR(r.withName(name));
            for (RRSIGRecord s : set.sigs()) out.addRR((RRSIGRecord) s.withName(name));
            return out;
        }

        /** The highest delegation point at or above qname (below the apex), if any. */
        static ZoneView.Node delegationAbove(ZoneView zone, Name qname) {
            int apexLabels = zone.origin().labels();
            for (int labels = apexLabels + 1; labels <= qname.labels(); labels++) {
                Name anc =
                        labels == qname.labels() ? qname : new Name(qname, qname.labels() - labels);
                ZoneView.Node n = zone.nodes().get(anc);
                if (n != null && n.delegation()) return n;
            }
            return null;
        }

        static Name closestEncloser(ZoneView zone, Name qname) {
            for (int strip = 1; qname.labels() - strip >= zone.origin().labels(); strip++) {
                Name anc = new Name(qname, strip);
                if (zone.existing().contains(anc)) return anc;
            }
            return zone.origin();
        }

        static Name wildcard(Name encloser) {
            try {
                return Name.concatenate(WILD, encloser);
            } catch (NameTooLongException e) {
                return encloser;
            }
        }
    }
}
