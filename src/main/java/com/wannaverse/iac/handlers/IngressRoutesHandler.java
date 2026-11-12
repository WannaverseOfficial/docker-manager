package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.ConfigExporter;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.HostedRegistry;
import com.wannaverse.persistence.HostedRegistryRepository;
import com.wannaverse.persistence.IngressConfig;
import com.wannaverse.persistence.IngressConfigRepository;
import com.wannaverse.persistence.IngressRoute;
import com.wannaverse.persistence.IngressRouteRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ObservabilityStack;
import com.wannaverse.persistence.ObservabilityStackRepository;
import com.wannaverse.persistence.Rollout;
import com.wannaverse.persistence.RolloutRepository;
import com.wannaverse.security.InputValidator;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.IngressService;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ingress.<host>.routes}: addresses the proxy answers and where it sends them. Routes that
 * Docker Manager makes for other features (rollouts, the hosted registry, Grafana) are never
 * managed here.
 */
@Component
public class IngressRoutesHandler implements ResourceHandler<IngressRoutesHandler.RouteSpec> {

    @Data
    @Doc(
            "Sends visitors of hostname + path to a container (container + port), another"
                    + " address (address + port) or a web page (redirect). Give exactly one.")
    public static class RouteSpec {
        @Doc(value = "The hostname visitors use, such as app.example.com", required = true)
        private String hostname;

        @Doc("The path it answers under (default /)")
        private String path;

        @Doc("Send traffic to this container on the same host (its name)")
        private String container;

        @Doc("Send traffic to this IPv4 address or DNS name instead of a container")
        private String address;

        @Doc("The container's or address's port (required with container or address)")
        private Integer port;

        @Doc("How the proxy talks to the target: http (default) or https")
        private IngressRoute.Protocol protocol;

        @Doc("Send visitors to this web address instead (a redirect)")
        private String redirect;

        @Doc("Redirect: permanent (301) instead of temporary (302); default false")
        private Boolean redirectPermanent;

        @Doc("Redirect: add the visitor's path and query to the address; default false")
        private Boolean redirectKeepPath;

        @Doc(
                "HTTPS: none (default), lets-encrypt (requested automatically), or custom-cert /"
                        + " bring-your-own (a certificate under certificates: for this hostname)")
        private IngressRoute.TlsMode tls;

        @Doc("Send HTTP visitors to HTTPS (default false; needs tls)")
        private Boolean httpsRedirect;

        @Doc("Ask visitors for a user name and password")
        private AuthSpec auth;

        @Doc("No upload size limit or buffering, for large uploads (default false)")
        private Boolean unlimitedUploads;

        @Doc("Answer visitors (default true). False keeps the route but pauses it.")
        private Boolean enabled;
    }

    @Data
    public static class AuthSpec {
        @Doc(
                value = "Who may sign in: user name to password (at least 8 characters)",
                required = true)
        private Map<String, Secret> users = new LinkedHashMap<>();
    }

    private final IngressService ingress;
    private final IngressConfigRepository configs;
    private final IngressRouteRepository routes;
    private final HostedRegistryRepository registries;
    private final ObservabilityStackRepository observability;
    private final RolloutRepository rollouts;

    public IngressRoutesHandler(
            IngressService ingress,
            IngressConfigRepository configs,
            IngressRouteRepository routes,
            HostedRegistryRepository registries,
            ObservabilityStackRepository observability,
            RolloutRepository rollouts) {
        this.ingress = ingress;
        this.configs = configs;
        this.routes = routes;
        this.registries = registries;
        this.observability = observability;
        this.rollouts = rollouts;
    }

    @Override
    public String section() {
        return "ingress.routes";
    }

    @Override
    public String parentSection() {
        return "ingress";
    }

    @Override
    public String parentField() {
        return "routes";
    }

    @Override
    public int order() {
        return 400;
    }

    @Override
    public Class<RouteSpec> specType() {
        return RouteSpec.class;
    }

    @Override
    public String description() {
        return "Routes of the proxy, keyed by a name of your choice";
    }

