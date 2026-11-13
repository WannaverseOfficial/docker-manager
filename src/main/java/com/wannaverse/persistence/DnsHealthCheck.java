package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Checks that an address answers (HTTP, HTTPS or a TCP connection). Record sets that use it are
 * left out of answers while it fails, as with Route 53 health checks.
 */
@Entity
@Table(name = "dns_health_checks_t", uniqueConstraints = @UniqueConstraint(columnNames = {"name"}))
@Getter
@Setter
@NoArgsConstructor
public class DnsHealthCheck {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    /** HTTP, HTTPS or TCP. */
    @Column(nullable = false)
    private String type = "HTTP";

    /** IP address or hostname to connect to. */
    @Column(nullable = false)
    private String address;

    private int port = 80;

    /** HTTP(S): the path requested. */
    private String path = "/";

    /** HTTP(S): the Host header and TLS server name; empty means the address. */
    private String hostHeader;

    private int intervalSeconds = 30;

    private int timeoutSeconds = 5;

    /** Failures (or successes) in a row before the status changes. */
    private int failureThreshold = 3;

    /** Email administrators when the status changes. */
    private boolean notify = true;

    private boolean enabled = true;

    /** HEALTHY, UNHEALTHY or UNKNOWN (not checked yet). */
    private String status = "UNKNOWN";

    @Column(columnDefinition = "TEXT")
    private String lastResult;

    private Long lastCheckedAt;

    private Long lastChangedAt;

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
