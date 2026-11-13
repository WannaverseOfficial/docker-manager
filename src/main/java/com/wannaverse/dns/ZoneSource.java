package com.wannaverse.dns;

import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

import java.security.PrivateKey;
import java.util.List;

/**
 * Everything a zone answers, before it is turned into a {@link ZoneView}: plain data, so building
 * and answering can be tested without a database.
 *
 * @param nameservers the zone's NS records, in order (the first is the SOA's primary)
 * @param hostmaster the SOA contact as a name (hostmaster.example.com.)
 * @param key the DNSSEC signing key, or null for an unsigned zone
 */
public record ZoneSource(
        String id,
        Name origin,
        int defaultTtl,
        int negativeTtl,
        List<Name> nameservers,
        Name hostmaster,
        List<RecordSet> sets,
        SigningKey key) {

    public enum Kind {
        /** Added by a person (or the configuration file). */
        MANUAL,
        /** For an ingress route: its hostname points at its host. */
        AUTO,
        /** Address of a nameserver whose name is inside the zone (glue). */
        NAMESERVER,
        /** A Let's Encrypt DNS-01 challenge (TXT), while a certificate is requested. */
        CHALLENGE
    }

    public enum Policy {
        SIMPLE,
        WEIGHTED,
        FAILOVER
    }

    /**
     * One record set.
     *
     * @param records the values, already parsed, all with {@code name} and {@code type}
     * @param primary FAILOVER: true for the primary set
     * @param healthy null when no health check is attached, else its latest result (unknown counts
     *     as healthy)
     * @param source what made it, for lists: a record id, an ingress route id...
     */
    public record RecordSet(
            Name name,
            int type,
            long ttl,
            List<Record> records,
            Kind kind,
            Policy policy,
            String setId,
            int weight,
            boolean primary,
            Boolean healthy,
            String source) {

        public static RecordSet simple(
                Name name, int type, long ttl, List<Record> records, Kind kind, String source) {
            return new RecordSet(
                    name, type, ttl, records, kind, Policy.SIMPLE, null, 0, false, null, source);
        }

        boolean up() {
            return healthy == null || healthy;
        }
    }

    /** A zone's DNSSEC key (a combined signing key: it signs both the DNSKEY set and the data). */
    public record SigningKey(DNSKEYRecord dnskey, PrivateKey privateKey) {}
}
