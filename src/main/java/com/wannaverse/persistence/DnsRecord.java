package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A record set of a hosted zone: one name and type with its values, as in Route 53. Several sets
 * may share a name and type when they use the weighted or failover routing policy; each then has
 * its own {@link #setIdentifier}.
 */
@Entity
@Table(
        name = "dns_records_t",
        indexes = {@Index(name = "idx_dns_record_zone", columnList = "zoneId")})
@Getter
@Setter
@NoArgsConstructor
public class DnsRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String zoneId;

    /** Relative to the zone, lower case: "@" for the zone itself, "www", "*.dev". */
    @Column(nullable = false)
    private String name;

    /** A, AAAA, CNAME, MX, TXT... */
    @Column(nullable = false)
    private String type;

    /** Seconds; null means the zone's default. */
    private Integer ttl;

    /** The values as a JSON list of strings in zone-file form, such as "10 mail.example.com". */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String valuesJson;

    /** SIMPLE (null), WEIGHTED or FAILOVER. */
    private String routingPolicy;

    /** Tells apart record sets with the same name and type (weighted and failover). */
    private String setIdentifier;

    /** WEIGHTED: share of answers, 0-255, relative to the other sets. */
    private Integer weight;

    /** FAILOVER: PRIMARY or SECONDARY. */
    private String failoverRole;

    /** Answer with this set only while the health check passes. */
    private String healthCheckId;

    private String comment;

    private long createdAt;

    private long updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = System.currentTimeMillis();
    }
}