    @Override
    public void validate(Entry<RouteSpec> entry, ApplyContext ctx) {
        RouteSpec s = entry.spec();
        String hostname = hostname(s);
        if (!InputValidator.isValidHostname(hostname) || !hostname.contains(".")) {
            throw new IacException("hostname: give a full hostname, such as app.example.com");
        }
        int targets =
                (blank(s.getContainer()) ? 0 : 1)
                        + (blank(s.getAddress()) ? 0 : 1)
                        + (blank(s.getRedirect()) ? 0 : 1);
        if (targets != 1) {
            throw new IacException("give exactly one of container, address or redirect");
        }
        if (blank(s.getRedirect())) {
            if (s.getPort() == null || s.getPort() < 1 || s.getPort() > 65535) {
                throw new IacException("port: the target's port, 1 to 65535");
            }
        } else if (s.getAuth() != null) {
            throw new IacException("auth: a redirect can't ask for a password");
        }
        if (s.getAuth() != null) {
            if (s.getAuth().getUsers().isEmpty()) {
                throw new IacException("auth.users: add at least one user");
            }
            for (var u : s.getAuth().getUsers().entrySet()) {
                String pw = Secret.valueOf(u.getValue());
                if (pw == null || pw.length() < 8) {
                    throw new IacException(
                            "auth.users."
                                    + u.getKey()
                                    + ": use a password of at least 8 characters");
                }
            }
        }
        if (Boolean.TRUE.equals(s.getHttpsRedirect())
                && (s.getTls() == null || s.getTls() == IngressRoute.TlsMode.NONE)) {
            throw new IacException("httpsRedirect: needs tls (lets-encrypt, custom-cert...)");
        }
        String hostId = ctx.hostId(entry.parentKey());
        if (hostId != null) {
            IngressRoute clash = existing(hostId, hostname, path(s)).orElse(null);
            if (clash != null && systemRouteIds().contains(clash.getId())) {
                throw new IacException(
                        hostname
                                + path(s)
                                + " belongs to "
                                + owner(clash)
                                + "; it can't be managed by the file");
            }
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<RouteSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.parentKey());
        if (hostId == null) return Optional.empty();
        RouteSpec s = entry.spec();
        return existing(hostId, hostname(s), path(s))
                .filter(r -> !systemRouteIds().contains(r.getId()))
                .map(r -> target(r, hostId));
    }

