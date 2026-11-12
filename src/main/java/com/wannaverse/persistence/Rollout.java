package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An app released with blue/green or canary deployments.
 *
 * <p>A rollout owns two containers, {@code <name>-blue} and {@code <name>-green}, and an ingress
 * route. One container serves visitors (the active one); a new version starts in the other, is
 * tested there, and then receives traffic: all at once (blue/green) or in growing steps (canary).
 * Traffic only ever moves through the ingress proxy, so switching back is a config reload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rollouts_t")
public class Rollout {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "docker_host_id", nullable = false)
    private DockerHost dockerHost;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Strategy strategy = Strategy.BLUE_GREEN;

    /** Base container name; the slots are {@code <name>-blue} and {@code <name>-green}. */
    @Column(nullable = false)
    private String containerName;

    /** Container settings (env, volumes, networks...) as a JSON ContainerSpec, without image. */
    @Column(columnDefinition = "TEXT")
    private String containerSpecJson;

    /** The port the app listens on inside the container. */
    @Column(nullable = false)
    private int containerPort;

    // --- address ----------------------------------------------------------------------------

    /** The ingress route that sends visitors to the active version. */
    private String routeId;

    private String hostname;

    private String pathPrefix = "/";

    /** NONE or LETS_ENCRYPT, used when the rollout creates its route. */
    private String tlsMode;

    /** An optional second address that always shows the version being released. */
    private String previewHostname;

    private String previewRouteId;

    // --- checks -----------------------------------------------------------------------------

    /** Path probed to decide that a new version is up and healthy, for example /health. */
    private String healthPath = "/";

    /** How long a new version may take to start answering, in seconds. */
    private int startTimeoutSeconds = 180;

    /** Test steps run against the new version before it gets traffic: a JSON list. */
    @Column(columnDefinition = "TEXT")
    private String testsJson;

    // --- canary ------------------------------------------------------------------------------

    /** Traffic percentages for the new version, in order, e.g. "10,25,50". */
    private String canarySteps = "10,25,50";

    /** How long each canary step lasts, in seconds. */
    private int stepSeconds = 120;

    /** Stickiness: the same visitor keeps seeing the same version. */
    private boolean stickySessions = true;

    // --- analysis ----------------------------------------------------------------------------

    /** Roll back automatically when the checks fail while the new version has traffic. */
    private boolean autoRollback = true;

    /** Highest share of 5xx answers (percent) the new version may give. */
    private double maxErrorRate = 5;

    /** Highest average response time in milliseconds; 0 for no limit. */
    private int maxAvgResponseMs;

    /** Requests needed before the error rate counts. */
    private int minRequests = 20;

    /**
     * Blue/green: how long to watch the new version after the switch, in seconds, with the old one
     * still running for an instant switch back.
     */
    private int bakeSeconds = 120;

    /** Keep the previous version running after a release (instant rollback, uses memory). */
    private boolean keepPreviousRunning;

    // --- state -------------------------------------------------------------------------------

    /**
     * The container serving visitors now (a slot, or the app's container before the first release).
     */
    private String activeContainer;

    private String activeImage;

    /** The container that served before the last release, for rolling back. */
    private String previousContainer;

    private String previousImage;

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

    private String createdBy;

    public enum Strategy {
        BLUE_GREEN,
        CANARY
    }

    public String blueName() {
        return containerName + "-blue";
    }

    public String greenName() {
        return containerName + "-green";
    }

    /** The slot a new version goes into: the one that isn't serving. */
    public String idleSlot() {
        return blueName().equals(activeContainer) ? greenName() : blueName();
    }

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
