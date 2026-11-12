package com.wannaverse.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The observability stack Docker Manager runs on a host (at most one per host): Grafana,
 * Prometheus, and optionally Mimir, Loki and Tempo, already connected to each other, plus the
 * collectors that feed them (Grafana Alloy, cAdvisor and node-exporter).
 */
@Entity
@Table(
        name = "observability_stacks_t",
        uniqueConstraints = @UniqueConstraint(columnNames = {"dockerHostId"}))
@Getter
@Setter
@NoArgsConstructor
public class ObservabilityStack {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String dockerHostId;

    private long createdAt;

    // --- components (Grafana and Prometheus always run) ---

    /** Long-term metrics: Prometheus writes everything it scrapes into Mimir. */
    private boolean mimirEnabled;

    private boolean lokiEnabled;

    private boolean tempoEnabled;

    // --- collection ---

    /** Every container's logs go to Loki (Grafana Alloy reads them from Docker). */
    private boolean collectLogs;

    /** cAdvisor (per-container) and node-exporter (the host), scraped by Prometheus. */
    private boolean collectMetrics;

    /** Grafana Alloy accepts OpenTelemetry from apps on the stack's OTLP network. */
    private boolean otlpEnabled;

    // --- retention ---

    private int metricsRetentionDays;
    private int logsRetentionDays;
    private int tracesRetentionDays;

    // --- reaching Grafana ---

    /** PUBLIC (HTTPS on a hostname through ingress) or LOCAL (a port on 127.0.0.1). */
    @Column(nullable = false)
    private String access;

    private String hostname;

    private Integer localPort;

    private String ingressRouteId;

    /**
     * The Docker Manager page that signs administrators in to Grafana, for example
     * https://docker.example.com/observability/grafana/?host=ID. Null turns that sign-in off.
     */
    @Column(length = 500)
    private String signInUrl;

    /** Grafana's own administrator, for when Docker Manager sign-in is not available. */
    @Column(nullable = false)
    private String grafanaAdminUser;

    @JsonIgnore private String grafanaAdminPassword;

    /** Signs the tokens Grafana accepts from Docker Manager (RSA, PKCS#8, encrypted). */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String signingKey;

    /** The matching public key (PEM), which Grafana checks tokens with. */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String verifyingKey;

    // --- progress ---

    /** What the stack is doing right now (starting, applying changes), shown in the UI. */
    @Column(length = 300)
    private String activity;

    @Column(columnDefinition = "TEXT")
    private String lastResult;

    private boolean lastResultFailed;
}
