package com.wannaverse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Container;
import com.wannaverse.persistence.*;
import com.wannaverse.persistence.IngressAuditLog.IngressAction;
import com.wannaverse.persistence.IngressCertificate.CertificateStatus;
import com.wannaverse.persistence.IngressConfig.IngressStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class IngressService {
    private static final Logger log = LoggerFactory.getLogger(IngressService.class);

    public static final String NGINX_IMAGE = "nginx:alpine";
    public static final String NGINX_CONTAINER_PREFIX = "managed-ingress-proxy-";
    public static final String INGRESS_NETWORK_PREFIX = "managed-ingress-network-";

    private final IngressConfigRepository configRepository;
    private final IngressRouteRepository routeRepository;
    private final IngressCertificateRepository certificateRepository;
    private final IngressAuditLogRepository auditLogRepository;
    private final DockerHostRepository hostRepository;
    private final DockerService dockerService;
    private final NginxConfigGenerator nginxConfigGenerator;
    private final ObjectMapper objectMapper;

    public IngressService(
            IngressConfigRepository configRepository,
            IngressRouteRepository routeRepository,
            IngressCertificateRepository certificateRepository,
            IngressAuditLogRepository auditLogRepository,
            DockerHostRepository hostRepository,
            DockerService dockerService,
            NginxConfigGenerator nginxConfigGenerator,
            ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.auditLogRepository = auditLogRepository;
        this.hostRepository = hostRepository;
        this.dockerService = dockerService;
        this.nginxConfigGenerator = nginxConfigGenerator;
        this.objectMapper = objectMapper;
    }

    public Optional<IngressConfig> getIngressConfig(String hostId) {
        return configRepository.findByDockerHostId(hostId);
    }

    public boolean isIngressEnabled(String hostId) {
        return configRepository
                .findByDockerHostId(hostId)
                .map(c -> c.getStatus() == IngressStatus.ENABLED)
                .orElse(false);
    }

    public List<IngressRoute> getRoutes(String hostId) {
        return configRepository
                .findByDockerHostId(hostId)
                .map(c -> routeRepository.findByIngressConfigId(c.getId()))
                .orElse(Collections.emptyList());
    }

    public Optional<IngressRoute> getRoute(String routeId) {
        return routeRepository.findById(routeId);
    }

    public List<IngressCertificate> getCertificates(String hostId) {
        return configRepository
                .findByDockerHostId(hostId)
                .map(c -> certificateRepository.findByIngressConfigId(c.getId()))
                .orElse(Collections.emptyList());
    }

    public List<IngressAuditLog> getAuditLogs(String hostId) {
        return configRepository
                .findByDockerHostId(hostId)
                .map(c -> auditLogRepository.findByIngressConfigIdOrderByTimestampDesc(c.getId()))
                .orElse(Collections.emptyList());
    }

    public EnableIngressPreview previewEnableIngress(String hostId, EnableIngressRequest request) {
        List<String> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String networkName = INGRESS_NETWORK_PREFIX + hostId.substring(0, 8);
        String containerName = NGINX_CONTAINER_PREFIX + hostId.substring(0, 8);

        actions.add("Pull Docker image: " + NGINX_IMAGE);
        actions.add("Create Docker network: " + networkName);
        actions.add("Create nginx container: " + containerName);
        actions.add("Start nginx container");

        if (!hostRepository.existsById(hostId)) {
            warnings.add("Docker host not found: " + hostId);
        }

        if (isIngressEnabled(hostId)) {
            warnings.add("Ingress is already enabled for this host");
        }

        return new EnableIngressPreview(
                actions,
                NGINX_IMAGE,
                networkName,
                containerName,
                request.httpPort(),
                request.httpsPort(),
                warnings);
    }

    @Transactional
    public EnableIngressResult enableIngress(
            String hostId, EnableIngressRequest request, String userId, String username) {
        log.info("Enabling ingress for host {} by user {}", hostId, username);

        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Host not found: " + hostId));

        Optional<IngressConfig> existing = configRepository.findByDockerHostId(hostId);
        if (existing.isPresent() && existing.get().getStatus() == IngressStatus.ENABLED) {
            throw new IllegalStateException("Ingress already enabled for this host");
        }

        IngressConfig config = existing.orElseGet(IngressConfig::new);
        config.setDockerHostId(hostId);
        config.setStatus(IngressStatus.ENABLING);
        config.setHttpPort(request.httpPort());
        config.setHttpsPort(request.httpsPort());
        config.setAcmeProxyPort(request.acmeProxyPort());
        config.setAcmeEmail(request.acmeEmail());
        config.setAcmeEnabled(request.acmeEmail() != null && !request.acmeEmail().isEmpty());
        config.setAcmeDirectoryUrl(
                request.useStaging()
                        ? "https://acme-staging-v02.api.letsencrypt.org/directory"
                        : "https://acme-v02.api.letsencrypt.org/directory");

        String networkName = INGRESS_NETWORK_PREFIX + hostId.substring(0, 8);
        String containerName = NGINX_CONTAINER_PREFIX + hostId.substring(0, 8);
        config.setIngressNetworkName(networkName);
        config.setNginxContainerName(containerName);

        config = configRepository.save(config);

        try {
            DockerAPI api = getDockerAPI(host);

            log.debug("Pulling nginx image: {}", NGINX_IMAGE);
            api.pullImage(NGINX_IMAGE);

            log.debug("Setting up ingress network: {}", networkName);
            String networkId = findOrCreateNetwork(api, networkName);
            config.setIngressNetworkId(networkId);

            removeExistingContainer(api, containerName);

            log.debug("Creating nginx container: {}", containerName);
            Map<Integer, Integer> ports = new HashMap<>();
            ports.put(80, request.httpPort());
            ports.put(443, request.httpsPort());

            List<String> extraHosts = List.of("host.docker.internal:host-gateway");

            var containerResponse =
                    api.createContainer(
                            NGINX_IMAGE,
                            containerName,
                            Collections.emptyList(),
                            ports,
                            Collections.emptyList(),
                            networkName,
                            extraHosts);
            config.setNginxContainerId(containerResponse.getId());

            log.debug("Starting nginx container: {}", containerName);
            api.startContainer(containerResponse.getId());

            config.setStatus(IngressStatus.ENABLED);
            config.setEnabledAt(System.currentTimeMillis());
            config.setEnabledByUserId(userId);
            config.setEnabledByUsername(username);

            String nginxConfig = generateNginxConfig(config);
            config.setCurrentNginxConfig(nginxConfig);

            log.debug("Applying nginx config to container");
            String containerId = containerResponse.getId();

            String base64Config =
                    java.util.Base64.getEncoder().encodeToString(nginxConfig.getBytes());
            api.execCommand(
                    containerId,
                    "sh",
                    "-c",
                    "echo '" + base64Config + "' | base64 -d > /etc/nginx/nginx.conf");

            String testResult = api.execCommand(containerId, "nginx", "-t");
            log.info("Nginx config test: {}", testResult);
            reloadOrStartNginx(api, containerId);

            config = configRepository.save(config);

            auditLogRepository.save(
                    IngressAuditLog.success(
                            config.getId(),
                            IngressAction.INGRESS_ENABLED,
                            "Ingress enabled with nginx container " + containerName,
                            userId,
                            username));

            log.info("Ingress enabled successfully for host {}", hostId);

            return new EnableIngressResult(
                    true,
                    config.getId(),
                    config.getNginxContainerId(),
                    config.getIngressNetworkId(),
                    null);

        } catch (Exception e) {
            log.error("Failed to enable ingress for host {}: {}", hostId, e.getMessage(), e);

            config.setStatus(IngressStatus.ERROR);
            config.setLastError(e.getMessage());
            configRepository.save(config);

            auditLogRepository.save(
                    IngressAuditLog.failure(
                            config.getId(),
                            IngressAction.INGRESS_ERROR,
                            "Failed to enable ingress",
                            e.getMessage(),
                            userId,
                            username));

            try {
                cleanupFailedEnable(host, config);
            } catch (Exception cleanupError) {
                log.warn("Failed to cleanup after enable failure: {}", cleanupError.getMessage());
            }

            return new EnableIngressResult(false, null, null, null, e.getMessage());
        }
    }

    public DisableIngressPreview previewDisableIngress(String hostId) {
        IngressConfig config =
                configRepository
                        .findByDockerHostId(hostId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Ingress not configured for host: " + hostId));

        List<IngressRoute> routes =
                routeRepository.findByIngressConfigIdAndEnabled(config.getId(), true);

        List<RouteImpact> affectedRoutes =
                routes.stream()
                        .map(
                                r ->
                                        new RouteImpact(
                                                r.getId(),
                                                r.getHostname(),
                                                r.getTargetContainerName(),
                                                "Will lose ingress access at "
                                                        + r.getHostname()
                                                        + r.getPathPrefix()))
                        .toList();

        List<String> actions = new ArrayList<>();
        actions.add("Stop nginx container: " + config.getNginxContainerName());
        actions.add("Remove nginx container");
        actions.add("Disconnect all containers from ingress network");
        actions.add("Remove ingress network: " + config.getIngressNetworkName());
        actions.add("Route configurations will be preserved (can re-enable later)");

        String warning =
                affectedRoutes.isEmpty()
                        ? "No active routes will be affected."
                        : affectedRoutes.size() + " route(s) will stop working.";

        return new DisableIngressPreview(affectedRoutes, actions, warning);
    }

    @Transactional
    public DisableIngressResult disableIngress(String hostId, String userId, String username) {
        log.info("Disabling ingress for host {} by user {}", hostId, username);

        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Host not found: " + hostId));

        IngressConfig config =
                configRepository
                        .findByDockerHostId(hostId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Ingress not configured for host: " + hostId));

        if (config.getStatus() == IngressStatus.DISABLED) {
            throw new IllegalStateException("Ingress is already disabled");
        }

        config.setStatus(IngressStatus.DISABLING);
        configRepository.save(config);

        try {
            DockerAPI api = getDockerAPI(host);

            int routesDisabled =
                    routeRepository.countByIngressConfigIdAndEnabled(config.getId(), true);

            if (config.getNginxContainerId() != null) {
                log.debug("Stopping nginx container: {}", config.getNginxContainerId());
                try {
                    api.stopContainer(config.getNginxContainerId());
                } catch (Exception e) {
                    log.warn(
                            "Failed to stop container (may already be stopped): {}",
                            e.getMessage());
                }

                log.debug("Removing nginx container: {}", config.getNginxContainerId());
                try {
                    api.forceRemoveContainer(config.getNginxContainerId());
                } catch (Exception e) {
                    log.warn("Failed to remove container: {}", e.getMessage());
                }
            }

            if (config.getIngressNetworkId() != null) {
                log.debug("Removing ingress network: {}", config.getIngressNetworkId());
                try {
                    api.removeNetwork(config.getIngressNetworkId());
                } catch (Exception e) {
                    log.warn("Failed to remove network: {}", e.getMessage());
                }
            }

            config.setStatus(IngressStatus.DISABLED);
            config.setNginxContainerId(null);
            config.setIngressNetworkId(null);
            config.setDisabledAt(System.currentTimeMillis());
            config.setCurrentNginxConfig(null);
            configRepository.save(config);

            auditLogRepository.save(
                    IngressAuditLog.success(
                            config.getId(),
                            IngressAction.INGRESS_DISABLED,
                            "Ingress disabled. " + routesDisabled + " routes affected.",
                            userId,
                            username));

            log.info("Ingress disabled successfully for host {}", hostId);

            return new DisableIngressResult(true, routesDisabled, null);

        } catch (Exception e) {
            log.error("Failed to disable ingress for host {}: {}", hostId, e.getMessage(), e);

            config.setStatus(IngressStatus.ERROR);
            config.setLastError(e.getMessage());
            configRepository.save(config);

            auditLogRepository.save(
                    IngressAuditLog.failure(
                            config.getId(),
                            IngressAction.INGRESS_ERROR,
                            "Failed to disable ingress",
                            e.getMessage(),
                            userId,
                            username));

            return new DisableIngressResult(false, 0, e.getMessage());
        }
    }

    public ExposeAppPreview previewExposeApp(String hostId, ExposeAppRequest request) {
        IngressConfig config =
                configRepository
                        .findByDockerHostId(hostId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Ingress not configured for host: " + hostId));

        if (config.getStatus() != IngressStatus.ENABLED) {
            throw new IllegalStateException("Ingress is not enabled");
        }

        IngressRoute route = new IngressRoute();
        route.setHostname(request.hostname());
        route.setPathPrefix(request.pathPrefix() != null ? request.pathPrefix() : "/");
        route.setTargetContainerId(request.containerId());
        route.setTargetContainerName(request.containerName());
        route.setTargetPort(request.targetPort());
        route.setProtocol(IngressRoute.Protocol.valueOf(request.protocol()));
        route.setTlsMode(IngressRoute.TlsMode.valueOf(request.tlsMode()));
        route.setForceHttpsRedirect(request.forceHttpsRedirect());

        String nginxBlock = nginxConfigGenerator.generateServerBlockPreview(route, null);

        List<String> warnings = new ArrayList<>();

        if (routeRepository.existsByIngressConfigIdAndHostnameAndPathPrefix(
                config.getId(), request.hostname(), request.pathPrefix())) {
            warnings.add(
                    "A route for " + request.hostname() + request.pathPrefix() + " already exists");
        }

        boolean certificateNeeded = !request.tlsMode().equals("NONE");
        String certificateStatus = "Not needed";

        if (certificateNeeded) {
            switch (request.tlsMode()) {
                case "LETS_ENCRYPT":
                    certificateStatus = "Let's Encrypt (automatic)";
                    warnings.add(
                            "IMPORTANT: DNS A/AAAA record for '"
                                    + request.hostname()
                                    + "' must point to this server's public IP address.");
                    warnings.add(
                            "Port 80 must be accessible from the internet for HTTP-01 challenge.");
                    if (config.getAcmeEmail() == null || config.getAcmeEmail().isEmpty()) {
                        warnings.add(
                                "ACME email not configured. Certificate issuance may fail -"
                                        + " configure email in ingress settings.");
                    }
                    break;
                case "CUSTOM_CERT":
                    certificateStatus = "Custom certificate (upload required)";
                    warnings.add(
                            "You will need to upload a certificate after creating this route.");
                    break;
                case "BRING_YOUR_OWN":
                    certificateStatus = "Self-signed / External";
                    warnings.add(
                            "Route will use self-signed certificate until you provide your own."
                                    + " Browsers will show warnings.");
                    break;
                default:
                    certificateStatus = "Unknown TLS mode";
            }
        }

        return new ExposeAppPreview(
                nginxBlock, null, certificateNeeded, certificateStatus, warnings);
    }

    @Transactional
    public IngressRoute exposeApp(
            String hostId, ExposeAppRequest request, String userId, String username) {
        log.info(
                "Exposing app {} at {} for host {}",
                request.containerName(),
                request.hostname(),
                hostId);

        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Host not found: " + hostId));

        IngressConfig config =
                configRepository
                        .findByDockerHostId(hostId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Ingress not configured for host: " + hostId));

        if (config.getStatus() != IngressStatus.ENABLED) {
            throw new IllegalStateException("Ingress is not enabled");
        }

        if (routeRepository.existsByIngressConfigIdAndHostnameAndPathPrefix(
                config.getId(), request.hostname(), request.pathPrefix())) {
            throw new IllegalArgumentException(
                    "Route already exists for " + request.hostname() + request.pathPrefix());
        }

        IngressRoute route = new IngressRoute();
        route.setIngressConfigId(config.getId());
        route.setHostname(request.hostname());
        route.setPathPrefix(request.pathPrefix() != null ? request.pathPrefix() : "/");
        route.setTargetContainerId(request.containerId());
        route.setTargetContainerName(request.containerName());
        route.setTargetPort(request.targetPort());
        route.setProtocol(IngressRoute.Protocol.valueOf(request.protocol()));
        route.setTlsMode(IngressRoute.TlsMode.valueOf(request.tlsMode()));
        route.setForceHttpsRedirect(request.forceHttpsRedirect());
        route.setEnabled(true);
        route.setCreatedByUserId(userId);
        route.setCreatedByUsername(username);

        IngressCertificate certificate = null;
        IngressRoute.TlsMode tlsMode = route.getTlsMode();
        if (tlsMode != IngressRoute.TlsMode.NONE) {
            certificate = new IngressCertificate();
            certificate.setIngressConfigId(config.getId());
            certificate.setHostname(request.hostname());

            switch (tlsMode) {
                case LETS_ENCRYPT:
                    certificate.setSource(IngressCertificate.CertificateSource.LETS_ENCRYPT);
                    certificate.setStatus(IngressCertificate.CertificateStatus.PENDING);
                    auditLogRepository.save(
                            IngressAuditLog.success(
                                    config.getId(),
                                    IngressAction.CERTIFICATE_REQUESTED,
                                    "Let's Encrypt certificate requested for "
                                            + request.hostname()
                                            + ". Ensure DNS A/AAAA record points to this server and"
                                            + " port 80 is accessible.",
                                    userId,
                                    username));
                    break;
                case CUSTOM_CERT:
                    certificate.setSource(IngressCertificate.CertificateSource.UPLOADED);
                    certificate.setStatus(IngressCertificate.CertificateStatus.PENDING);
                    auditLogRepository.save(
                            IngressAuditLog.success(
                                    config.getId(),
                                    IngressAction.CERTIFICATE_REQUESTED,
                                    "Custom certificate pending upload for " + request.hostname(),
                                    userId,
                                    username));
                    break;
                case BRING_YOUR_OWN:
                    certificate.setSource(IngressCertificate.CertificateSource.EXTERNAL);
                    certificate.setStatus(IngressCertificate.CertificateStatus.PENDING);
                    auditLogRepository.save(
                            IngressAuditLog.success(
                                    config.getId(),
                                    IngressAction.CERTIFICATE_REQUESTED,
                                    "External/self-signed certificate expected for "
                                            + request.hostname(),
                                    userId,
                                    username));
                    break;
                default:
                    break;
            }

            certificate = certificateRepository.save(certificate);
            route.setCertificateId(certificate.getId());
        }

        String nginxBlock = nginxConfigGenerator.generateServerBlockPreview(route, certificate);
        route.setGeneratedNginxBlock(nginxBlock);

        route = routeRepository.save(route);

        try {
            DockerAPI api = getDockerAPI(host);

            log.debug("Connecting container {} to ingress network", request.containerId());
            try {
                api.connectContainerToNetwork(config.getIngressNetworkId(), request.containerId());

                auditLogRepository.save(
                        IngressAuditLog.success(
                                config.getId(),
                                IngressAction.CONTAINER_CONNECTED,
                                "Connected " + request.containerName() + " to ingress network",
                                userId,
                                username));
            } catch (Exception e) {
                log.warn(
                        "Failed to connect container to network (may already be connected): {}",
                        e.getMessage());
            }

            regenerateAndApplyNginxConfig(config, host);

            auditLogRepository.save(
                    IngressAuditLog.success(
                            config.getId(),
                            IngressAction.ROUTE_CREATED,
                            "Created route: "
                                    + request.hostname()
                                    + request.pathPrefix()
                                    + " -> "
                                    + request.containerName()
                                    + ":"
                                    + request.targetPort(),
                            userId,
                            username));

            log.info(
                    "App exposed successfully: {} at {}",
                    request.containerName(),
                    request.hostname());

            return route;

        } catch (Exception e) {
            log.error("Failed to expose app: {}", e.getMessage(), e);

            routeRepository.delete(route);

            auditLogRepository.save(
                    IngressAuditLog.failure(
                            config.getId(),
                            IngressAction.ROUTE_CREATED,
                            "Failed to create route for " + request.hostname(),
                            e.getMessage(),
                            userId,
                            username));

            throw new RuntimeException("Failed to expose app: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void removeRoute(
            String routeId, boolean disconnectFromNetwork, String userId, String username) {
        log.info("Removing route {} by user {}", routeId, username);

        IngressRoute route =
                routeRepository
                        .findById(routeId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Route not found: " + routeId));

        IngressConfig config =
                configRepository
                        .findById(route.getIngressConfigId())
                        .orElseThrow(() -> new IllegalStateException("Config not found for route"));

        DockerHost host =
                hostRepository
                        .findById(config.getDockerHostId())
                        .orElseThrow(() -> new IllegalStateException("Host not found for config"));

        try {
            if (disconnectFromNetwork && config.getStatus() == IngressStatus.ENABLED) {
                DockerAPI api = getDockerAPI(host);

                List<IngressRoute> containerRoutes =
                        routeRepository.findByTargetContainerId(route.getTargetContainerId());
                if (containerRoutes.size() == 1) {
                    log.debug(
                            "Disconnecting container {} from ingress network",
                            route.getTargetContainerId());
                    try {
                        api.disconnectContainerFromNetwork(
                                config.getIngressNetworkId(), route.getTargetContainerId());

                        auditLogRepository.save(
                                IngressAuditLog.success(
                                        config.getId(),
                                        IngressAction.CONTAINER_DISCONNECTED,
                                        "Disconnected "
                                                + route.getTargetContainerName()
                                                + " from ingress network",
                                        userId,
                                        username));
                    } catch (Exception e) {
                        log.warn("Failed to disconnect container: {}", e.getMessage());
                    }
                }
            }

            routeRepository.delete(route);

            if (config.getStatus() == IngressStatus.ENABLED) {
                regenerateAndApplyNginxConfig(config, host);
            }

            auditLogRepository.save(
                    IngressAuditLog.success(
                            config.getId(),
                            IngressAction.ROUTE_DELETED,
                            "Deleted route: " + route.getHostname() + route.getPathPrefix(),
                            userId,
                            username));

            log.info("Route removed successfully: {}", routeId);

        } catch (Exception e) {
            log.error("Failed to remove route: {}", e.getMessage(), e);

            auditLogRepository.save(
                    IngressAuditLog.failure(
                            config.getId(),
                            IngressAction.ROUTE_DELETED,
                            "Failed to delete route " + route.getHostname(),
                            e.getMessage(),
                            userId,
                            username));

            throw new RuntimeException("Failed to remove route: " + e.getMessage(), e);
        }
    }

    public String getNginxConfig(String hostId) {
        return configRepository
                .findByDockerHostId(hostId)
                .map(IngressConfig::getCurrentNginxConfig)
                .orElse(null);
    }

    public NginxStatus getNginxStatus(String hostId) {
        IngressConfig config = configRepository.findByDockerHostId(hostId).orElse(null);

        if (config == null) {
            return new NginxStatus("not_configured", null, null, 0, 0);
        }

        if (config.getStatus() != IngressStatus.ENABLED) {
            return new NginxStatus(config.getStatus().name().toLowerCase(), null, null, 0, 0);
        }

        DockerHost host = hostRepository.findById(hostId).orElse(null);
        if (host == null) {
            return new NginxStatus("host_not_found", null, null, 0, 0);
        }

        try {
            DockerAPI api = getDockerAPI(host);
            var containers = api.listAllContainers();

            String containerStatus = "unknown";
            for (Container c : containers) {
                if (c.getId().equals(config.getNginxContainerId())) {
                    containerStatus = c.getState();
                    break;
                }
            }

            int routeCount = routeRepository.countByIngressConfigIdAndEnabled(config.getId(), true);
            int certCount =
                    certificateRepository.countByIngressConfigIdAndStatus(
                            config.getId(), CertificateStatus.ACTIVE);

            return new NginxStatus(
                    containerStatus,
                    config.getNginxContainerId(),
                    config.getNginxContainerName(),
                    routeCount,
                    certCount);

        } catch (Exception e) {
            log.warn("Failed to get nginx status: {}", e.getMessage());
            return new NginxStatus(
                    "error", config.getNginxContainerId(), config.getNginxContainerName(), 0, 0);
        }
    }

    @Transactional
    public void regenerateNginxConfig(String hostId, String userId, String username) {
        log.info("Regenerating nginx config for host {} by user {}", hostId, username);

        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Host not found: " + hostId));

        IngressConfig config =
                configRepository
                        .findByDockerHostId(hostId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Ingress not configured for host: " + hostId));

        if (config.getStatus() != IngressStatus.ENABLED) {
            throw new IllegalStateException("Ingress is not enabled");
        }

        regenerateAndApplyNginxConfig(config, host);

        auditLogRepository.save(
                IngressAuditLog.success(
                        config.getId(),
                        IngressAction.NGINX_CONFIG_UPDATED,
                        "Nginx configuration regenerated",
                        userId,
                        username));
    }

    private DockerAPI getDockerAPI(DockerHost host) {
        return dockerService.dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()));
    }

    private void reloadOrStartNginx(DockerAPI api, String containerId) {
        log.info("Sending SIGHUP to nginx master process (PID 1)");
        String reloadResult = api.execCommand(containerId, "kill", "-HUP", "1");
        log.info(
                "Nginx reload result: {}",
                reloadResult.isEmpty() ? "(success - no output)" : reloadResult);
    }

    private String findOrCreateNetwork(DockerAPI api, String networkName) {
        var networks = api.listNetworks();
        for (var network : networks) {
            if (networkName.equals(network.getName())) {
                log.debug("Reusing existing network: {} ({})", networkName, network.getId());
                return network.getId();
            }
        }

        log.debug("Creating new network: {}", networkName);
        var response = api.createNetwork(networkName);
        return response.getId();
    }

    private void removeExistingContainer(DockerAPI api, String containerName) {
        var containers = api.listAllContainers();
        for (var container : containers) {
            var names = container.getNames();
            if (names != null) {
                for (var name : names) {
                    if (name.equals("/" + containerName) || name.equals(containerName)) {
                        log.debug(
                                "Removing existing container: {} ({})",
                                containerName,
                                container.getId());
                        try {
                            api.forceRemoveContainer(container.getId());
                        } catch (Exception e) {
                            log.warn("Failed to remove existing container: {}", e.getMessage());
                        }
                        return;
                    }
                }
            }
        }
    }

    private String generateNginxConfig(IngressConfig config) {
        List<IngressRoute> routes =
                routeRepository.findByIngressConfigIdAndEnabled(config.getId(), true);
        List<IngressCertificate> certs =
                certificateRepository.findByIngressConfigId(config.getId());

        Map<String, IngressCertificate> certMap =
                certs.stream()
                        .collect(
                                Collectors.toMap(
                                        IngressCertificate::getHostname, c -> c, (a, b) -> a));

        return nginxConfigGenerator.generateFullConfig(config, routes, certMap);
    }

    private void regenerateAndApplyNginxConfig(IngressConfig config, DockerHost host) {
        String nginxConfig = generateNginxConfig(config);
        config.setCurrentNginxConfig(nginxConfig);
        configRepository.save(config);

        if (config.getNginxContainerId() != null) {
            try {
                DockerAPI api = getDockerAPI(host);
                String containerId = config.getNginxContainerId();

                String base64Config =
                        java.util.Base64.getEncoder().encodeToString(nginxConfig.getBytes());
                String writeResult =
                        api.execCommand(
                                containerId,
                                "sh",
                                "-c",
                                "echo '" + base64Config + "' | base64 -d > /etc/nginx/nginx.conf");
                log.debug("Nginx config write result: {}", writeResult);

                String testResult = api.execCommand(containerId, "nginx", "-t");
                log.info("Nginx config test: {}", testResult);

                reloadOrStartNginx(api, containerId);

                log.info("Nginx config applied and reloaded successfully");

            } catch (Exception e) {
                log.error("Failed to apply nginx config: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to apply nginx config: " + e.getMessage(), e);
            }
        } else {
            log.warn("No nginx container ID, config stored but not applied");
        }
    }

    private void cleanupFailedEnable(DockerHost host, IngressConfig config) {
        DockerAPI api = getDockerAPI(host);

        if (config.getNginxContainerId() != null) {
            try {
                api.stopContainer(config.getNginxContainerId());
            } catch (Exception ignored) {
            }
            try {
                api.forceRemoveContainer(config.getNginxContainerId());
            } catch (Exception ignored) {
            }
        }

        if (config.getIngressNetworkId() != null) {
            try {
                api.removeNetwork(config.getIngressNetworkId());
            } catch (Exception ignored) {
            }
        }
    }

    public record EnableIngressRequest(
            int httpPort, int httpsPort, int acmeProxyPort, String acmeEmail, boolean useStaging) {
        public EnableIngressRequest {
            if (httpPort <= 0) httpPort = 80;
            if (httpsPort <= 0) httpsPort = 443;
            if (acmeProxyPort <= 0) acmeProxyPort = 8080;
        }
    }

    public record EnableIngressPreview(
            List<String> actionsToPerform,
            String nginxImage,
            String networkName,
            String containerName,
            int httpPort,
            int httpsPort,
            List<String> warnings) {}

    public record EnableIngressResult(
            boolean success,
            String configId,
            String nginxContainerId,
            String networkId,
            String error) {}

    public record DisableIngressPreview(
            List<RouteImpact> affectedRoutes,
            List<String> actionsToPerform,
            String warningMessage) {}

    public record RouteImpact(
            String routeId, String hostname, String containerName, String impact) {}

    public record DisableIngressResult(boolean success, int routesDisabled, String error) {}

    public record ExposeAppRequest(
            String containerId,
            String containerName,
            String hostname,
            String pathPrefix,
            int targetPort,
            String protocol,
            String tlsMode,
            String certificateId,
            boolean authEnabled,
            String authType,
            String authConfig,
            boolean forceHttpsRedirect) {}

    public record ExposeAppPreview(
            String proposedNginxBlock,
            String fullConfigDiff,
            boolean certificateNeeded,
            String certificateStatus,
            List<String> warnings) {}

    public record NginxStatus(
            String containerStatus,
            String containerId,
            String containerName,
            int activeRoutes,
            int activeCertificates) {}
}
