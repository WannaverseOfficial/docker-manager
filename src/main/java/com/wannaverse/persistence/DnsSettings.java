package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The DNS server's settings (one row). */
@Entity
@Table(name = "dns_settings_t")
@Getter
@Setter
@NoArgsConstructor
public class DnsSettings {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Answer DNS queries. */
    private boolean enabled;

    /**
     * The nameservers registrars are given, as a JSON list of hostnames (ns1.example.com). Names
     * inside a hosted zone get A/AAAA records with {@link #publicIpv4} / {@link #publicIpv6}.
     */
    @Column(columnDefinition = "TEXT")
    private String nameserversJson;

    /** This server's public addresses. Empty means the public address of its own Docker host. */
    private String publicIpv4;

    private String publicIpv6;

    /** The zones' contact address (SOA), such as hostmaster@example.com. */
    private String hostmasterEmail;

    /**
     * Secondary nameservers allowed to copy zones (AXFR), as a JSON list of IP addresses or
     * networks (CIDR). Single addresses are also told when a zone changes (NOTIFY).
     */
    @Column(columnDefinition = "TEXT")
    private String transferAllowedJson;

    /** Most answers one address gets per second over UDP; 0 turns the limit off. */
    private int rateLimit = 100;

    private long updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = System.currentTimeMillis();
    }
}
