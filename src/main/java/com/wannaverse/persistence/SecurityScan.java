package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One security scan: an image checked by Trivy or Docker Scout, or a host by Docker Bench. */
@Entity
@Table(
        name = "security_scans_t",
        indexes = {@Index(name = "idx_scan_host_time", columnList = "dockerHostId, startedAt")})
@Getter
@Setter
@NoArgsConstructor
public class SecurityScan {
    public enum Kind {
        TRIVY,
        SCOUT,
        BENCH
    }

    public enum Status {
        RUNNING,
        DONE,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String dockerHostId;

    // Stored as text: new kinds must not run into a database CHECK constraint.
    @Column(nullable = false, length = 20)
    private String kind;

    /** The image scanned, or the host's name for Docker Bench. */
    @Column(length = 512)
    private String target;

    @Column(length = 20)
    private String status;

    private Long startedAt;

    private Long finishedAt;

    private String startedBy;

    /** Counts by severity, fixable count, score... as JSON. */
    @Column(columnDefinition = "TEXT")
    private String summaryJson;

    /** The findings, as JSON. */
    @Column(columnDefinition = "TEXT")
    private String findingsJson;

    @Column(length = 2000)
    private String error;
}
