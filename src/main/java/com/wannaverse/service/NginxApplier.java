package com.wannaverse.service;

import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.wannaverse.persistence.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the ingress proxy container: creates it, writes its config, certificates and password files,
 * and keeps it healthy.
 *
 * <ul>
 *   <li>Files are written with the Docker archive API, not by echoing base64 through a shell.
 *   <li>A new config is tested with {@code nginx -t} before it replaces the running one. A failed
 *       test leaves the proxy serving the previous config and reports the error. (Previously the
 *       test result was logged and ignored.)
 *   <li>The container is created with a restart policy, so it comes back after a reboot.
 *   <li>Every minute the proxy is checked: a missing or stopped container is recreated or started
 *       with its config and certificates, and route targets that were recreated (for example by a
 *       new deployment) are reconnected to the ingress network.
 * </ul>
 */
@Service
public class NginxApplier {
    private static final Logger log = LoggerFactory.getLogger(NginxApplier.class);
    public static final String NGINX_IMAGE = "nginx:stable-alpine";
    private static final String CONF = "/etc/nginx/nginx.conf";
    private static final String NEXT = "/etc/nginx/nginx.conf.next";

    private final IngressConfigRepository configRepository;
    private final IngressRouteRepository routeRepository;
    private final IngressCertificateRepository certificateRepository;
    private final DockerHostRepository hostRepository;
    private final DockerService dockerService;
    private final NginxConfigGenerator generator;
    private final EncryptionService encryptionService;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public NginxApplier(
            IngressConfigRepository configRepository,
            IngressRouteRepository routeRepository,
            IngressCertificateRepository certificateRepository,
            DockerHostRepository hostRepository,
            DockerService dockerService,
            NginxConfigGenerator generator,
            EncryptionService encryptionService) {
        this.configRepository = configRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.hostRepository = hostRepository;
        this.dockerService = dockerService;
        this.generator = generator;
        this.encryptionService = encryptionService;
    }

    public static class ApplyException extends RuntimeException {
        public ApplyException(String message) {
            super(message);
        }
    }

