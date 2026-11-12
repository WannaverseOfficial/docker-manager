package com.wannaverse.service;

import static com.wannaverse.service.ObservabilityConfigs.*;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.wannaverse.persistence.IngressCertificateRepository;
import com.wannaverse.persistence.IngressRoute;
import com.wannaverse.persistence.IngressRouteRepository;
import com.wannaverse.persistence.ObservabilityStack;
import com.wannaverse.persistence.ObservabilityStackRepository;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.InputValidator;

import io.jsonwebtoken.Jwts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a pre-wired observability stack on a host: Grafana and Prometheus, with Mimir (long-term
 * metrics written by Prometheus), Loki (logs) and Tempo (traces), and the collectors that feed
 * them: Grafana Alloy (container logs and OpenTelemetry from apps), cAdvisor (per-container
 * metrics) and node-exporter (the host).
 *
 * <ul>
 *   <li>Everything talks over its own network, {@value #NETWORK}; nothing but Grafana is published.
 *   <li>Apps send OpenTelemetry to Alloy on a second network, {@value #OTLP_NETWORK}, which only
 *       Alloy shares, so they can't reach the databases or Grafana's backends.
 *   <li>Grafana is reached over HTTPS through ingress, where Docker Manager administrators sign in
 *       with their own account (an RS256 token Docker Manager signs and Grafana verifies with the
 *       public key it was given), or on a 127.0.0.1 port with Grafana's own password.
 *   <li>Every container carries a hash of its settings; applying changes recreates only the
 *       containers whose settings changed. Data stays in named volumes.
 * </ul>
 */
@Service
public class ObservabilityService {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);

    // Pinned versions, checked on the registries on 2026-11-12.
    static final String GRAFANA_IMAGE = "grafana/grafana:13.3.1";
    static final String PROMETHEUS_IMAGE = "prom/prometheus:v3.16.0";
    static final String MIMIR_IMAGE = "grafana/mimir:3.2.4";
    static final String LOKI_IMAGE = "grafana/loki:3.7.9";
    static final String TEMPO_IMAGE = "grafana/tempo:3.1.1";
    static final String ALLOY_IMAGE = "grafana/alloy:v1.21.3";
    static final String CADVISOR_IMAGE = "ghcr.io/google/cadvisor:0.60.6";
    static final String NODE_EXPORTER_IMAGE = "prom/node-exporter:v1.12.1";

    static final String LABEL = "com.wannaverse.observability";
    static final String HASH_LABEL = "com.wannaverse.observability.hash";
    static final String NETWORK = "dm-obs";
    static final String OTLP_NETWORK = "dm-obs-otlp";
    static final String TOKEN_HEADER = "X-DM-Grafana-Token";
    static final String TOKEN_ISSUER = "docker-manager";
    static final long TOKEN_HOURS = 8;
    private static final String KEY_FILE = "/etc/grafana/dm/jwt.pem";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A component and the container it runs in. */
    public record Component(
            String key, String name, String description, String container, String image) {}

    static final List<Component> COMPONENTS =
            List.of(
                    new Component(
                            "grafana", "Grafana", "Dashboards and Explore", GRAFANA, GRAFANA_IMAGE),
                    new Component(
                            "prometheus",
                            "Prometheus",
                            "Scrapes metrics every 15 seconds",
                            PROMETHEUS,
                            PROMETHEUS_IMAGE),
                    new Component(
                            "mimir", "Mimir", "Long-term metrics storage", MIMIR, MIMIR_IMAGE),
                    new Component("loki", "Loki", "Logs", LOKI, LOKI_IMAGE),
                    new Component("tempo", "Tempo", "Traces", TEMPO, TEMPO_IMAGE),
                    new Component(
                            "alloy",
                            "Grafana Alloy",
                            "Collects container logs and OpenTelemetry",
                            ALLOY,
                            ALLOY_IMAGE),
                    new Component(
                            "cadvisor",
                            "cAdvisor",
                            "Per-container CPU, memory, network and disk",
                            CADVISOR,
                            CADVISOR_IMAGE),
                    new Component(
                            "node-exporter",
                            "node-exporter",
                            "The host's CPU, memory, load and disks",
                            NODE_EXPORTER,
                            NODE_EXPORTER_IMAGE));

    private final ObservabilityStackRepository repository;
    private final DockerHostService dockerHostService;
    private final EncryptionService encryptionService;
    private final IngressService ingressService;
    private final IngressRouteRepository routes;
    private final IngressCertificateRepository certificates;
    private final UserRepository users;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public ObservabilityService(
            ObservabilityStackRepository repository,
            DockerHostService dockerHostService,
            EncryptionService encryptionService,
            IngressService ingressService,
            IngressRouteRepository routes,
            IngressCertificateRepository certificates,
            UserRepository users) {
        this.repository = repository;
        this.dockerHostService = dockerHostService;
        this.encryptionService = encryptionService;
        this.ingressService = ingressService;
        this.routes = routes;
        this.certificates = certificates;
        this.users = users;
    }

    /** Work cut short by a restart is not running any more. */
    @EventListener(ApplicationReadyEvent.class)
    public void clearInterruptedWork() {
        for (ObservabilityStack s : repository.findAll()) {
            if (s.getActivity() != null) {
                s.setActivity(null);
                s.setLastResult(
                        "Docker Manager restarted while this was running. Apply the settings again"
                                + " to finish.");
                s.setLastResultFailed(true);
                repository.save(s);
            }
        }
    }

    // --- reading -----------------------------------------------------------------------------

    public record ComponentView(
            String key,
            String name,
            String description,
            String container,
            String image,
            boolean enabled,
            String state,
            String containerId,
            boolean outdated) {}

    public record StackView(
            ObservabilityStack stack,
            List<ComponentView> components,
            String grafanaUrl,
            Map<String, String> certificate,
            boolean busy) {}

    public Optional<StackView> get(String hostId) {
        return repository.findByDockerHostId(hostId).map(this::view);
    }

    private StackView view(ObservabilityStack s) {
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        Map<String, Spec> specs = new HashMap<>();
        for (Spec spec : specs(s)) specs.put(spec.name(), spec);
        List<ComponentView> list = new ArrayList<>();
        for (Component c : COMPONENTS) {
            Spec spec = specs.get(c.container());
            String state = "absent";
            String id = null;
            boolean outdated = false;
            try {
                InspectContainerResponse info = api.inspectContainer(c.container());
                state = info.getState().getStatus();
                id = info.getId();
                Map<String, String> labels = info.getConfig().getLabels();
                outdated =
                        spec != null
                                && (labels == null || !hash(spec).equals(labels.get(HASH_LABEL)));
            } catch (NotFoundException e) {
                if (spec != null) state = "missing";
            }
            list.add(
                    new ComponentView(
                            c.key(),
                            c.name(),
                            c.description(),
                            c.container(),
                            c.image(),
                            spec != null,
                            state,
                            id,
                            outdated));
        }
        return new StackView(
                s, list, grafanaUrl(s), certificate(s), busy.contains(s.getDockerHostId()));
    }

    private String grafanaUrl(ObservabilityStack s) {
        if ("PUBLIC".equals(s.getAccess())) {
            Map<String, String> cert = certificate(s);
            boolean https =
                    cert != null
                            && ("ACTIVE".equals(cert.get("status"))
                                    || "RENEWAL_PENDING".equals(cert.get("status")));
            int port = https ? 443 : 80;
            var config = ingressService.getIngressConfig(s.getDockerHostId()).orElse(null);
            if (config != null) port = https ? config.getHttpsPort() : config.getHttpPort();
            boolean standard = port == (https ? 443 : 80);
            return (https ? "https://" : "http://")
                    + s.getHostname()
                    + (standard ? "" : ":" + port)
                    + "/";
        }
        return "http://localhost:" + s.getLocalPort() + "/";
    }

    /** HTTPS state of Grafana's route: its certificate's status and message, or null. */
    private Map<String, String> certificate(ObservabilityStack s) {
        if (s.getIngressRouteId() == null) return null;
        var route = routes.findById(s.getIngressRouteId()).orElse(null);
        if (route == null)
            return Map.of("status", "MISSING", "message", "The ingress route was removed");
        if (route.getCertificateId() == null) return Map.of("status", "PENDING", "message", "");
        return certificates
                .findById(route.getCertificateId())
                .map(
                        c -> {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put("status", c.getStatus().name());
                            String msg =
                                    c.getLastRenewalError() != null
                                            ? c.getLastRenewalError()
                                            : c.getStatusMessage();
                            m.put("message", msg == null ? "" : msg);
                            return m;
                        })
                .orElse(Map.of("status", "PENDING", "message", ""));
    }

    /** Whether the stack is being set up, changed or repaired right now. */
    public boolean isBusy(String hostId) {
        return busy.contains(hostId);
    }

    public String adminPassword(String hostId) {
        return encryptionService.decrypt(require(hostId).getGrafanaAdminPassword());
    }

    // --- creating and changing ---------------------------------------------------------------

    /**
     * What the stack runs and how Grafana is reached. Null fields keep their current value (or the
     * default, when creating).
     */
    public record Settings(
            Boolean mimir,
            Boolean loki,
            Boolean tempo,
            Boolean collectLogs,
            Boolean collectMetrics,
            Boolean otlp,
            Integer metricsRetentionDays,
            Integer logsRetentionDays,
            Integer tracesRetentionDays,
            String access,
            String hostname,
            Integer port,
            /** The address this Docker Manager is opened at, e.g. https://docker.example.com. */
            String dockerManagerUrl) {}

    public ObservabilityStack create(String hostId, Settings settings) {
        if (repository.findByDockerHostId(hostId).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This host already has an observability stack");
        }
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        for (Component c : COMPONENTS) {
            String id = NginxApplier.findContainer(api, c.container());
            if (id != null && !ours(api, id)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A container named "
                                + c.container()
                                + " already exists and was not made by Docker Manager");
            }
        }
        ObservabilityStack s = new ObservabilityStack();
        s.setDockerHostId(hostId);
        s.setCreatedAt(System.currentTimeMillis());
        s.setMimirEnabled(true);
        s.setLokiEnabled(true);
        s.setTempoEnabled(true);
        s.setCollectLogs(true);
        s.setCollectMetrics(true);
        s.setOtlpEnabled(true);
        s.setMetricsRetentionDays(30);
        s.setLogsRetentionDays(14);
        s.setTracesRetentionDays(7);
        s.setAccess("LOCAL");
        s.setLocalPort(3000);
        s.setGrafanaAdminUser("grafana-admin");
        s.setGrafanaAdminPassword(encryptionService.encrypt(randomPassword()));
        KeyPair keys = newKeys();
        s.setSigningKey(
                encryptionService.encrypt(
                        Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())));
        s.setVerifyingKey(pem(keys.getPublic().getEncoded()));
        applySettings(s, settings);
        s.setActivity("Starting");
        s.setLastResult(null);
        s.setLastResultFailed(false);
        s = repository.save(s);
        start(s.getDockerHostId());
        return s;
    }

    public ObservabilityStack update(String hostId, Settings settings) {
        ObservabilityStack s = require(hostId);
        if (busy.contains(hostId)) throw busyError();
        ObservabilityStack changed = new ObservabilityStack();
        org.springframework.beans.BeanUtils.copyProperties(s, changed);
        // Validate on a copy, so a refused change leaves nothing half-modified to be flushed.
        applySettings(changed, settings);
        changed.setActivity("Applying changes");
        changed = repository.save(changed);
        start(hostId);
        return changed;
    }

    /** Recreates missing or changed containers and starts stopped ones. */
    public void repair(String hostId) {
        ObservabilityStack s = require(hostId);
        if (busy.contains(hostId)) throw busyError();
        s.setActivity("Repairing");
        repository.save(s);
        start(hostId);
    }

    private void applySettings(ObservabilityStack s, Settings in) {
        if (in == null) return;
        if (in.mimir() != null) s.setMimirEnabled(in.mimir());
        if (in.loki() != null) s.setLokiEnabled(in.loki());
        if (in.tempo() != null) s.setTempoEnabled(in.tempo());
        if (in.collectLogs() != null) s.setCollectLogs(in.collectLogs());
        if (in.collectMetrics() != null) s.setCollectMetrics(in.collectMetrics());
        if (in.otlp() != null) s.setOtlpEnabled(in.otlp());
        if (in.metricsRetentionDays() != null) s.setMetricsRetentionDays(in.metricsRetentionDays());
        if (in.logsRetentionDays() != null) s.setLogsRetentionDays(in.logsRetentionDays());
        if (in.tracesRetentionDays() != null) s.setTracesRetentionDays(in.tracesRetentionDays());
        checkDays("Metrics", s.getMetricsRetentionDays(), 3650);
        checkDays("Logs", s.getLogsRetentionDays(), 3650);
        checkDays("Traces", s.getTracesRetentionDays(), 365);
        if (s.isCollectLogs() && !s.isLokiEnabled()) s.setCollectLogs(false);

        if (in.access() != null) {
            String access = in.access().toUpperCase(Locale.ROOT);
            if (!access.equals("PUBLIC") && !access.equals("LOCAL")) {
                throw bad("Choose how Grafana is reached");
            }
            s.setAccess(access);
        }
        if ("PUBLIC".equals(s.getAccess())) {
            String host =
                    in.hostname() != null
                            ? in.hostname().trim().toLowerCase(Locale.ROOT)
                            : s.getHostname();
            if (host == null || !InputValidator.isValidHostname(host) || !host.contains(".")) {
                throw bad("Enter Grafana's full hostname, for example grafana.example.com");
            }
            if (!ingressService.isIngressEnabled(s.getDockerHostId())) {
                throw bad("Turn on Ingress for this host first, or reach Grafana on a port");
            }
            s.setHostname(host);
            s.setLocalPort(null);
            if (in.dockerManagerUrl() != null) {
                String base = in.dockerManagerUrl().trim().replaceFirst("/+$", "");
                String url = base + "/observability/grafana/?host=" + s.getDockerHostId();
                if (!IngressService.SIGN_IN_URL.matcher(url).matches() || url.length() > 500) {
                    throw bad("Docker Manager's address is not a plain http(s) URL");
                }
                s.setSignInUrl(url);
            }
        } else {
            int port =
                    in.port() != null
                            ? in.port()
                            : s.getLocalPort() == null ? 3000 : s.getLocalPort();
            if (port < 1024 || port > 65535) throw bad("Choose a port between 1024 and 65535");
            String holder = portHolder(s.getDockerHostId(), port);
            if (holder != null) {
                throw bad(
                        "Port "
                                + port
                                + " is already published by the container "
                                + holder
                                + ". Choose another port.");
            }
            s.setLocalPort(port);
            s.setHostname(null);
        }
    }

    private static void checkDays(String what, int days, int max) {
        if (days < 1 || days > max) {
            throw bad(what + " retention must be between 1 and " + max + " days");
        }
    }

    private void start(String hostId) {
        if (!busy.add(hostId)) throw busyError();
        executor.submit(
                () -> {
                    try {
                        ObservabilityStack s = require(hostId);
                        try {
                            apply(s);
                            s = require(hostId);
                            s.setLastResult(null);
                            s.setLastResultFailed(false);
                        } catch (Exception e) {
                            log.warn("Observability stack on {}: {}", hostId, e.getMessage(), e);
                            s = require(hostId);
                            s.setLastResult(message(e));
                            s.setLastResultFailed(true);
                        }
                        s.setActivity(null);
                        repository.save(s);
                    } catch (Exception e) {
                        log.warn("Observability stack on {}: {}", hostId, e.getMessage());
                    } finally {
                        busy.remove(hostId);
                    }
                });
    }

    private void activity(ObservabilityStack s, String text) {
        ObservabilityStack fresh = require(s.getDockerHostId());
        fresh.setActivity(text);
        repository.save(fresh);
    }

    /** Brings the host in line with the stack's settings. */
    private void apply(ObservabilityStack s) {
        DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
        List<Spec> wanted = specs(s);

        activity(s, "Downloading images");
        for (Spec spec : wanted) ToolRunner.ensureImage(api, spec.image());

        ensureNetwork(api, NETWORK, false);
        if (s.isOtlpEnabled()) ensureNetwork(api, OTLP_NETWORK, false);

        Set<String> keep = new HashSet<>();
        for (Spec spec : wanted) keep.add(spec.name());
        for (Component c : COMPONENTS) {
            if (keep.contains(c.container())) continue;
            String id = NginxApplier.findContainer(api, c.container());
            if (id != null && ours(api, id)) {
                activity(s, "Removing " + c.name());
                api.client().removeContainerCmd(id).withForce(true).exec();
            }
        }
        for (Spec spec : wanted) {
            Component c =
                    COMPONENTS.stream()
                            .filter(x -> x.container().equals(spec.name()))
                            .findFirst()
                            .orElseThrow();
            activity(s, "Starting " + c.name());
            boolean fresh = ensureContainer(api, s, spec);
            if (fresh && spec.name().equals(GRAFANA)) resetGrafanaPassword(api, s);
        }

        if (!s.isOtlpEnabled()) removeNetwork(api, OTLP_NETWORK);
        activity(s, "Setting up the Grafana address");
        applyRoute(s);
    }

    /** Ingress route for Grafana: created, moved or removed to match the settings. */
    private void applyRoute(ObservabilityStack s) {
        ObservabilityStack fresh = require(s.getDockerHostId());
        IngressRoute route =
                fresh.getIngressRouteId() == null
                        ? null
                        : routes.findById(fresh.getIngressRouteId()).orElse(null);
        boolean pub = "PUBLIC".equals(s.getAccess());
        if (route != null && (!pub || !route.getHostname().equals(s.getHostname()))) {
            try {
                ingressService.setGrafanaSignIn(route.getId(), null);
                ingressService.removeRoute(route.getId(), true, null, "observability");
            } catch (Exception e) {
                log.warn("Could not remove Grafana's route: {}", e.getMessage());
            }
            route = null;
        }
        if (pub && route == null) {
            DockerAPI api = dockerHostService.getDockerAPI(s.getDockerHostId());
            String id = NginxApplier.findContainer(api, GRAFANA);
            route =
                    ingressService.exposeApp(
                            s.getDockerHostId(),
                            new IngressService.ExposeAppRequest(
                                    id,
                                    GRAFANA,
                                    s.getHostname(),
                                    "/",
                                    3000,
                                    "HTTP",
                                    "LETS_ENCRYPT",
                                    null,
                                    false,
                                    null,
                                    null,
                                    true,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null),
                            null,
                            "observability");
        }
        if (route != null && !Objects.equals(route.getAuthConfig(), s.getSignInUrl())) {
            route = ingressService.setGrafanaSignIn(route.getId(), s.getSignInUrl());
        }
        fresh = require(s.getDockerHostId());
        fresh.setIngressRouteId(route == null ? null : route.getId());
        repository.save(fresh);
    }

    public void remove(String hostId, boolean deleteData) {
        ObservabilityStack s = require(hostId);
        if (busy.contains(hostId)) throw busyError();
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        if (s.getIngressRouteId() != null && routes.existsById(s.getIngressRouteId())) {
            try {
                ingressService.setGrafanaSignIn(s.getIngressRouteId(), null);
                ingressService.removeRoute(s.getIngressRouteId(), true, null, "observability");
            } catch (Exception e) {
                log.warn("Could not remove Grafana's route: {}", e.getMessage());
            }
        }
        for (Component c : COMPONENTS) {
            String id = NginxApplier.findContainer(api, c.container());
            if (id != null && ours(api, id)) {
                api.client().removeContainerCmd(id).withForce(true).exec();
            }
        }
        removeNetwork(api, OTLP_NETWORK);
        removeNetwork(api, NETWORK);
        if (deleteData) {
            for (String volume : volumes()) {
                try {
                    api.removeVolume(volume);
                } catch (NotFoundException ignored) {
                } catch (Exception e) {
                    log.warn("Could not remove {}: {}", volume, e.getMessage());
                }
            }
        }
        repository.delete(s);
    }

    static List<String> volumes() {
        return List.of(
                "dm-obs-grafana-data",
                "dm-obs-prometheus-data",
                "dm-obs-mimir-data",
                "dm-obs-loki-data",
                "dm-obs-tempo-data",
                "dm-obs-alloy-data");
    }

    // --- apps sending OpenTelemetry ----------------------------------------------------------

    public record Member(String id, String name) {}

    public List<Member> members(String hostId) {
        require(hostId);
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        List<Member> list = new ArrayList<>();
        try {
            var network = api.client().inspectNetworkCmd().withNetworkId(OTLP_NETWORK).exec();
            if (network.getContainers() != null) {
                network.getContainers()
                        .forEach(
                                (id, c) -> {
                                    String n =
                                            c.getName() == null
                                                    ? id
                                                    : c.getName().replaceFirst("^/", "");
                                    if (!n.equals(ALLOY)) list.add(new Member(id, n));
                                });
            }
        } catch (NotFoundException ignored) {
        }
        list.sort(Comparator.comparing(Member::name));
        return list;
    }

    public void connect(String hostId, String container) {
        ObservabilityStack s = require(hostId);
        if (!s.isOtlpEnabled()) throw bad("Turn on OpenTelemetry for apps first");
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        InspectContainerResponse c;
        try {
            c = api.inspectContainer(container);
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such container");
        }
        if (c.getName() != null && c.getName().startsWith("/dm-obs-")) {
            throw bad("That's part of the observability stack");
        }
        var nets = c.getNetworkSettings().getNetworks();
        if (nets != null && nets.containsKey(OTLP_NETWORK)) return;
        api.connectContainerToNetwork(OTLP_NETWORK, c.getId());
    }

    public void disconnect(String hostId, String container) {
        require(hostId);
        if (container.equals(ALLOY)) throw bad("That's Grafana Alloy itself");
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        api.disconnectContainerFromNetwork(OTLP_NETWORK, container);
    }

    // --- Docker Manager sign-in for Grafana --------------------------------------------------

    /** Where the browser posts the token, and the token itself. */
    public record SignIn(String action, String token) {}

    public SignIn signIn(String hostId, String userId) {
        ObservabilityStack s = require(hostId);
        if (!"PUBLIC".equals(s.getAccess()) || s.getIngressRouteId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Docker Manager sign-in works when Grafana has a hostname. Use Grafana's own"
                            + " password on its port.");
        }
        if (s.getSignInUrl() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Docker Manager sign-in is turned off for this Grafana");
        }
        User user =
                users.findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "Sign in again"));
        if (!user.isAdmin()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Only administrators can open Grafana");
        }
        String base = grafanaUrl(s).replaceFirst("/$", "");
        return new SignIn(base + "/.dm-sso/" + s.getIngressRouteId() + "/callback", token(s, user));
    }

    String token(ObservabilityStack s, User user) {
        Instant now = Instant.now();
        var builder =
                Jwts.builder()
                        .issuer(TOKEN_ISSUER)
                        .subject(user.getId())
                        .claim("login", user.getUsername())
                        .claim("name", user.getUsername())
                        // Only administrators get here (Grafana sees every container's data).
                        .claim("role", "GrafanaAdmin")
                        // A little slack for clocks that differ between this server and Grafana's.
                        .issuedAt(Date.from(now.minusSeconds(30)))
                        .notBefore(Date.from(now.minusSeconds(60)))
                        .expiration(Date.from(now.plusSeconds(TOKEN_HOURS * 3600)));
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            builder.claim("email", user.getEmail());
        }
        return builder.signWith(signingKey(s), Jwts.SIG.RS256).compact();
    }

    private PrivateKey signingKey(ObservabilityStack s) {
        try {
            byte[] der = Base64.getDecoder().decode(encryptionService.decrypt(s.getSigningKey()));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("The Grafana signing key can't be read", e);
        }
    }

    static KeyPair newKeys() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, RANDOM);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A public key (X.509 SubjectPublicKeyInfo, which Grafana calls PKIX) as PEM. */
    static String pem(byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    // --- containers --------------------------------------------------------------------------

    /** Everything about one container; its hash decides whether it is recreated. */
    record Spec(
            String name,
            String image,
            List<String> cmd,
            List<String> env,
            List<String> binds,
            String user,
            boolean privileged,
            boolean hostPid,
            Integer publishPort,
            int containerPort,
            List<String> extraNetworks,
            SortedMap<String, String> files,
            /** Replaces the image's entrypoint when not empty. */
            List<String> entrypoint) {}

    List<Spec> specs(ObservabilityStack s) {
        List<Spec> list = new ArrayList<>();
        if (s.isMimirEnabled()) {
            list.add(
                    spec(
                            MIMIR,
                            MIMIR_IMAGE,
                            List.of("-config.file=/etc/mimir/mimir.yaml"),
                            List.of(),
                            List.of("dm-obs-mimir-data:/data"),
                            null,
                            files("/etc/mimir/mimir.yaml", mimir(s))));
        }
        if (s.isLokiEnabled()) {
            list.add(
                    spec(
                            LOKI,
                            LOKI_IMAGE,
                            List.of("-config.file=/etc/loki/dm.yaml"),
                            List.of(),
                            List.of("dm-obs-loki-data:/loki"),
                            null,
                            files("/etc/loki/dm.yaml", loki(s))));
        }
        if (s.isTempoEnabled()) {
            list.add(
                    spec(
                            TEMPO,
                            TEMPO_IMAGE,
                            List.of("-target=all", "-config.file=/etc/tempo/tempo.yaml"),
                            List.of(),
                            List.of("dm-obs-tempo-data:/var/tempo"),
                            null,
                            files("/etc/tempo/tempo.yaml", tempo(s))));
        }
        list.add(
                spec(
                        PROMETHEUS,
                        PROMETHEUS_IMAGE,
                        prometheusArgs(s),
                        List.of(),
                        List.of("dm-obs-prometheus-data:/prometheus"),
                        null,
                        files("/etc/prometheus/prometheus.yml", prometheus(s))));
        if (s.isCollectMetrics()) {
            list.add(
                    new Spec(
                            CADVISOR,
                            CADVISOR_IMAGE,
                            // Docker's containerd socket is /run/containerd on most hosts, but
                            // under /run/docker in Docker-in-Docker; use whichever exists.
                            List.of(
                                    "sock=/run/containerd/containerd.sock; [ -S \"$sock\" ] ||"
                                        + " sock=/run/docker/containerd/containerd.sock; exec"
                                        + " /usr/bin/cadvisor -logtostderr --containerd=\"$sock\""
                                        + " \"$@\"",
                                    "cadvisor",
                                    "--docker_only=true",
                                    "--housekeeping_interval=15s",
                                    "--store_container_labels=false",
                                    "--whitelisted_container_labels=com.docker.compose.project,"
                                            + "com.docker.compose.service",
                                    "--disable_metrics=percpu,sched,tcp,udp,advtcp,hugetlb,"
                                            + "referenced_memory,cpu_topology,resctrl,"
                                            + "memory_numa,process"),
                            List.of(),
                            List.of(
                                    "/:/rootfs:ro",
                                    "/var/run:/var/run:ro",
                                    "/sys:/sys:ro",
                                    "/var/lib/docker/:/var/lib/docker:ro",
                                    "/dev/disk/:/dev/disk:ro"),
                            null,
                            true,
                            false,
                            null,
                            8080,
                            List.of(),
                            new TreeMap<>(),
                            List.of("sh", "-c")));
            list.add(
                    new Spec(
                            NODE_EXPORTER,
                            NODE_EXPORTER_IMAGE,
                            List.of("--path.rootfs=/host"),
                            List.of(),
                            List.of("/:/host:ro,rslave"),
                            null,
                            false,
                            true,
                            null,
                            9100,
                            List.of(),
                            new TreeMap<>(),
                            List.of()));
        }
        if (alloyNeeded(s)) {
            List<String> cmd =
                    List.of(
                            "run",
                            "/etc/alloy/config.alloy",
                            "--storage.path=/var/lib/alloy/data",
                            // The debugging UI stays inside the container.
                            "--server.http.listen-addr=127.0.0.1:12345",
                            "--disable-reporting");
            List<String> binds = new ArrayList<>(List.of("dm-obs-alloy-data:/var/lib/alloy/data"));
            if (s.isCollectLogs() && s.isLokiEnabled()) {
                binds.add("/var/run/docker.sock:/var/run/docker.sock:ro");
            }
            list.add(
                    new Spec(
                            ALLOY,
                            ALLOY_IMAGE,
                            cmd,
                            List.of(),
                            binds,
                            // Reading the Docker socket needs root.
                            "0",
                            false,
                            false,
                            null,
                            12345,
                            s.isOtlpEnabled() ? List.of(OTLP_NETWORK) : List.of(),
                            files("/etc/alloy/config.alloy", alloy(s)),
                            List.of()));
        }
        list.add(grafanaSpec(s));
        return list;
    }

    private Spec grafanaSpec(ObservabilityStack s) {
        boolean pub = "PUBLIC".equals(s.getAccess());
        List<String> env = new ArrayList<>();
        env.add(
                "GF_SERVER_ROOT_URL="
                        + (pub
                                ? "https://" + s.getHostname() + "/"
                                : "http://localhost:" + s.getLocalPort() + "/"));
        env.add("GF_SECURITY_ADMIN_USER=" + s.getGrafanaAdminUser());
        // Grafana matches signed-in users to accounts by email first. Its default for the built-in
        // administrator, admin@localhost, is also Docker Manager's, which would hand that account
        // to whoever signs in with it.
        env.add("GF_SECURITY_ADMIN_EMAIL=" + s.getGrafanaAdminUser() + "@grafana.invalid");
        env.add("GF_USERS_ALLOW_SIGN_UP=false");
        env.add("GF_ANALYTICS_REPORTING_ENABLED=false");
        env.add("GF_ANALYTICS_CHECK_FOR_UPDATES=false");
        env.add("GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES=false");
        env.add("GF_NEWS_NEWS_FEED_ENABLED=false");
        env.add("GF_LOG_LEVEL=warn");
        if (!dashboards(s).isEmpty()) {
            // Grafana opens on the first one: containers when collected, otherwise logs.
            env.add(
                    "GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/etc/grafana/dashboards/"
                            + dashboards(s).getFirst());
        }
        if (pub) {
            env.add("GF_SECURITY_COOKIE_SECURE=true");
            if (s.getSignInUrl() != null) {
                env.add("GF_AUTH_JWT_ENABLED=true");
                env.add("GF_AUTH_JWT_HEADER_NAME=" + TOKEN_HEADER);
                env.add("GF_AUTH_JWT_USERNAME_CLAIM=login");
                env.add("GF_AUTH_JWT_EMAIL_CLAIM=email");
                env.add("GF_AUTH_JWT_KEY_FILE=" + KEY_FILE);
                env.add("GF_AUTH_JWT_AUTO_SIGN_UP=true");
                env.add("GF_AUTH_JWT_ROLE_ATTRIBUTE_PATH=role");
                env.add("GF_AUTH_JWT_ROLE_ATTRIBUTE_STRICT=true");
                env.add("GF_AUTH_JWT_ALLOW_ASSIGN_GRAFANA_ADMIN=true");
                env.add("GF_AUTH_JWT_EXPECT_CLAIMS={\"iss\":\"" + TOKEN_ISSUER + "\"}");
                env.add("GF_AUTH_SIGNOUT_REDIRECT_URL=/.dm-sso/signed-out");
            }
        }
        SortedMap<String, String> files = new TreeMap<>();
        files.put(
                "/etc/grafana/provisioning/datasources/docker-manager.yaml", grafanaDatasources(s));
        files.put(
                "/etc/grafana/provisioning/dashboards/docker-manager.yaml",
                grafanaDashboardProvider());
        for (String d : dashboards(s)) files.put("/etc/grafana/dashboards/" + d, resource(d));
        files.put(KEY_FILE, s.getVerifyingKey());
        return new Spec(
                GRAFANA,
                GRAFANA_IMAGE,
                List.of(),
                env,
                List.of("dm-obs-grafana-data:/var/lib/grafana"),
                null,
                false,
                false,
                pub ? null : s.getLocalPort(),
                3000,
                List.of(),
                files,
                List.of());
    }

    private static Spec spec(
            String name,
            String image,
            List<String> cmd,
            List<String> env,
            List<String> binds,
            String user,
            SortedMap<String, String> files) {
        return new Spec(
                name, image, cmd, env, binds, user, false, false, null, 0, List.of(), files,
                List.of());
    }

    private static SortedMap<String, String> files(String path, String content) {
        SortedMap<String, String> m = new TreeMap<>();
        m.put(path, content);
        return m;
    }

    private static String resource(String dashboard) {
        try (InputStream in =
                ObservabilityService.class.getResourceAsStream(
                        "/observability/dashboards/" + dashboard)) {
            if (in == null) throw new IllegalStateException(dashboard + " is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + dashboard, e);
        }
    }

    static String hash(Spec spec) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(spec.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Creates the container, or recreates it when its settings changed; starts it if stopped.
     * Returns true when the container was (re)created.
     */
    private boolean ensureContainer(DockerAPI api, ObservabilityStack s, Spec spec) {
        String hash = hash(spec);
        String existing = NginxApplier.findContainer(api, spec.name());
        if (existing != null) {
            InspectContainerResponse info = api.inspectContainer(existing);
            Map<String, String> labels = info.getConfig().getLabels();
            if (labels == null || !labels.containsKey(LABEL)) {
                throw new IllegalStateException(
                        "A container named "
                                + spec.name()
                                + " already exists and was not made by Docker Manager");
            }
            if (hash.equals(labels.get(HASH_LABEL))) {
                if (!Boolean.TRUE.equals(info.getState().getRunning())) {
                    api.startContainer(existing);
                }
                return false;
            }
            api.client().removeContainerCmd(existing).withForce(true).exec();
        }
        for (String bind : spec.binds()) {
            String source = bind.substring(0, bind.indexOf(':'));
            if (source.startsWith("dm-obs-")) ensureVolume(api, source, s.getDockerHostId());
        }

        try {
            createAndStart(api, s, spec, hash, spec.binds());
        } catch (RuntimeException e) {
            // node-exporter asks for rslave so it sees disks mounted later. Docker Desktop's VM
            // (and some other hosts) refuse that for /, so fall back to a plain read-only mount.
            String m = String.valueOf(e.getMessage());
            boolean propagation = spec.binds().stream().anyMatch(b -> b.endsWith(",rslave"));
            if (!propagation || !m.contains("not a shared or slave mount")) throw e;
            log.info("{}: the host refuses rslave mounts; mounting without it", spec.name());
            createAndStart(
                    api,
                    s,
                    spec,
                    hash,
                    spec.binds().stream().map(b -> b.replaceFirst(",rslave$", "")).toList());
        }
        return true;
    }

    private void createAndStart(
            DockerAPI api, ObservabilityStack s, Spec spec, String hash, List<String> binds) {
        HostConfig hc =
                HostConfig.newHostConfig()
                        .withBinds(binds.stream().map(Bind::parse).toList())
                        .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
                        .withNetworkMode(NETWORK)
                        .withPrivileged(spec.privileged());
        if (spec.hostPid()) hc.withPidMode("host");
        var create =
                api.client()
                        .createContainerCmd(spec.image())
                        .withName(spec.name())
                        .withLabels(Map.of(LABEL, s.getDockerHostId(), HASH_LABEL, hash))
                        .withEnv(spec.env());
        if (!spec.entrypoint().isEmpty()) create.withEntrypoint(spec.entrypoint());
        if (!spec.cmd().isEmpty()) create.withCmd(spec.cmd());
        if (spec.user() != null) create.withUser(spec.user());
        if (spec.publishPort() != null) {
            Ports ports = new Ports();
            ports.bind(
                    ExposedPort.tcp(spec.containerPort()),
                    Ports.Binding.bindIpAndPort("127.0.0.1", spec.publishPort()));
            hc.withPortBindings(ports);
            create.withExposedPorts(ExposedPort.tcp(spec.containerPort()));
        }
        String id = create.withHostConfig(hc).exec().getId();
        try {
            if (!spec.files().isEmpty()) {
                Map<String, byte[]> bytes = new LinkedHashMap<>();
                spec.files().forEach((k, v) -> bytes.put(k, v.getBytes(StandardCharsets.UTF_8)));
                api.putFiles(id, bytes, 0644);
            }
            for (String network : spec.extraNetworks()) api.connectContainerToNetwork(network, id);
            api.startContainer(id);
        } catch (RuntimeException e) {
            try {
                api.client().removeContainerCmd(id).withForce(true).exec();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /**
     * Grafana takes its administrator password from the environment only when its database is new.
     * Data kept from an earlier stack has the old password, so it's set to the one shown here.
     */
    private void resetGrafanaPassword(DockerAPI api, ObservabilityStack s) {
        String id = NginxApplier.findContainer(api, GRAFANA);
        if (id == null) return;
        String password = encryptionService.decrypt(s.getGrafanaAdminPassword());
        // Wait for Grafana to finish creating or migrating its database.
        for (int i = 0; i < 60; i++) {
            var out =
                    api.execFull(
                            id,
                            Map.of(),
                            15,
                            "sh",
                            "-c",
                            "wget -q -O /dev/null http://127.0.0.1:3000/api/health");
            if (out.ok()) break;
            sleep(2000);
        }
        // The password goes in through the environment, not the command line.
        var out =
                api.execFull(
                        id,
                        Map.of("DM_PASSWORD", password),
                        60,
                        "sh",
                        "-c",
                        "grafana cli --homepath /usr/share/grafana admin reset-admin-password"
                                + " \"$DM_PASSWORD\"");
        if (!out.ok()) {
            log.warn("Could not set Grafana's administrator password: {}", out.stderr());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        }
    }

    private static void ensureVolume(DockerAPI api, String name, String hostId) {
        try {
            api.client().inspectVolumeCmd(name).exec();
        } catch (NotFoundException e) {
            api.client().createVolumeCmd().withName(name).withLabels(Map.of(LABEL, hostId)).exec();
        }
    }

    private static void ensureNetwork(DockerAPI api, String name, boolean internal) {
        try {
            api.client().inspectNetworkCmd().withNetworkId(name).exec();
        } catch (NotFoundException e) {
            api.client()
                    .createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .withInternal(internal)
                    .withLabels(Map.of(LABEL, "network"))
                    .exec();
        }
    }

    /** Disconnects whatever is still attached (apps sending OTLP), then removes the network. */
    private static void removeNetwork(DockerAPI api, String name) {
        try {
            var network = api.client().inspectNetworkCmd().withNetworkId(name).exec();
            if (network.getLabels() == null || !network.getLabels().containsKey(LABEL)) return;
            if (network.getContainers() != null) {
                for (String id : network.getContainers().keySet()) {
                    try {
                        api.disconnectContainerFromNetwork(name, id);
                    } catch (Exception ignored) {
                    }
                }
            }
            api.removeNetwork(name);
        } catch (NotFoundException ignored) {
        } catch (Exception e) {
            log.warn("Could not remove the {} network: {}", name, e.getMessage());
        }
    }

    private static boolean ours(DockerAPI api, String id) {
        Map<String, String> labels = api.inspectContainer(id).getConfig().getLabels();
        return labels != null && labels.containsKey(LABEL);
    }

    // --- helpers -----------------------------------------------------------------------------

    private ObservabilityStack require(String hostId) {
        return repository
                .findByDockerHostId(hostId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "No observability stack on this host"));
    }

    private static ResponseStatusException busyError() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "The stack is busy. Try again when it has finished.");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * The container (other than Grafana itself) that publishes {@code port} on the host, or null.
     * Programs outside Docker aren't visible here; Docker's own error covers those.
     */
    private String portHolder(String hostId, int port) {
        try {
            for (var c : dockerHostService.getDockerAPI(hostId).listAllContainers()) {
                String name =
                        c.getNames() == null || c.getNames().length == 0
                                ? c.getId()
                                : c.getNames()[0].replaceFirst("^/", "");
                if (name.equals(GRAFANA) || c.getPorts() == null) continue;
                if (!"running".equals(c.getState())) continue;
                for (var p : c.getPorts()) {
                    if (p.getPublicPort() != null && p.getPublicPort() == port) return name;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not check port {}: {}", port, e.getMessage());
        }
        return null;
    }

    static String message(Exception e) {
        String m = e instanceof ResponseStatusException r ? r.getReason() : e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        var taken =
                java.util.regex.Pattern.compile(
                                "(?:Bind for [^ ]*?:(\\d+) failed: port is already allocated"
                                        + "|listen tcp[^ ]* [^ ]*?:(\\d+): bind: address already in"
                                        + " use)")
                        .matcher(m);
        if (taken.find()) {
            String port = taken.group(1) != null ? taken.group(1) : taken.group(2);
            return "Port "
                    + port
                    + " is already used on this host by another program or container. Choose"
                    + " another port under Settings and apply the changes.";
        }
        return m;
    }

    static String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }
}
