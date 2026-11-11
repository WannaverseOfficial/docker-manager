package com.wannaverse.service;

import com.wannaverse.persistence.*;
import com.wannaverse.persistence.IngressAuditLog.IngressAction;
import com.wannaverse.persistence.IngressCertificate.CertificateStatus;
import com.wannaverse.persistence.IngressConfig.IngressStatus;
import com.wannaverse.security.InputValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Ingress: an nginx reverse proxy per Docker host that publishes containers on hostnames, with
 * automatic Let's Encrypt certificates.
 *
 * <p>Docker work (pulling, creating containers, writing config) is not wrapped in database
 * transactions; with SQLite's single connection those would block every other request while images
 * download.
 */
@Service
public class IngressService {
    private static final Logger log = LoggerFactory.getLogger(IngressService.class);

    public static final String NGINX_IMAGE = NginxApplier.NGINX_IMAGE;
    public static final String NGINX_CONTAINER_PREFIX = "managed-ingress-proxy-";
    public static final String INGRESS_NETWORK_PREFIX = "managed-ingress-network-";

    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9._~/-]*$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@;{}]+@[^\\s@;{}]+\\.[^\\s@;{}]+$");
    private static final Pattern BASIC_USER = Pattern.compile("^[A-Za-z0-9._@-]{1,64}$");

    private final IngressConfigRepository configRepository;
    private final IngressRouteRepository routeRepository;
    private final IngressCertificateRepository certificateRepository;
    private final IngressAuditLogRepository auditLogRepository;
    private final DockerHostRepository hostRepository;
    private final NginxConfigGenerator nginxConfigGenerator;
    private final NginxApplier applier;
    private final CertificateService certificateService;
    private final PermissionService permissionService;

    public IngressService(
            IngressConfigRepository configRepository,
            IngressRouteRepository routeRepository,
            IngressCertificateRepository certificateRepository,
            IngressAuditLogRepository auditLogRepository,
            DockerHostRepository hostRepository,
            NginxConfigGenerator nginxConfigGenerator,
            NginxApplier applier,
            @Lazy CertificateService certificateService,
            PermissionService permissionService) {
        this.configRepository = configRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.auditLogRepository = auditLogRepository;
        this.hostRepository = hostRepository;
        this.nginxConfigGenerator = nginxConfigGenerator;
        this.applier = applier;
        this.certificateService = certificateService;
        this.permissionService = permissionService;
    }

    // --- reads -------------------------------------------------------------------------------

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

    public String getNginxConfig(String hostId) {
        return configRepository
                .findByDockerHostId(hostId)
                .map(IngressConfig::getCurrentNginxConfig)
                .orElse(null);
    }

    public NginxStatus getNginxStatus(String hostId) {
        IngressConfig config = configRepository.findByDockerHostId(hostId).orElse(null);
        if (config == null) return new NginxStatus("not_configured", null, null, 0, 0, null);
        if (config.getStatus() != IngressStatus.ENABLED) {
            return new NginxStatus(
                    config.getStatus().name().toLowerCase(Locale.ROOT),
                    null,
                    null,
                    0,
                    0,
                    config.getLastError());
        }
        String state = "unknown";
        try {
            DockerAPI api = applier.api(config);
            String id = NginxApplier.findContainer(api, config.getNginxContainerName());
            state = id == null ? "missing" : api.inspectContainer(id).getState().getStatus();
        } catch (Exception e) {
            state = "unreachable";
        }
        int routes = routeRepository.countByIngressConfigIdAndEnabled(config.getId(), true);
        int certs =
                (int)
                        certificateRepository.findByIngressConfigId(config.getId()).stream()
                                .filter(NginxConfigGenerator::usable)
                                .count();
        return new NginxStatus(
                state,
                config.getNginxContainerId(),
                config.getNginxContainerName(),
                routes,
                certs,
                config.getLastError());
    }

    // --- enable / disable --------------------------------------------------------------------

    public EnableIngressPreview previewEnableIngress(String hostId, EnableIngressRequest request) {
        validatePorts(request);
        List<String> warnings = new ArrayList<>();
        if (!hostRepository.existsById(hostId)) warnings.add("Docker host not found");
        if (isIngressEnabled(hostId)) warnings.add("Ingress is already enabled for this host");
        if (request.acmeEmail() == null || request.acmeEmail().isBlank()) {
            warnings.add(
                    "No email for Let's Encrypt. Certificates still work, but you won't get expiry"
                            + " warnings from Let's Encrypt.");
        }
        String suffix = hostId.substring(0, 8);
        return new EnableIngressPreview(
                List.of(
                        "Pull " + NGINX_IMAGE,
                        "Create the network " + INGRESS_NETWORK_PREFIX + suffix,
                        "Create and start the proxy container "
                                + NGINX_CONTAINER_PREFIX
                                + suffix
                                + " on ports "
                                + request.httpPort()
                                + " and "
                                + request.httpsPort()),
                NGINX_IMAGE,
                INGRESS_NETWORK_PREFIX + suffix,
                NGINX_CONTAINER_PREFIX + suffix,
                request.httpPort(),
                request.httpsPort(),
                warnings);
    }

    public EnableIngressResult enableIngress(
            String hostId, EnableIngressRequest request, String userId, String username) {
        validatePorts(request);
        if (request.acmeEmail() != null
                && !request.acmeEmail().isBlank()
                && !EMAIL.matcher(request.acmeEmail().trim()).matches()) {
            throw bad("Enter a valid email address for Let's Encrypt");
        }
        hostRepository
                .findById(hostId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Host not found"));

        IngressConfig config =
                configRepository.findByDockerHostId(hostId).orElseGet(IngressConfig::new);
        if (config.getStatus() == IngressStatus.ENABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ingress is already enabled");
        }
        String suffix = hostId.substring(0, 8);
        config.setDockerHostId(hostId);
        config.setStatus(IngressStatus.ENABLING);
        config.setHttpPort(request.httpPort());
        config.setHttpsPort(request.httpsPort());
        config.setAcmeEmail(blankToNull(request.acmeEmail()));
        config.setAcmeEnabled(true);
        config.setAcmeDirectoryUrl(
                request.useStaging()
                        ? "https://acme-staging-v02.api.letsencrypt.org/directory"
                        : "https://acme-v02.api.letsencrypt.org/directory");
        config.setIngressNetworkName(INGRESS_NETWORK_PREFIX + suffix);
        config.setNginxContainerName(NGINX_CONTAINER_PREFIX + suffix);
        config.setLastError(null);
        config = configRepository.save(config);

        try {
            // A leftover container from an earlier attempt may have the wrong ports.
            DockerAPI api = applier.api(config);
            String old = NginxApplier.findContainer(api, config.getNginxContainerName());
            if (old != null) api.forceRemoveContainer(old);

            applier.apply(config);
            config.setStatus(IngressStatus.ENABLED);
            config.setEnabledAt(System.currentTimeMillis());
            config.setEnabledByUserId(userId);
            config.setEnabledByUsername(username);
            config = configRepository.save(config);
            audit(config, IngressAction.INGRESS_ENABLED, "Ingress enabled", userId, username);
            return new EnableIngressResult(
                    true,
                    config.getId(),
                    config.getNginxContainerId(),
                    config.getIngressNetworkId(),
                    null);
        } catch (Exception e) {
            log.error("Enabling ingress for {} failed: {}", hostId, e.getMessage());
            config.setStatus(IngressStatus.ERROR);
            config.setLastError(e.getMessage());
            configRepository.save(config);
            auditLogRepository.save(
                    IngressAuditLog.failure(
                            config.getId(),
                            IngressAction.INGRESS_ERROR,
                            "Enabling ingress failed",
                            e.getMessage(),
                            userId,
                            username));
            try {
                DockerAPI api = applier.api(config);
                String id = NginxApplier.findContainer(api, config.getNginxContainerName());
                if (id != null) api.forceRemoveContainer(id);
            } catch (Exception ignored) {
            }
            return new EnableIngressResult(false, null, null, null, e.getMessage());
        }
    }

    /** Changes the Let's Encrypt email or environment of an enabled ingress. */
    public IngressConfig updateSettings(
            String hostId, String acmeEmail, Boolean useStaging, String userId, String username) {
        IngressConfig config = requireConfig(hostId);
        if (acmeEmail != null) {
            if (!acmeEmail.isBlank() && !EMAIL.matcher(acmeEmail.trim()).matches()) {
                throw bad("Enter a valid email address");
            }
            config.setAcmeEmail(blankToNull(acmeEmail));
        }
        if (useStaging != null) {
            config.setAcmeDirectoryUrl(
                    useStaging
                            ? "https://acme-staging-v02.api.letsencrypt.org/directory"
                            : "https://acme-v02.api.letsencrypt.org/directory");
            config.setAcmeAccountKeyPem(null); // accounts are per directory
        }
        config = configRepository.save(config);
        audit(
                config,
                IngressAction.NGINX_CONFIG_UPDATED,
                "Ingress settings updated",
                userId,
                username);
        return config;
    }

    public DisableIngressPreview previewDisableIngress(String hostId) {
        IngressConfig config = requireConfig(hostId);
        List<RouteImpact> affected =
                routeRepository.findByIngressConfigIdAndEnabled(config.getId(), true).stream()
                        .map(
                                r ->
                                        new RouteImpact(
                                                r.getId(),
                                                r.getHostname(),
                                                r.getTargetContainerName(),
                                                r.getHostname()
                                                        + NginxConfigGenerator.path(r)
                                                        + " will stop answering"))
                        .toList();
        return new DisableIngressPreview(
                affected,
                List.of(
                        "Stop and remove the proxy container " + config.getNginxContainerName(),
                        "Disconnect containers from "
                                + config.getIngressNetworkName()
                                + " and remove it",
                        "Keep routes and certificates, so turning ingress back on restores them"),
                affected.isEmpty()
                        ? "No active routes will be affected."
                        : affected.size() + " route(s) will stop working.");
    }

    public DisableIngressResult disableIngress(String hostId, String userId, String username) {
        IngressConfig config = requireConfig(hostId);
        if (config.getStatus() == IngressStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ingress is already off");
        }
        config.setStatus(IngressStatus.DISABLING);
        configRepository.save(config);
        int routes = routeRepository.countByIngressConfigIdAndEnabled(config.getId(), true);
        try {
            DockerAPI api = applier.api(config);
            String id = NginxApplier.findContainer(api, config.getNginxContainerName());
            if (id != null) api.forceRemoveContainer(id);
            try {
                var network = api.inspectNetwork(config.getIngressNetworkName());
                if (network.getContainers() != null) {
                    for (String containerId : network.getContainers().keySet()) {
                        try {
                            api.disconnectContainerFromNetwork(
                                    config.getIngressNetworkName(), containerId);
                        } catch (Exception ignored) {
                        }
                    }
                }
                api.removeNetwork(config.getIngressNetworkName());
            } catch (Exception e) {
                log.debug("Ingress network already gone: {}", e.getMessage());
            }
            config.setStatus(IngressStatus.DISABLED);
            config.setNginxContainerId(null);
            config.setIngressNetworkId(null);
            config.setDisabledAt(System.currentTimeMillis());
            config.setCurrentNginxConfig(null);
            config.setLastError(null);
            configRepository.save(config);
            audit(
                    config,
                    IngressAction.INGRESS_DISABLED,
                    "Ingress disabled; " + routes + " route(s) paused",
                    userId,
                    username);
            return new DisableIngressResult(true, routes, null);
        } catch (Exception e) {
            config.setStatus(IngressStatus.ERROR);
            config.setLastError(e.getMessage());
            configRepository.save(config);
            return new DisableIngressResult(false, 0, e.getMessage());
        }
    }

    // --- routes ------------------------------------------------------------------------------

    public ExposeAppPreview previewExposeApp(String hostId, ExposeAppRequest request) {
        IngressConfig config = requireEnabled(hostId);
        IngressRoute route = toRoute(request, config, null);
        List<String> warnings = new ArrayList<>();
        if (routeRepository.existsByIngressConfigIdAndHostnameAndPathPrefix(
                config.getId(), route.getHostname(), route.getPathPrefix())) {
            warnings.add(
                    "A route for "
                            + route.getHostname()
                            + route.getPathPrefix()
                            + " already exists");
        }
        String certStatus = "Not needed";
        if (route.getTlsMode() == IngressRoute.TlsMode.LETS_ENCRYPT) {
            certStatus = "Let's Encrypt, requested automatically";
            warnings.add(
                    "The DNS record for " + route.getHostname() + " must point at this server.");
            warnings.add(
                    "Port "
                            + config.getHttpPort()
                            + " must be reachable from the internet (Let's Encrypt connects on port"
                            + " 80).");
        } else if (route.getTlsMode() != IngressRoute.TlsMode.NONE) {
            certStatus = "Upload a certificate after creating the route";
        }
        return new ExposeAppPreview(
                nginxConfigGenerator.generateServerBlockPreview(route, null),
                null,
                route.getTlsMode() != IngressRoute.TlsMode.NONE,
                certStatus,
                warnings);
    }

    public IngressRoute exposeApp(
            String hostId, ExposeAppRequest request, String userId, String username) {
        IngressConfig config = requireEnabled(hostId);
        IngressRoute route = toRoute(request, config, null);
        requireHostnameOwner(config, route.getHostname(), null);
        if (routeRepository.existsByIngressConfigIdAndHostnameAndPathPrefix(
                config.getId(), route.getHostname(), route.getPathPrefix())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A route for "
                            + route.getHostname()
                            + route.getPathPrefix()
                            + " already exists");
        }
        resolveTarget(config, route);
        route.setIngressConfigId(config.getId());
        route.setEnabled(true);
        route.setCreatedByUserId(userId);
        route.setCreatedByUsername(username);
        route = routeRepository.save(route);

        IngressCertificate cert = ensureCertificateRow(config, route);
        if (cert != null) {
            route.setCertificateId(cert.getId());
            route = routeRepository.save(route);
        }

        try {
            applier.apply(config);
        } catch (Exception e) {
            routeRepository.delete(route);
            try {
                applier.apply(config);
            } catch (Exception ignored) {
            }
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Could not add the route: " + e.getMessage());
        }
        audit(
                config,
                IngressAction.ROUTE_CREATED,
                "Route "
                        + route.getHostname()
                        + route.getPathPrefix()
                        + " -> "
                        + route.getTargetContainerName()
                        + ":"
                        + route.getTargetPort(),
                userId,
                username);

        requestCertificateIfNeeded(cert, route, userId, username);
        return route;
    }

    public IngressRoute updateRoute(
            String routeId,
            ExposeAppRequest request,
            Boolean enabled,
            String userId,
            String username) {
        IngressRoute route =
                routeRepository
                        .findById(routeId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Route not found"));
        IngressConfig config =
                configRepository
                        .findById(route.getIngressConfigId())
                        .orElseThrow(() -> new IllegalStateException("Ingress not found"));
        IngressRoute before = copy(route);

        if (request != null) {
            IngressRoute updated = toRoute(request, config, route.getAuthConfig());
            requireHostnameOwner(config, updated.getHostname(), route.getId());
            boolean moved =
                    !updated.getHostname().equals(route.getHostname())
                            || !updated.getPathPrefix().equals(route.getPathPrefix());
            if (moved
                    && routeRepository.existsByIngressConfigIdAndHostnameAndPathPrefix(
                            config.getId(), updated.getHostname(), updated.getPathPrefix())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Another route already uses that address");
            }
            route.setHostname(updated.getHostname());
            route.setPathPrefix(updated.getPathPrefix());
            route.setTargetContainerName(updated.getTargetContainerName());
            resolveTarget(config, route);
            route.setTargetPort(updated.getTargetPort());
            route.setProtocol(updated.getProtocol());
            route.setTlsMode(updated.getTlsMode());
            route.setForceHttpsRedirect(updated.isForceHttpsRedirect());
            route.setAuthEnabled(updated.isAuthEnabled());
            route.setAuthType(updated.getAuthType());
            // Keep the existing password when the form leaves it empty.
            if (updated.getAuthConfig() != null) route.setAuthConfig(updated.getAuthConfig());
            if (!route.isAuthEnabled()) route.setAuthConfig(null);
        }
        if (enabled != null) route.setEnabled(enabled);
        if (route.isAuthEnabled()
                && (route.getAuthConfig() == null || route.getAuthConfig().isBlank())) {
            throw bad("Set a username and password for basic authentication");
        }
        route = routeRepository.save(route);
        IngressCertificate cert = ensureCertificateRow(config, route);
        if (cert != null && !cert.getId().equals(route.getCertificateId())) {
            route.setCertificateId(cert.getId());
            route = routeRepository.save(route);
        }

        if (config.getStatus() == IngressStatus.ENABLED) {
            try {
                applier.apply(config);
            } catch (Exception e) {
                routeRepository.save(before);
                try {
                    applier.apply(config);
                } catch (Exception ignored) {
                }
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Could not update the route: " + e.getMessage());
            }
        }
        audit(
                config,
                IngressAction.ROUTE_UPDATED,
                "Route " + route.getHostname() + route.getPathPrefix() + " updated",
                userId,
                username);
        requestCertificateIfNeeded(cert, route, userId, username);
        return route;
    }

    public void removeRoute(
            String routeId, boolean disconnectFromNetwork, String userId, String username) {
        IngressRoute route =
                routeRepository
                        .findById(routeId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Route not found"));
        IngressConfig config =
                configRepository
                        .findById(route.getIngressConfigId())
                        .orElseThrow(() -> new IllegalStateException("Ingress not found"));
        routeRepository.delete(route);

        if (config.getStatus() == IngressStatus.ENABLED) {
            if (disconnectFromNetwork) {
                boolean stillUsed =
                        routeRepository.findByIngressConfigId(config.getId()).stream()
                                .anyMatch(
                                        r ->
                                                Objects.equals(
                                                        r.getTargetContainerName(),
                                                        route.getTargetContainerName()));
                if (!stillUsed) {
                    try {
                        DockerAPI api = applier.api(config);
                        String id = NginxApplier.findContainer(api, route.getTargetContainerName());
                        if (id != null)
                            api.disconnectContainerFromNetwork(config.getIngressNetworkName(), id);
                    } catch (Exception e) {
                        log.debug(
                                "Could not disconnect {}: {}",
                                route.getTargetContainerName(),
                                e.getMessage());
                    }
                }
            }
            applier.apply(config);
        }
        audit(
                config,
                IngressAction.ROUTE_DELETED,
                "Route " + route.getHostname() + route.getPathPrefix() + " removed",
                userId,
                username);
    }

    public void regenerateNginxConfig(String hostId, String userId, String username) {
        IngressConfig config = requireEnabled(hostId);
        applier.apply(config);
        audit(config, IngressAction.NGINX_RELOADED, "Configuration re-applied", userId, username);
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Finds the target container by name; routes follow the name, so redeploys keep working. */
    /**
     * A hostname belongs to whoever made its first route. Others (unless admins) can't add a path
     * to it: a longer path such as /api/auth/login would take that part of someone else's site.
     */
    private void requireHostnameOwner(IngressConfig config, String hostname, String exceptRouteId) {
        var ctx = com.wannaverse.security.SecurityContextHolder.getContext();
        if (ctx == null || ctx.isAdmin()) return;
        for (IngressRoute other : routeRepository.findByIngressConfigId(config.getId())) {
            if (other.getId().equals(exceptRouteId) || !other.getHostname().equals(hostname))
                continue;
            if (!ctx.getUserId().equals(other.getCreatedByUserId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        hostname
                                + " already has routes made by someone else. Ask an"
                                + " administrator.");
            }
        }
    }

    private void resolveTarget(IngressConfig config, IngressRoute route) {
        String id = NginxApplier.findContainer(applier.api(config), route.getTargetContainerName());
        if (id == null) {
            throw bad("No container named '" + route.getTargetContainerName() + "' on this host");
        }
        // A route publishes the container to the network, so the user must have access to it.
        var ctx = com.wannaverse.security.SecurityContextHolder.getContext();
        if (ctx != null && !ctx.isAdmin()) {
            String hostId = config.getDockerHostId();
            String name = route.getTargetContainerName();
            boolean allowed =
                    permissionService.hasPermission(
                                    ctx.getUserId(), Resource.CONTAINERS, "inspect", hostId, name)
                            || permissionService.hasPermission(
                                    ctx.getUserId(), Resource.CONTAINERS, "inspect", hostId, id)
                            || permissionService.hasPermission(
                                    ctx.getUserId(),
                                    Resource.CONTAINERS,
                                    "inspect",
                                    hostId,
                                    id.substring(0, 12));
            if (!allowed) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "You don't have access to the container '" + name + "'");
            }
        }
        route.setTargetContainerId(id);
    }

    private IngressCertificate ensureCertificateRow(IngressConfig config, IngressRoute route) {
        if (route.getTlsMode() == IngressRoute.TlsMode.NONE) return null;
        Optional<IngressCertificate> existing =
                certificateRepository.findByIngressConfigIdAndHostname(
                        config.getId(), route.getHostname());
        if (existing.isPresent()) return existing.get();
        IngressCertificate cert = new IngressCertificate();
        cert.setIngressConfigId(config.getId());
        cert.setHostname(route.getHostname());
        cert.setSource(
                route.getTlsMode() == IngressRoute.TlsMode.LETS_ENCRYPT
                        ? IngressCertificate.CertificateSource.LETS_ENCRYPT
                        : IngressCertificate.CertificateSource.UPLOADED);
        cert.setStatus(CertificateStatus.PENDING);
        cert.setStatusMessage(
                route.getTlsMode() == IngressRoute.TlsMode.LETS_ENCRYPT
                        ? "Waiting to request a certificate"
                        : "Upload a certificate for this hostname");
        return certificateRepository.save(cert);
    }

    private void requestCertificateIfNeeded(
            IngressCertificate cert, IngressRoute route, String userId, String username) {
        if (cert == null
                || route.getTlsMode() != IngressRoute.TlsMode.LETS_ENCRYPT
                || cert.getSource() != IngressCertificate.CertificateSource.LETS_ENCRYPT
                || NginxConfigGenerator.usable(cert)
                || !route.isEnabled()) {
            return;
        }
        // Previously creating a route only created a PENDING certificate and nothing ever
        // requested it.
        certificateService.requestLetsEncryptCertificate(cert.getId(), userId, username);
    }

    /** Validates a route request; everything here ends up inside nginx.conf. */
    private IngressRoute toRoute(
            ExposeAppRequest request, IngressConfig config, String existingAuthConfig) {
        String hostname =
                request.hostname() == null
                        ? ""
                        : request.hostname().trim().toLowerCase(Locale.ROOT);
        if (!InputValidator.isValidHostname(hostname) || !hostname.contains(".")) {
            throw bad("Enter a full hostname, for example app.example.com");
        }
        String path =
                request.pathPrefix() == null || request.pathPrefix().isBlank()
                        ? "/"
                        : request.pathPrefix().trim();
        if (!path.startsWith("/")) path = "/" + path;
        if (!PATH.matcher(path).matches() || path.contains("..") || path.contains("//")) {
            throw bad("The path may only use letters, digits and - . _ ~ /");
        }
        String container =
                request.containerName() == null
                        ? ""
                        : request.containerName().trim().replaceFirst("^/", "");
        if (!InputValidator.isValidContainerName(container)) throw bad("Choose a container");
        if (request.targetPort() < 1 || request.targetPort() > 65535)
            throw bad("The port must be between 1 and 65535");

        IngressRoute.Protocol protocol;
        IngressRoute.TlsMode tls;
        try {
            protocol =
                    IngressRoute.Protocol.valueOf(
                            request.protocol() == null ? "HTTP" : request.protocol());
            tls =
                    IngressRoute.TlsMode.valueOf(
                            request.tlsMode() == null ? "NONE" : request.tlsMode());
        } catch (IllegalArgumentException e) {
            throw bad("Unknown protocol or TLS mode");
        }

        IngressRoute route = new IngressRoute();
        route.setHostname(hostname);
        route.setPathPrefix(path);
        route.setTargetContainerId(request.containerId());
        route.setTargetContainerName(container);
        route.setTargetPort(request.targetPort());
        route.setProtocol(protocol);
        route.setTlsMode(tls);
        route.setForceHttpsRedirect(
                request.forceHttpsRedirect() && tls != IngressRoute.TlsMode.NONE);
        route.setAuthEnabled(request.authEnabled());
        if (request.authEnabled()) {
            route.setAuthType(IngressRoute.AuthType.BASIC);
            route.setAuthConfig(
                    request.authUsers() != null
                            ? htpasswd(request.authUsers(), existingAuthConfig)
                            : htpasswd(request.authConfig()));
        }
        return route;
    }

    private static final int MAX_AUTH_USERS = 50;

    /**
     * Builds the htpasswd file (one bcrypt line per user) for a route. A user sent without a
     * password keeps the hash they already have in {@code existing}.
     */
    static String htpasswd(List<AuthUser> users, String existing) {
        Map<String, String> current = new LinkedHashMap<>();
        if (existing != null) {
            for (String line : existing.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) current.put(line.substring(0, colon), line.strip());
            }
        }
        if (users.isEmpty()) throw bad("Add at least one person who can sign in");
        if (users.size() > MAX_AUTH_USERS) {
            throw bad("A route can have up to " + MAX_AUTH_USERS + " users");
        }
        Set<String> seen = new HashSet<>();
        List<String> lines = new ArrayList<>();
        for (AuthUser u : users) {
            String name = u.username() == null ? "" : u.username().trim();
            if (!BASIC_USER.matcher(name).matches()) {
                throw bad("Usernames may only use letters, digits and . _ @ -");
            }
            if (!seen.add(name)) throw bad("The username " + name + " is listed twice");
            String password = u.password() == null ? "" : u.password();
            if (password.isEmpty()) {
                String kept = current.get(name);
                if (kept == null) throw bad("Set a password for " + name);
                lines.add(kept);
            } else {
                if (password.length() < 8) {
                    throw bad("Use a password of at least 8 characters for " + name);
                }
                lines.add(name + ":" + new BCryptPasswordEncoder().encode(password));
            }
        }
        return String.join("\n", lines);
    }

    /** The usernames in a route's htpasswd file, in order. */
    public static List<String> authUsernames(String htpasswd) {
        List<String> names = new ArrayList<>();
        if (htpasswd == null) return names;
        for (String line : htpasswd.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) names.add(line.substring(0, colon).strip());
        }
        return names;
    }

    /** "user:password" from the form, stored only as an htpasswd line with a bcrypt hash. */
    private static String htpasswd(String userAndPassword) {
        if (userAndPassword == null || userAndPassword.isBlank()) return null;
        int colon = userAndPassword.indexOf(':');
        if (colon < 1 || colon == userAndPassword.length() - 1) {
            throw bad("Enter the basic-auth credentials as username:password");
        }
        String user = userAndPassword.substring(0, colon);
        String password = userAndPassword.substring(colon + 1);
        if (!BASIC_USER.matcher(user).matches())
            throw bad("The username may only use letters, digits and . _ @ -");
        if (password.length() < 8) throw bad("Use a password of at least 8 characters");
        return user + ":" + new BCryptPasswordEncoder().encode(password);
    }

    private static void validatePorts(EnableIngressRequest request) {
        for (int p : new int[] {request.httpPort(), request.httpsPort()}) {
            if (p < 1 || p > 65535) throw bad("Ports must be between 1 and 65535");
        }
        if (request.httpPort() == request.httpsPort())
            throw bad("HTTP and HTTPS need different ports");
    }

    private IngressConfig requireConfig(String hostId) {
        return configRepository
                .findByDockerHostId(hostId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Ingress is not set up on this host"));
    }

    private IngressConfig requireEnabled(String hostId) {
        IngressConfig config = requireConfig(hostId);
        if (config.getStatus() != IngressStatus.ENABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Turn ingress on first");
        }
        return config;
    }

    private static IngressRoute copy(IngressRoute r) {
        IngressRoute c = new IngressRoute();
        c.setId(r.getId());
        c.setIngressConfigId(r.getIngressConfigId());
        c.setHostname(r.getHostname());
        c.setPathPrefix(r.getPathPrefix());
        c.setTargetContainerId(r.getTargetContainerId());
        c.setTargetContainerName(r.getTargetContainerName());
        c.setTargetPort(r.getTargetPort());
        c.setProtocol(r.getProtocol());
        c.setTlsMode(r.getTlsMode());
        c.setCertificateId(r.getCertificateId());
        c.setAuthEnabled(r.isAuthEnabled());
        c.setForceHttpsRedirect(r.isForceHttpsRedirect());
        c.setAuthType(r.getAuthType());
        c.setAuthConfig(r.getAuthConfig());
        c.setEnabled(r.isEnabled());
        c.setGeneratedNginxBlock(r.getGeneratedNginxBlock());
        c.setCreatedAt(r.getCreatedAt());
        c.setUpdatedAt(r.getUpdatedAt());
        c.setCreatedByUserId(r.getCreatedByUserId());
        c.setCreatedByUsername(r.getCreatedByUsername());
        return c;
    }

    private void audit(
            IngressConfig config,
            IngressAction action,
            String details,
            String userId,
            String username) {
        auditLogRepository.save(
                IngressAuditLog.success(config.getId(), action, details, userId, username));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    // --- records -----------------------------------------------------------------------------

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
            boolean forceHttpsRedirect,
            /** Who may sign in. A blank password keeps that user's current one. */
            List<AuthUser> authUsers) {}

    public record AuthUser(String username, String password) {}

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
            int activeCertificates,
            String lastError) {}
}