    DockerAPI api(IngressConfig config) {
        DockerHost host =
                hostRepository
                        .findById(config.getDockerHostId())
                        .orElseThrow(() -> new IllegalStateException("Docker host not found"));
        return dockerService.dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()));
    }

    private ReentrantLock lock(IngressConfig config) {
        return locks.computeIfAbsent(config.getDockerHostId(), k -> new ReentrantLock());
    }

    // --- container ---------------------------------------------------------------------------

    /**
     * Makes sure the network and the running proxy container exist. Returns true when the container
     * was (re)created, which means its files must be written again.
     */
    public boolean ensureProxy(IngressConfig config) {
        DockerAPI api = api(config);
        String network = ensureNetwork(api, config);

        String containerId = findContainer(api, config.getNginxContainerName());
        if (containerId != null && !isOurProxy(api, containerId, config)) {
            // Never write config into (or reload) a container someone else runs under that name.
            throw new ApplyException(
                    "A container named '"
                            + config.getNginxContainerName()
                            + "' already exists and was not created by Docker Manager. Rename or"
                            + " remove it, then try again.");
        }
        boolean created = false;
        if (containerId == null) {
            try {
                api.inspectImage(NGINX_IMAGE);
            } catch (NotFoundException e) {
                try {
                    api.pullImage(NGINX_IMAGE);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ApplyException("Pulling nginx was interrupted");
                }
            }
            Ports ports = new Ports();
            ports.bind(ExposedPort.tcp(80), Ports.Binding.bindPort(config.getHttpPort()));
            ports.bind(ExposedPort.tcp(443), Ports.Binding.bindPort(config.getHttpsPort()));
            containerId =
                    api.client()
                            .createContainerCmd(NGINX_IMAGE)
                            .withName(config.getNginxContainerName())
                            .withExposedPorts(ExposedPort.tcp(80), ExposedPort.tcp(443))
                            .withLabels(Map.of("com.wannaverse.ingress", config.getDockerHostId()))
                            .withHostConfig(
                                    HostConfig.newHostConfig()
                                            .withPortBindings(ports)
                                            .withNetworkMode(network)
                                            .withRestartPolicy(
                                                    RestartPolicy.unlessStoppedRestart()))
                            .exec()
                            .getId();
            created = true;
        }

        var state = api.inspectContainer(containerId).getState();
        if (!Boolean.TRUE.equals(state.getRunning())) {
            try {
                api.startContainer(containerId);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("port is already allocated")
                        || msg.contains("address already in use")) {
                    throw new ApplyException(
                            "Port "
                                    + config.getHttpPort()
                                    + " or "
                                    + config.getHttpsPort()
                                    + " is already used by another program on this host. Stop it"
                                    + " or choose other ports.");
                }
                throw new ApplyException("Could not start the proxy: " + msg);
            }
        }
        if (!containerId.equals(config.getNginxContainerId())) {
            config.setNginxContainerId(containerId);
            configRepository.save(config);
        }
        return created;
    }

    /**
     * Moves the proxy to other host ports. Docker can't change a container's ports, so a new proxy
     * is started with the same configuration; the old one is kept aside and put back if the new one
     * can't start (a port already in use, for example). Routes are down for a few seconds.
     */
    public void changePorts(IngressConfig config, int httpPort, int httpsPort) {
        ReentrantLock lock = lock(config);
        lock.lock();
        try {
            DockerAPI api = api(config);
            int oldHttp = config.getHttpPort();
            int oldHttps = config.getHttpsPort();
            String oldContainerId = config.getNginxContainerId();
            String oldId = findContainer(api, config.getNginxContainerName());
            if (oldId != null && !isOurProxy(api, oldId, config)) {
                throw new ApplyException(
                        "A container named '"
                                + config.getNginxContainerName()
                                + "' already exists and was not created by Docker Manager.");
            }
            String backupName =
                    config.getNginxContainerName() + "-old-" + System.currentTimeMillis();
            boolean wasRunning = false;
            if (oldId != null) {
                wasRunning =
                        Boolean.TRUE.equals(api.inspectContainer(oldId).getState().getRunning());
                // Stopped first: the new proxy may keep one of the same ports.
                if (wasRunning) api.client().stopContainerCmd(oldId).withTimeout(10).exec();
                api.client().renameContainerCmd(oldId).withName(backupName).exec();
            }
            config.setHttpPort(httpPort);
            config.setHttpsPort(httpsPort);
            config.setNginxContainerId(null);
            try {
                apply(config); // creates and starts the new proxy, then writes its files
            } catch (RuntimeException e) {
                log.warn(
                        "The proxy didn't start on the new ports, restoring the old one: {}",
                        e.getMessage());
                String newId = findContainer(api, config.getNginxContainerName());
                if (newId != null && !newId.equals(oldId)) {
                    try {
                        api.client().removeContainerCmd(newId).withForce(true).exec();
                    } catch (Exception ignored) {
                    }
                }
                config.setHttpPort(oldHttp);
                config.setHttpsPort(oldHttps);
                config.setNginxContainerId(oldContainerId);
                configRepository.save(config);
                if (oldId != null) {
                    try {
                        api.client()
                                .renameContainerCmd(oldId)
                                .withName(config.getNginxContainerName())
                                .exec();
                        if (wasRunning) api.startContainer(oldId);
                    } catch (Exception restore) {
                        log.error("Could not restore the previous proxy: {}", restore.getMessage());
                    }
                }
                throw e;
            }
            configRepository.save(config);
            if (oldId != null) {
                try {
                    api.client().removeContainerCmd(oldId).withForce(true).exec();
                } catch (Exception e) {
                    log.warn(
                            "The old proxy {} could not be removed: {}",
                            backupName,
                            e.getMessage());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private String ensureNetwork(DockerAPI api, IngressConfig config) {
        String name = config.getIngressNetworkName();
        for (Network n : api.listNetworks()) {
            if (name.equals(n.getName())) {
                if (!n.getId().equals(config.getIngressNetworkId())) {
                    config.setIngressNetworkId(n.getId());
                    configRepository.save(config);
                }
                return name;
            }
        }
        String id = api.createNetwork(name).getId();
        config.setIngressNetworkId(id);
        configRepository.save(config);
        return name;
    }

    /** Created by Docker Manager for this host: labelled, or the container 1.x recorded. */
    private static boolean isOurProxy(DockerAPI api, String containerId, IngressConfig config) {
        if (containerId.equals(config.getNginxContainerId())) return true;
        var labels = api.inspectContainer(containerId).getConfig().getLabels();
        return labels != null
                && config.getDockerHostId().equals(labels.get("com.wannaverse.ingress"));
    }

    static String findContainer(DockerAPI api, String name) {
        for (Container c : api.listAllContainers()) {
            if (c.getNames() != null && Arrays.asList(c.getNames()).contains("/" + name)) {
                return c.getId();
            }
        }
        return null;
    }

    /** Connects every enabled route's target container to the ingress network. */
    public void connectTargets(IngressConfig config) {
        DockerAPI api = api(config);
        Set<String> connected = new HashSet<>();
        try {
            var network = api.inspectNetwork(config.getIngressNetworkName());
            if (network.getContainers() != null) {
                network.getContainers().values().forEach(c -> connected.add(c.getName()));
            }
        } catch (Exception e) {
            log.debug("Could not inspect ingress network: {}", e.getMessage());
        }
        for (IngressRoute route :
                routeRepository.findByIngressConfigIdAndEnabled(config.getId(), true)) {
            if (route.kind() != IngressRoute.TargetType.CONTAINER) continue;
            connectTarget(api, config, route.getTargetContainerName(), connected);
            if (route.splitsTraffic()) {
                connectTarget(api, config, route.getCanaryContainerName(), connected);
            }
        }
    }

    private void connectTarget(
            DockerAPI api, IngressConfig config, String target, Set<String> connected) {
        if (target == null || target.isEmpty() || connected.contains(target)) return;
        String id = findContainer(api, target);
        if (id == null) return; // not running right now; nginx answers 502 until it is
        try {
            api.connectContainerToNetwork(config.getIngressNetworkName(), id);
            connected.add(target);
        } catch (ConflictException e) {
            connected.add(target);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (!msg.contains("already exists")) {
                log.warn("Could not connect {} to the ingress network: {}", target, msg);
            }
        }
    }

    /** Runs a command in the proxy container (HTTP checks, reading traffic logs). */
    public DockerAPI.ExecOutput execInProxy(
            IngressConfig config, long timeoutSeconds, String... command) {
        return api(config)
                .execFull(
                        config.getNginxContainerId(), Map.of(), timeoutSeconds, 4_000_000, command);
    }

    // --- config ------------------------------------------------------------------------------

    /**
     * Writes the current config, certificates and password files, tests them, and reloads nginx.
     * Throws {@link ApplyException} with nginx's message when the test fails; the proxy then keeps
     * running the previous config.
     */
    public String apply(IngressConfig config) {
        ReentrantLock lock = lock(config);
        lock.lock();
        try {
            ensureProxy(config);
            connectTargets(config);

            List<IngressRoute> routes =
                    routeRepository.findByIngressConfigIdAndEnabled(config.getId(), true);
            Map<String, IngressCertificate> certs = new HashMap<>();
            for (IngressCertificate c :
                    certificateRepository.findByIngressConfigId(config.getId())) {
                IngressCertificate existing = certs.get(c.getHostname());
                // Prefer a usable certificate when a hostname has several rows.
                if (existing == null
                        || (!NginxConfigGenerator.usable(existing)
                                && NginxConfigGenerator.usable(c))) {
                    certs.put(c.getHostname(), c);
                }
            }
            String nginxConf = generator.generateFullConfig(config, routes, certs);

            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put(NEXT, nginxConf.getBytes(StandardCharsets.UTF_8));
            Map<String, byte[]> secrets = new LinkedHashMap<>();
            for (IngressCertificate cert : certs.values()) {
                if (!NginxConfigGenerator.usable(cert)) continue;
                String dir = NginxConfigGenerator.CERT_DIR + "/" + cert.getHostname();
                String chain = cert.getCertificatePem().strip() + "\n";
                if (cert.getChainPem() != null && !cert.getChainPem().isBlank()) {
                    chain += cert.getChainPem().strip() + "\n";
                }
                files.put(dir + "/fullchain.pem", chain.getBytes(StandardCharsets.UTF_8));
                secrets.put(
                        dir + "/privkey.pem",
                        decryptPem(cert.getPrivateKeyPem()).getBytes(StandardCharsets.UTF_8));
            }
            for (IngressRoute route : routes) {
                if (route.isAuthEnabled()
                        && route.getAuthConfig() != null
                        && !route.getAuthConfig().isBlank()) {
                    secrets.put(
                            NginxConfigGenerator.AUTH_DIR + "/" + route.getId() + ".htpasswd",
                            (route.getAuthConfig().strip() + "\n")
                                    .getBytes(StandardCharsets.UTF_8));
                    // Signs sign-in cookies. Derived from the users' password hashes too, so
                    // changing a password signs everyone out of that route.
                    String sessionKey =
                            encryptionService.deriveKey(
                                    "ingress-session|"
                                            + route.getId()
                                            + "|"
                                            + route.getAuthConfig().strip());
                    secrets.put(
                            NginxConfigGenerator.AUTH_DIR + "/" + route.getId() + ".key",
                            (sessionKey + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            files.put(
                    NginxConfigGenerator.ACME_ROOT + "/.well-known/acme-challenge/.keep",
                    new byte[0]);
            files.put(
                    NginxConfigGenerator.NJS_DIR + "/" + NginxConfigGenerator.AUTH_SCRIPT,
                    AUTH_SCRIPT_SOURCE);

            DockerAPI api = api(config);
            String id = config.getNginxContainerId();
            api.putFiles(id, files, 0644);
            if (!secrets.isEmpty()) {
                api.putFiles(id, secrets, 0640);
                // nginx workers run as "nginx"; let them read the keys, nobody else.
                api.exec(
                        id,
                        "chgrp",
                        "-R",
                        "nginx",
                        NginxConfigGenerator.CERT_DIR,
                        NginxConfigGenerator.AUTH_DIR);
            }

            DockerAPI.ExecResult test = api.exec(id, "nginx", "-t", "-q", "-c", NEXT);
            if (!test.ok()) {
                String message = cleanNginxOutput(test.output());
                config.setLastError(message);
                configRepository.save(config);
                throw new ApplyException("nginx rejected the new configuration: " + message);
            }
            api.exec(id, "mv", "-f", NEXT, CONF);
            // nginx is the container's main process; SIGHUP makes it reload gracefully. (Using
            // `nginx -s reload` depends on a pid file that may not match the running master.)
            api.client().killContainerCmd(id).withSignal("HUP").exec();
            config.setCurrentNginxConfig(nginxConf);
            config.setLastError(null);
            configRepository.save(config);
            return nginxConf;
        } finally {
            lock.unlock();
        }
    }

    private static final byte[] AUTH_SCRIPT_SOURCE = loadAuthScript();

    private static byte[] loadAuthScript() {
        try (var in = NginxApplier.class.getResourceAsStream("/ingress/dm_auth.js")) {
            if (in == null) throw new IllegalStateException("ingress/dm_auth.js is missing");
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read ingress/dm_auth.js", e);
        }
    }

    static String cleanNginxOutput(String output) {
        if (output == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\n")) {
            String l = line.replaceFirst("^nginx: ", "").trim();
            if (l.isEmpty() || l.contains("test is successful") || l.contains("syntax is ok"))
                continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(l);
        }
        return sb.length() > 800 ? sb.substring(0, 800) : sb.toString();
    }

    // --- ACME --------------------------------------------------------------------------------

    public void writeChallenge(IngressConfig config, String token, String content) {
        if (!token.matches("^[A-Za-z0-9_-]+$")) throw new IllegalArgumentException("Invalid token");
        ensureProxy(config);
        api(config)
                .putFiles(
                        config.getNginxContainerId(),
                        Map.of(
                                NginxConfigGenerator.ACME_ROOT
                                        + "/.well-known/acme-challenge/"
                                        + token,
                                content.getBytes(StandardCharsets.UTF_8)),
                        0644);
    }

    public void removeChallenge(IngressConfig config, String token) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]+$")) return;
        try {
            api(config)
                    .exec(
                            config.getNginxContainerId(),
                            "rm",
                            "-f",
                            NginxConfigGenerator.ACME_ROOT
                                    + "/.well-known/acme-challenge/"
                                    + token);
        } catch (Exception ignored) {
        }
    }

    // --- keys at rest ------------------------------------------------------------------------

    /** Private keys are stored encrypted; older rows may still hold plain PEM. */
    public String decryptPem(String stored) {
        if (stored == null) return null;
        if (stored.startsWith("-----BEGIN")) return stored;
        return encryptionService.decrypt(stored);
    }

    public String encryptPem(String pem) {
        return pem == null ? null : encryptionService.encrypt(pem);
    }

    // --- self-healing ------------------------------------------------------------------------

    /** The proxy container exists but isn't running. (Missing is a different matter: recreate.) */
    private boolean stoppedOnPurpose(IngressConfig config) {
        try {
            DockerAPI api = api(config);
            String id = findContainer(api, config.getNginxContainerName());
            if (id == null) return false;
            var state = api.inspectContainer(id).getState();
            return !Boolean.TRUE.equals(state.getRunning())
                    && !Boolean.TRUE.equals(state.getRestarting());
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the proxy is serving the config Docker Manager wrote. */
    private boolean runsOurConfig(IngressConfig config) {
        try {
            return api(config)
                    .exec(
                            config.getNginxContainerId(),
                            "grep",
                            "-q",
                            "Managed by Docker Manager",
                            CONF)
                    .ok();
        } catch (Exception e) {
            return true; // can't tell; don't keep re-applying
        }
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void reconcile() {
        for (IngressConfig config : configRepository.findAll()) {
            if (config.getStatus() != IngressConfig.IngressStatus.ENABLED) continue;
            ReentrantLock lock = lock(config);
            if (!lock.tryLock()) continue;
            try {
                // Someone stopped the proxy on purpose (it restarts by itself after reboots, as it
                // runs with restart: unless-stopped). Leave it stopped; "Re-apply configuration"
                // or turning ingress on again starts it.
                if (stoppedOnPurpose(config)) continue;
                boolean recreated = ensureProxy(config);
                connectTargets(config);
                if (recreated) {
                    log.info(
                            "Ingress proxy for host {} was missing and has been recreated",
                            config.getDockerHostId());
                    apply(config);
                } else if (config.getLastError() != null || !runsOurConfig(config)) {
                    // A previous apply failed, or the proxy came back with nginx's stock config
                    // (recreated by hand, restored from a backup...): try again until it's right.
                    apply(config);
                }
            } catch (Exception e) {
                log.warn(
                        "Ingress check for host {} failed: {}",
                        config.getDockerHostId(),
                        e.getMessage());
                config.setLastError(e.getMessage());
                configRepository.save(config);
            } finally {
                lock.unlock();
            }
        }
    }
}
