package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ingress_routes_t",
        indexes = {
            @Index(name = "idx_route_config", columnList = "ingress_config_id"),
            @Index(name = "idx_route_hostname", columnList = "hostname"),
            @Index(name = "idx_route_container", columnList = "target_container_id")
        })
public class IngressRoute {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "ingress_config_id", nullable = false)
    private String ingressConfigId;

    @Column(nullable = false)
    private String hostname;

    private String pathPrefix = "/";

    @Column(name = "target_container_id", nullable = false)
    private String targetContainerId;

    @Column(nullable = false)
    private String targetContainerName;

    @Column(nullable = false)
    private int targetPort;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Protocol protocol = Protocol.HTTP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TlsMode tlsMode = TlsMode.NONE;

    private String certificateId;

    private boolean authEnabled = false;

    private boolean forceHttpsRedirect = false;

    @Enumerated(EnumType.STRING)
    private AuthType authType;

    @Column(columnDefinition = "TEXT")
    private String authConfig;

    private boolean enabled = true;

    @Column(columnDefinition = "TEXT")
    private String generatedNginxBlock;

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

    private String createdByUserId;

    private String createdByUsername;

    /**
     * No limit on request size and no buffering, for uploads of any size (a hosted Docker registry
     * receives image layers of several GB). Null means the default limit.
     */
    private Boolean unlimitedUploads;

    /**
     * What the route sends visitors to. Null (routes made before this existed) means a container.
     * For the other kinds the container columns hold "" (they are NOT NULL in older databases).
     */
    @Enumerated(EnumType.STRING)
    private TargetType targetType;

    /** ADDRESS routes: an IP address or DNS name, proxied to on {@link #targetPort}. */
    private String targetHost;

    /** REDIRECT routes: where visitors are sent, for example https://example.org. */
    @Column(columnDefinition = "TEXT")
    private String redirectUrl;

    /** REDIRECT routes: 301 (browsers remember it) instead of 302. */
    private Boolean redirectPermanent;

    /** REDIRECT routes: append the visitor's path and query to {@link #redirectUrl}. */
    private Boolean redirectKeepPath;

    /** Set when a rollout manages this route: it decides which container gets the traffic. */
    private String rolloutId;

    /**
     * A second container that gets {@link #canaryWeight} percent of visitors (a canary release).
     * Null or empty when all traffic goes to the target container.
     */
    private String canaryContainerName;

    /** The canary's share of visitors, 0-100. */
    private Integer canaryWeight;

    /** Keep visitors on the version they first got (by address and browser). Null means yes. */
    private Boolean canarySticky;

    /**
     * Log each request's container, status and time to a file the rollout reads, to measure the new
     * version's error rate. Only on while a rollout watches it, so the file stays small.
     */
    private Boolean trafficLog;

    /** True when part of the traffic goes to a canary container. */
    public boolean splitsTraffic() {
        return canaryContainerName != null
                && !canaryContainerName.isBlank()
                && canaryWeight != null
                && canaryWeight > 0;
    }

    public TargetType kind() {
        return targetType == null ? TargetType.CONTAINER : targetType;
    }

    /** Where the route points, for lists and the activity log. */
    public String describeTarget() {
        return switch (kind()) {
            case CONTAINER -> targetContainerName + ":" + targetPort;
            case ADDRESS -> targetHost + ":" + targetPort;
            case REDIRECT -> redirectUrl;
        };
    }

    public enum TargetType {
        CONTAINER,
        ADDRESS,
        REDIRECT
    }

    public enum Protocol {
        HTTP,
        HTTPS
    }

    public enum TlsMode {
        NONE,
        LETS_ENCRYPT,
        CUSTOM_CERT,
        BRING_YOUR_OWN
    }

    public enum AuthType {
        BASIC,
        FORWARD_AUTH,
        /**
         * Grafana of an observability stack: visitors sign in with their Docker Manager account.
         * {@link #authConfig} holds the Docker Manager page that hands out sign-in tokens.
         */
        GRAFANA_SSO
    }

    /** True for the Grafana route of an observability stack (Docker Manager sign-in). */
    public boolean grafanaSso() {
        return authType == AuthType.GRAFANA_SSO && authConfig != null && id != null;
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