    private Optional<IngressRoute> existing(String hostId, String hostname, String path) {
        return configs.findByDockerHostId(hostId)
                .flatMap(
                        c ->
                                routes.findByIngressConfigIdAndHostnameAndPathPrefix(
                                        c.getId(), hostname, path));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return routes.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<RouteSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.parentKey());
        RouteSpec s = entry.spec();
        IngressRoute route =
                ingress.exposeApp(
                        hostId,
                        request(s, true),
                        SecurityContextHolder.getCurrentUserId(),
                        ctx.actor());
        if (Boolean.FALSE.equals(s.getEnabled())) {
            route =
                    ingress.updateRoute(
                            route.getId(),
                            null,
                            false,
                            SecurityContextHolder.getCurrentUserId(),
                            ctx.actor());
        }
        return target(route, hostId);
    }

    @Override
    public Target update(
            Entry<RouteSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        RouteSpec s = entry.spec();
        IngressRoute current =
                routes.findById(record.getTargetId())
                        .orElseThrow(() -> new IacException("the route no longer exists"));
        if (systemRouteIds().contains(current.getId())) {
            throw new IacException(
                    "this route now belongs to " + owner(current) + "; remove it from the file");
        }
        IngressRoute route =
                ingress.updateRoute(
                        current.getId(),
                        request(s, changes.has("auth")),
                        s.getEnabled() == null || s.getEnabled(),
                        SecurityContextHolder.getCurrentUserId(),
                        ctx.actor());
        String hostId =
                record.getHostId() != null ? record.getHostId() : ctx.hostId(entry.parentKey());
        return target(route, hostId);
    }

    /**
     * The request the Ingress page would send.
     *
     * @param passwords send the users' passwords (they are hashed again); without them the current
     *     ones are kept
     */
    private static IngressService.ExposeAppRequest request(RouteSpec s, boolean passwords) {
        String type =
                !blank(s.getRedirect())
                        ? "REDIRECT"
                        : !blank(s.getAddress()) ? "ADDRESS" : "CONTAINER";
        boolean auth = s.getAuth() != null;
        List<IngressService.AuthUser> users = null;
        if (auth && passwords) {
            users = new ArrayList<>();
            for (var u : s.getAuth().getUsers().entrySet()) {
                users.add(new IngressService.AuthUser(u.getKey(), Secret.valueOf(u.getValue())));
            }
        }
        IngressRoute.TlsMode tls = s.getTls() == null ? IngressRoute.TlsMode.NONE : s.getTls();
        return new IngressService.ExposeAppRequest(
                null,
                blank(s.getContainer()) ? null : s.getContainer().trim(),
                hostname(s),
                path(s),
                s.getPort() == null ? 0 : s.getPort(),
                (s.getProtocol() == null ? IngressRoute.Protocol.HTTP : s.getProtocol()).name(),
                tls.name(),
                null,
                auth,
                null,
                null,
                Boolean.TRUE.equals(s.getHttpsRedirect()),
                users,
                Boolean.TRUE.equals(s.getUnlimitedUploads()),
                type,
                blank(s.getAddress()) ? null : s.getAddress().trim(),
                blank(s.getRedirect()) ? null : s.getRedirect().trim(),
                Boolean.TRUE.equals(s.getRedirectPermanent()),
                Boolean.TRUE.equals(s.getRedirectKeepPath()));
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        IngressRoute route = routes.findById(record.getTargetId()).orElse(null);
        if (route == null) return;
        if (systemRouteIds().contains(route.getId())) {
            // Another feature took it over; it is theirs now.
            return;
        }
        ingress.removeRoute(
                route.getId(), true, SecurityContextHolder.getCurrentUserId(), ctx.actor());
    }

    @Override
    public Map<String, RouteSpec> export(ExportContext ctx) {
        Set<String> system = systemRouteIds();
        Map<String, RouteSpec> out = new LinkedHashMap<>();
        for (IngressConfig config : configs.findAll()) {
            String hostKey = ctx.hostKey(config.getDockerHostId());
            Set<String> used = new HashSet<>();
            for (IngressRoute r : routes.findByIngressConfigId(config.getId())) {
                if (system.contains(r.getId())) continue;
                String fallback =
                        ConfigExporter.slug(
                                r.getHostname()
                                        + ("/".equals(r.getPathPrefix())
                                                ? ""
                                                : "-" + r.getPathPrefix()));
                String name = ctx.key(section(), r.getId(), fallback);
                String base = name;
                for (int i = 2; !used.add(name); i++) name = base + "-" + i;
                out.put(hostKey + "/" + name, spec(r, hostKey + "_" + name, ctx));
            }
        }
        return out;
    }

    private static RouteSpec spec(IngressRoute r, String envKey, ExportContext ctx) {
        RouteSpec s = new RouteSpec();
        s.setHostname(r.getHostname());
        if (!"/".equals(r.getPathPrefix())) s.setPath(r.getPathPrefix());
        switch (r.kind()) {
            case CONTAINER -> {
                s.setContainer(r.getTargetContainerName());
                s.setPort(r.getTargetPort());
            }
            case ADDRESS -> {
                s.setAddress(r.getTargetHost());
                s.setPort(r.getTargetPort());
            }
            case REDIRECT -> {
                s.setRedirect(r.getRedirectUrl());
                if (Boolean.TRUE.equals(r.getRedirectPermanent())) s.setRedirectPermanent(true);
                if (Boolean.TRUE.equals(r.getRedirectKeepPath())) s.setRedirectKeepPath(true);
            }
        }
        if (r.kind() != IngressRoute.TargetType.REDIRECT
                && r.getProtocol() == IngressRoute.Protocol.HTTPS) {
            s.setProtocol(r.getProtocol());
        }
        if (r.getTlsMode() != null && r.getTlsMode() != IngressRoute.TlsMode.NONE) {
            s.setTls(r.getTlsMode());
        }
        if (r.isForceHttpsRedirect()) s.setHttpsRedirect(true);
        if (Boolean.TRUE.equals(r.getUnlimitedUploads())) s.setUnlimitedUploads(true);
        if (!r.isEnabled()) s.setEnabled(false);
        if (r.isAuthEnabled() && r.getAuthType() == IngressRoute.AuthType.BASIC) {
            AuthSpec auth = new AuthSpec();
            for (String user : IngressService.authUsernames(r.getAuthConfig())) {
                auth.getUsers()
                        .put(
                                user,
                                ctx.secret(
                                        "route",
                                        envKey,
                                        user,
                                        "password of " + user + " on " + r.getHostname()));
            }
            s.setAuth(auth);
        }
        return s;
    }

    /** Routes other features own: rollouts, the hosted registry, Grafana. */
    private Set<String> systemRouteIds() {
        Set<String> ids = new HashSet<>();
        for (IngressRoute r : routes.findAll()) {
            if (r.getRolloutId() != null || r.getAuthType() == IngressRoute.AuthType.GRAFANA_SSO) {
                ids.add(r.getId());
            }
        }
        for (HostedRegistry h : registries.findAll()) {
            if (h.getIngressRouteId() != null) ids.add(h.getIngressRouteId());
        }
        for (ObservabilityStack o : observability.findAll()) {
            if (o.getIngressRouteId() != null) ids.add(o.getIngressRouteId());
        }
        for (Rollout r : rollouts.findAll()) {
            if (r.getRouteId() != null) ids.add(r.getRouteId());
            if (r.getPreviewRouteId() != null) ids.add(r.getPreviewRouteId());
        }
        return ids;
    }

    private String owner(IngressRoute r) {
        if (r.getRolloutId() != null) return "a rollout";
        if (r.getAuthType() == IngressRoute.AuthType.GRAFANA_SSO) return "an observability stack";
        for (HostedRegistry h : registries.findAll()) {
            if (r.getId().equals(h.getIngressRouteId())) return "the hosted registry";
        }
        for (ObservabilityStack o : observability.findAll()) {
            if (r.getId().equals(o.getIngressRouteId())) return "an observability stack";
        }
        return "a rollout";
    }

    private static Target target(IngressRoute r, String hostId) {
        return new Target(r.getId(), hostId, r.getHostname() + r.getPathPrefix());
    }

    private static String hostname(RouteSpec s) {
        return s.getHostname().trim().toLowerCase(Locale.ROOT);
    }

    private static String path(RouteSpec s) {
        String p = blank(s.getPath()) ? "/" : s.getPath().trim();
        return p.startsWith("/") ? p : "/" + p;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
