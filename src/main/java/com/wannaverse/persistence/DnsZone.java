package com.wannaverse.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A domain whose DNS Docker Manager answers (a hosted zone), such as example.com. Its records are
 * {@link DnsRecord}s; ingress routes under it add their own records unless {@link #autoRecords} is
 * off.
 */
@Entity
@Table(name = "dns_zones_t", uniqueConstraints = @UniqueConstraint(columnNames = {"name"}))
@Getter
@Setter
@NoArgsConstructor
public class DnsZone {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Lower case, without the trailing dot: example.com. */
    @Column(nullable = false)
    private String name;

    private String comment;

    /** TTL of records that don't set one, in seconds. */
    private int defaultTtl = 300;

    /** How long resolvers may remember that a name doesn't exist (SOA minimum), in seconds. */
    private int negativeTtl = 300;

    /**
     * The zone's nameservers as a JSON list of hostnames. Empty means the ones in the DNS settings.
     */
    @Column(columnDefinition = "TEXT")
    private String nameserversJson;

    /** Ingress routes under this domain get A/AAAA records pointing at their host. */
    private boolean autoRecords = true;

    private boolean dnssecEnabled;

    /** The zone's signing key (ECDSA P-256, PKCS#8, Base64), encrypted. */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String dnssecPrivateKey;

    /** The public half (X.509, Base64). */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String dnssecPublicKey;

    private Long dnssecKeyCreatedAt;

    /** SOA serial; raised whenever what the zone answers changes. */
    private long serial;

    /** Fingerprint of the answers the serial belongs to. */
    @JsonIgnore private String contentHash;

    /** Whether the domain's registrar points at these nameservers: OK, PARTIAL, NO or UNKNOWN. */
    private String delegationStatus;

    @Column(columnDefinition = "TEXT")
    private String delegationDetail;

    private Long delegationCheckedAt;

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
