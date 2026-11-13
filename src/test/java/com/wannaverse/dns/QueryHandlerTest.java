package com.wannaverse.dns;

import static org.junit.jupiter.api.Assertions.*;

import com.wannaverse.dns.ZoneSource.Kind;
import com.wannaverse.dns.ZoneSource.Policy;
import com.wannaverse.dns.ZoneSource.RecordSet;

import org.junit.jupiter.api.Test;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class QueryHandlerTest {

    static final Name ZONE = Name.fromConstantString("example.com.");

    final ZoneBuilder builder = new ZoneBuilder();

    static Name n(String s) {
        return Name.fromConstantString(s);
    }

    static RecordSet manual(String name, String type, String... values) {
        Name owner = RecordValues.absolute(name, "example.com");
        return RecordSet.simple(
                owner,
                Type.value(type),
                300,
                RecordValues.parse(owner, type, 300, List.of(values)),
                Kind.MANUAL,
                "t");
    }

    static RecordSet auto(String name, String ip) {
        Name owner = RecordValues.absolute(name, "example.com");
        return RecordSet.simple(
                owner,
                Type.A,
                60,
                RecordValues.parse(owner, "A", 60, List.of(ip)),
                Kind.AUTO,
                "route");
    }

    ZoneView build(List<RecordSet> sets, boolean signed) {
        ZoneSource.SigningKey key = null;
        if (signed) {
            KeyPair kp = DnssecKeys.generate();
            key =
                    new ZoneSource.SigningKey(
                            DnssecKeys.dnskey(ZONE, kp.getPublic()), kp.getPrivate());
        }
        ZoneSource src =
                new ZoneSource(
                        "z1",
                        ZONE,
                        300,
                        120,
                        List.of(n("ns1.example.com."), n("ns2.example.net.")),
                        n("hostmaster.example.com."),
                        sets,
                        key);
        return builder.finish(builder.prepare(src), 2026111201L);
    }

    static Message ask(ZoneView zone, String name, int type, boolean dnssec) {
        Map<Name, ZoneView> zones = new HashMap<>();
        zones.put(zone.origin(), zone);
        QueryHandler h = new QueryHandler(() -> zones);
        Message q = Message.newQuery(Record.newRecord(n(name), type, DClass.IN));
        if (dnssec) q.addRecord(new OPTRecord(1232, 0, 0, ExtendedFlags.DO), Section.ADDITIONAL);
        return h.answer(q);
    }

    static List<Record> section(Message m, int section) {
        return m.getSection(section);
    }

    static void verifyAll(Message m, ZoneView zone) throws Exception {
        DNSKEYRecord key = (DNSKEYRecord) zone.nodes().get(ZONE).get(Type.DNSKEY).pick().first();
        int checked = 0;
        for (int section : new int[] {Section.ANSWER, Section.AUTHORITY}) {
            for (RRset set : m.getSectionRRsets(section)) {
                if (set.getType() == Type.NS && !set.getName().equals(ZONE)) continue; // referral
                assertFalse(
                        set.sigs().isEmpty(),
                        "unsigned " + set.getName() + " " + Type.string(set.getType()));
                for (RRSIGRecord sig : set.sigs()) {
                    DNSSEC.verify(set, sig, key);
                    checked++;
                }
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    void answersARecordAuthoritatively() {
        ZoneView z = build(List.of(manual("www", "A", "203.0.113.10")), false);
        Message r = ask(z, "www.example.com.", Type.A, false);
        assertEquals(Rcode.NOERROR, r.getRcode());
        assertTrue(r.getHeader().getFlag(Flags.AA));
        assertFalse(r.getHeader().getFlag(Flags.RA));
        assertEquals(
                "203.0.113.10",
                ((ARecord) section(r, Section.ANSWER).getFirst()).getAddress().getHostAddress());
    }

    @Test
    void refusesOtherDomains() {
        ZoneView z = build(List.of(), false);
        Message r = ask(z, "www.google.com.", Type.A, false);
        assertEquals(Rcode.REFUSED, r.getRcode());
        assertTrue(section(r, Section.ANSWER).isEmpty());
    }

    @Test
    void nxdomainAndNodataCarryTheSoa() {
        ZoneView z = build(List.of(manual("www", "A", "203.0.113.10")), false);
        Message nx = ask(z, "nope.example.com.", Type.A, false);
        assertEquals(Rcode.NXDOMAIN, nx.getRcode());
        SOARecord soa = (SOARecord) section(nx, Section.AUTHORITY).getFirst();
        assertEquals(120, soa.getTTL());
        assertEquals(2026111201L, soa.getSerial());

        Message nodata = ask(z, "www.example.com.", Type.AAAA, false);
        assertEquals(Rcode.NOERROR, nodata.getRcode());
        assertTrue(section(nodata, Section.ANSWER).isEmpty());
        assertEquals(Type.SOA, section(nodata, Section.AUTHORITY).getFirst().getType());
    }

    @Test
    void emptyNonTerminalIsNodataNotNxdomain() {
        ZoneView z = build(List.of(manual("a.b", "A", "203.0.113.10")), false);
        assertEquals(Rcode.NOERROR, ask(z, "b.example.com.", Type.A, false).getRcode());
    }

    @Test
    void followsCnamesInsideTheZone() {
        ZoneView z =
                build(
                        List.of(
                                manual("www", "CNAME", "app.example.com"),
                                manual("app", "A", "203.0.113.20")),
                        false);
        Message r = ask(z, "www.example.com.", Type.A, false);
        List<Record> answer = section(r, Section.ANSWER);
        assertEquals(2, answer.size());
        assertEquals(Type.CNAME, answer.get(0).getType());
        assertEquals(Type.A, answer.get(1).getType());
        assertEquals(n("app.example.com."), ((CNAMERecord) answer.get(0)).getTarget());
    }

    @Test
    void cnameToForeignNameStops() {
        ZoneView z = build(List.of(manual("shop", "CNAME", "shops.myshopify.com")), false);
        Message r = ask(z, "shop.example.com.", Type.A, false);
        assertEquals(Rcode.NOERROR, r.getRcode());
        assertEquals(1, section(r, Section.ANSWER).size());
    }

    @Test
    void wildcardsMatchMissingNamesOnly() {
        ZoneView z =
                build(
                        List.of(
                                manual("*", "A", "203.0.113.30"),
                                manual("www", "A", "203.0.113.10")),
                        false);
        Record a = section(ask(z, "foo.example.com.", Type.A, false), Section.ANSWER).getFirst();
        assertEquals(n("foo.example.com."), a.getName());
        assertEquals("203.0.113.30", ((ARecord) a).getAddress().getHostAddress());
        Record w = section(ask(z, "www.example.com.", Type.A, false), Section.ANSWER).getFirst();
        assertEquals("203.0.113.10", ((ARecord) w).getAddress().getHostAddress());
        // www exists, so the wildcard doesn't apply below... it does apply to x.www? No: www is the
        // closest encloser and *.www doesn't exist.
        assertEquals(Rcode.NXDOMAIN, ask(z, "x.www.example.com.", Type.A, false).getRcode());
    }

    @Test
    void delegationsGiveAReferralWithGlue() {
        ZoneView z =
                build(
                        List.of(
                                manual("dev", "NS", "ns.dev.example.com"),
                                manual("ns.dev", "A", "198.51.100.5")),
                        false);
        Message r = ask(z, "app.dev.example.com.", Type.A, false);
        assertEquals(Rcode.NOERROR, r.getRcode());
        assertFalse(r.getHeader().getFlag(Flags.AA));
        assertTrue(section(r, Section.ANSWER).isEmpty());
        assertEquals(Type.NS, section(r, Section.AUTHORITY).getFirst().getType());
        assertTrue(section(r, Section.ADDITIONAL).stream().anyMatch(x -> x.getType() == Type.A));
    }

    @Test
    void handMadeRecordsWinOverAutomaticOnes() {
        ZoneView z =
                build(
                        List.of(
                                manual("app", "CNAME", "elsewhere.example.net"),
                                auto("app", "203.0.113.99"),
                                auto("api", "203.0.113.99")),
                        false);
        Message r = ask(z, "app.example.com.", Type.A, false);
        assertEquals(1, section(r, Section.ANSWER).size());
        assertEquals(Type.CNAME, section(r, Section.ANSWER).getFirst().getType());
        assertEquals(
                Type.A,
                section(ask(z, "api.example.com.", Type.A, false), Section.ANSWER)
                        .getFirst()
                        .getType());
    }

    @Test
    void automaticRecordsOnSeveralHostsAreRoundRobin() {
        ZoneView z = build(List.of(auto("app", "203.0.113.1"), auto("app", "203.0.113.2")), false);
        assertEquals(2, section(ask(z, "app.example.com.", Type.A, false), Section.ANSWER).size());
    }

    static RecordSet routed(
            String ip, Policy p, String id, int weight, boolean primary, Boolean healthy) {
        Name owner = n("app.example.com.");
        return new RecordSet(
                owner,
                Type.A,
                60,
                RecordValues.parse(owner, "A", 60, List.of(ip)),
                Kind.MANUAL,
                p,
                id,
                weight,
                primary,
                healthy,
                "r");
    }

    @Test
    void failoverAnswersWithTheSecondaryWhilePrimaryIsDown() {
        String ip =
                ((ARecord)
                                section(
                                                ask(
                                                        build(
                                                                List.of(
                                                                        routed(
                                                                                "203.0.113.1",
                                                                                Policy.FAILOVER,
                                                                                "a",
                                                                                0,
                                                                                true,
                                                                                false),
                                                                        routed(
                                                                                "203.0.113.2",
                                                                                Policy.FAILOVER,
                                                                                "b",
                                                                                0,
                                                                                false,
                                                                                null)),
                                                                false),
                                                        "app.example.com.",
                                                        Type.A,
                                                        false),
                                                Section.ANSWER)
                                        .getFirst())
                        .getAddress()
                        .getHostAddress();
        assertEquals("203.0.113.2", ip);
        String up =
                ((ARecord)
                                section(
                                                ask(
                                                        build(
                                                                List.of(
                                                                        routed(
                                                                                "203.0.113.1",
                                                                                Policy.FAILOVER,
                                                                                "a",
                                                                                0,
                                                                                true,
                                                                                true),
                                                                        routed(
                                                                                "203.0.113.2",
                                                                                Policy.FAILOVER,
                                                                                "b",
                                                                                0,
                                                                                false,
                                                                                true)),
                                                                false),
                                                        "app.example.com.",
                                                        Type.A,
                                                        false),
                                                Section.ANSWER)
                                        .getFirst())
                        .getAddress()
                        .getHostAddress();
        assertEquals("203.0.113.1", up);
    }

    @Test
    void weightedAnswersFollowTheWeightsAndSkipUnhealthy() {
        ZoneView z =
                build(
                        List.of(
                                routed("203.0.113.1", Policy.WEIGHTED, "a", 3, false, null),
                                routed("203.0.113.2", Policy.WEIGHTED, "b", 1, false, null),
                                routed("203.0.113.3", Policy.WEIGHTED, "c", 50, false, false)),
                        false);
        int first = 0;
        for (int i = 0; i < 4000; i++) {
            Message r = ask(z, "app.example.com.", Type.A, false);
            assertEquals(1, section(r, Section.ANSWER).size());
            String ip =
                    ((ARecord) section(r, Section.ANSWER).getFirst()).getAddress().getHostAddress();
            assertNotEquals("203.0.113.3", ip);
            if (ip.equals("203.0.113.1")) first++;
        }
        assertTrue(first > 2700 && first < 3300, "about 75% expected, got " + first);
    }

    @Test
    void largeAnswersAreTruncatedOverUdp() throws Exception {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 40; i++)
            values.add("value number " + i + " with some padding to make it long");
        ZoneView z = build(List.of(manual("big", "TXT", values.toArray(String[]::new))), false);
        Map<Name, ZoneView> zones = Map.of(ZONE, z);
        QueryHandler h = new QueryHandler(() -> zones);
        byte[] q =
                Message.newQuery(Record.newRecord(n("big.example.com."), Type.TXT, DClass.IN))
                        .toWire();
        byte[] udp = h.handle(q, false);
        assertTrue(udp.length <= 512);
        assertTrue(new Message(udp).getHeader().getFlag(Flags.TC));
        byte[] tcp = h.handle(q, true);
        assertFalse(new Message(tcp).getHeader().getFlag(Flags.TC));
        assertEquals(40, new Message(tcp).getSection(Section.ANSWER).size());
    }

    @Test
    void garbageGetsFormerrOrNothing() {
        QueryHandler h = new QueryHandler(Map::of);
        assertNull(h.handle(new byte[] {1, 2, 3}, false));
        byte[] bad = new byte[] {0, 7, 1, 0, 0, 5, 0, 0, 0, 0, 0, 0, 9};
        assertNotNull(h.handle(bad, false));
    }

    @Test
    void txtValuesKeepSemicolonsAndLongTextIsSplit() {
        Name owner = n("k._domainkey.example.com.");
        TXTRecord r =
                (TXTRecord)
                        RecordValues.parse(
                                        owner,
                                        "TXT",
                                        300,
                                        List.of("v=DKIM1; k=rsa; p=" + "A".repeat(400)))
                                .getFirst();
        assertEquals(2, r.getStrings().size());
        assertEquals("v=DKIM1; k=rsa; p=" + "A".repeat(400), String.join("", r.getStrings()));
    }

    @Test
    void valuesAreCheckedWithHelpfulMessages() {
        Name owner = n("www.example.com.");
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> RecordValues.parse(owner, "A", 300, List.of("not-an-ip")));
        assertTrue(e.getMessage().contains("IPv4"));
        assertEquals("www", RecordValues.relativeName("WWW.example.com.", "example.com"));
        assertEquals("@", RecordValues.relativeName("", "example.com"));
        assertEquals("*.dev", RecordValues.relativeName("*.dev", "example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RecordValues.relativeName("a.*", "example.com"));
        // Names in values are complete, as in Route 53.
        MXRecord mx =
                (MXRecord)
                        RecordValues.parse(owner, "MX", 300, List.of("10 mail.example.com"))
                                .getFirst();
        assertEquals(n("mail.example.com."), mx.getTarget());
    }

    // --- DNSSEC ---

    @Test
    void signedAnswersVerify() throws Exception {
        ZoneView z =
                build(
                        List.of(
                                manual("www", "A", "203.0.113.10"),
                                manual("@", "MX", "10 mail.example.com")),
                        true);
        Message r = ask(z, "www.example.com.", Type.A, true);
        verifyAll(r, z);
        Message keys = ask(z, "example.com.", Type.DNSKEY, true);
        verifyAll(keys, z);
        // Without the DO bit, no signatures.
        assertTrue(
                ask(z, "www.example.com.", Type.A, false).getSection(Section.ANSWER).stream()
                        .noneMatch(x -> x instanceof RRSIGRecord));
    }

    @Test
    void nxdomainIsProvenWithNsec() throws Exception {
        ZoneView z =
                build(
                        List.of(
                                manual("www", "A", "203.0.113.10"),
                                manual("mail", "A", "203.0.113.11")),
                        true);
        Name q = n("nope.example.com.");
        Message r = ask(z, q.toString(), Type.A, true);
        assertEquals(Rcode.NXDOMAIN, r.getRcode());
        verifyAll(r, z);
        boolean covered = false, wildcardCovered = false;
        Name wild = n("*.example.com.");
        for (Record rec : r.getSection(Section.AUTHORITY)) {
            if (rec instanceof NSECRecord nsec) {
                if (covers(nsec, q)) covered = true;
                if (covers(nsec, wild)) wildcardCovered = true;
            }
        }
        assertTrue(covered && wildcardCovered);
    }

    static boolean covers(NSECRecord nsec, Name name) {
        Name owner = nsec.getName(), next = nsec.getNext();
        boolean last = next.compareTo(owner) <= 0;
        return owner.compareTo(name) < 0 && (last || name.compareTo(next) < 0);
    }

    @Test
    void nodataIsProvenWithTheNamesOwnNsec() throws Exception {
        ZoneView z = build(List.of(manual("www", "A", "203.0.113.10")), true);
        Message r = ask(z, "www.example.com.", Type.AAAA, true);
        verifyAll(r, z);
        NSECRecord nsec =
                (NSECRecord)
                        r.getSection(Section.AUTHORITY).stream()
                                .filter(x -> x instanceof NSECRecord)
                                .findFirst()
                                .orElseThrow();
        assertEquals(n("www.example.com."), nsec.getName());
        assertTrue(nsec.hasType(Type.A) && !nsec.hasType(Type.AAAA));
    }

    @Test
    void wildcardAnswersVerifyAndProveNoCloserMatch() throws Exception {
        ZoneView z =
                build(
                        List.of(
                                manual("*", "A", "203.0.113.30"),
                                manual("www", "A", "203.0.113.10")),
                        true);
        Message r = ask(z, "foo.example.com.", Type.A, true);
        verifyAll(r, z);
        assertTrue(
                r.getSection(Section.AUTHORITY).stream()
                        .anyMatch(
                                x ->
                                        x instanceof NSECRecord nsec
                                                && covers(nsec, n("foo.example.com."))));
    }

    @Test
    void nsecChainIsClosedAndCanonical() {
        ZoneView z =
                build(
                        List.of(
                                manual("www", "A", "203.0.113.10"),
                                manual("a", "A", "203.0.113.1"),
                                manual("z.b", "A", "203.0.113.2"),
                                manual("dev", "NS", "ns.other.net")),
                        true);
        List<Name> owners = new ArrayList<>(z.nsec().keySet());
        assertEquals(ZONE, owners.getFirst());
        for (int i = 0; i < owners.size(); i++) {
            NSECRecord rec = (NSECRecord) z.nsec().get(owners.get(i)).first();
            assertEquals(owners.get((i + 1) % owners.size()), rec.getNext());
        }
        NSECRecord dev = (NSECRecord) z.nsec().get(n("dev.example.com.")).first();
        assertTrue(dev.hasType(Type.NS) && !dev.hasType(Type.A));
    }

    @Test
    void contentHashOnlyChangesWithContent() {
        String a = build(List.of(manual("www", "A", "203.0.113.10")), false).contentHash();
        String b = build(List.of(manual("www", "A", "203.0.113.10")), false).contentHash();
        String c = build(List.of(manual("www", "A", "203.0.113.11")), false).contentHash();
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}
