package com.wannaverse.service;

import com.wannaverse.persistence.IngressCertificate;
import com.wannaverse.persistence.IngressConfig;
import com.wannaverse.persistence.IngressRoute;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Generates the ingress proxy's nginx.conf.
 *
 * <p>Differences from the previous generator, each of which could break every route at once:
 *
 * <ul>
 *   <li>An HTTPS server block is only written when a usable certificate exists. Before, a route
 *       waiting for its certificate produced an {@code ssl} listener without a certificate, which
 *       nginx rejects, so the whole config failed to load.
 *   <li>Upstreams are resolved at request time through Docker's DNS, so a stopped or recreated
 *       container no longer makes nginx refuse the config ("host not found in upstream").
 *   <li>ACME challenges are served from files in the proxy container instead of being proxied to
 *       Docker Manager through host.docker.internal on a guessed port.
 *   <li>WebSocket upgrade headers are only sent when the client asked for an upgrade, so normal
 *       requests keep using keep-alive.
 * </ul>
 *
 * <p>All values written into the config (hostnames, paths, container names) are validated by {@link
 * IngressService} before they are stored.
 */
@Service
public class NginxConfigGenerator {
    public static final String ACME_ROOT = "/var/www/acme";
    public static final String CERT_DIR = "/etc/nginx/certs";
    public static final String AUTH_DIR = "/etc/nginx/auth";
    public static final String NJS_DIR = "/etc/nginx/njs";
    public static final String AUTH_SCRIPT = "dm_auth.js";

    /** Loopback-only port where the sign-in page has nginx check a password. */
    static final int AUTH_CHECK_PORT = 8088;

    /** True when the certificate can be served: it has a key and has not expired. */
    public static boolean usable(IngressCertificate cert) {
        return cert != null
                && cert.getCertificatePem() != null
                && !cert.getCertificatePem().isBlank()
                && cert.getPrivateKeyPem() != null
                && !cert.getPrivateKeyPem().isBlank()
                && (cert.getExpiresAt() == null
                        || cert.getExpiresAt() > System.currentTimeMillis());
    }

    public String generateFullConfig(
            IngressConfig config,
            List<IngressRoute> routes,
            Map<String, IngressCertificate> certificates) {
        StringBuilder sb = new StringBuilder();
        sb.append(header(config));

        Map<String, List<IngressRoute>> byHost = new TreeMap<>();
        for (IngressRoute route : routes) {
            if (route.isEnabled()) {
                byHost.computeIfAbsent(route.getHostname(), k -> new ArrayList<>()).add(route);
            }
        }
        for (var entry : byHost.entrySet()) {
            List<IngressRoute> hostRoutes = new ArrayList<>(entry.getValue());
            // Longest path first, so /api wins over /.
            hostRoutes.sort(
                    Comparator.comparingInt((IngressRoute r) -> path(r).length()).reversed());
            sb.append(
                    serverBlocks(
                            entry.getKey(), hostRoutes, certificates.get(entry.getKey()), config));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String header(IngressConfig config) {
        return """
        # Managed by Docker Manager. Changes made here are overwritten.
        # Generated %s for host %s

        load_module modules/ngx_http_js_module.so;

        user nginx;
        worker_processes auto;
        error_log /var/log/nginx/error.log warn;
        pid /run/nginx.pid;

        events {
            worker_connections 4096;
        }

        http {
            include /etc/nginx/mime.types;
            default_type application/octet-stream;
            log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                            '$status $body_bytes_sent "$http_referer" '
                            '"$http_user_agent" "$http_x_forwarded_for"';
            access_log /var/log/nginx/access.log main;
            sendfile on;
            keepalive_timeout 65;
            server_tokens off;
            client_max_body_size 1g;

            # Docker's embedded DNS: resolve containers at request time.
            resolver 127.0.0.11 valid=10s ipv6=off;
            resolver_timeout 5s;

            map $http_upgrade $connection_upgrade {
                default upgrade;
                ''      close;
            }

            # Sign-in pages for password-protected routes.
            js_path %s;
            js_import dmauth from %s;
            absolute_redirect off;
            limit_req_zone $binary_remote_addr zone=dm_login:1m rate=10r/m;
            limit_req_status 429;

            # Requests that send a username and password themselves (scripts) are limited per
            # address, which slows down guessing. Signed-in browsers send no header: no limit.
            map $http_authorization $dm_basic_client {
                ""      "";
                default $binary_remote_addr;
            }
            limit_req_zone $dm_basic_client zone=dm_basic:1m rate=5r/s;

            # The route's own username and password are not passed on to the app; any other
            # Authorization header (an app's own bearer token) still is.
            map $http_authorization $dm_upstream_authorization {
                "~*^basic " "";
                default     $http_authorization;
            }

            # Only reachable inside the proxy: the sign-in page asks nginx to check a password.
            server {
                listen 127.0.0.1:%d;
                location ~ ^/check/(?<dm_rid>[A-Za-z0-9-]+)$ {
                    auth_basic "check";
                    auth_basic_user_file %s/$dm_rid.htpasswd;
                    js_content dmauth.ok;
                }
                location / {
                    return 404;
                }
            }

            # Unknown hostnames: refuse TLS, 404 over HTTP (but still answer ACME).
            server {
                listen 443 ssl default_server;
                ssl_reject_handshake on;
            }
            server {
                listen 80 default_server;
                server_name _;
                location ^~ /.well-known/acme-challenge/ {
                    root %s;
                    default_type text/plain;
                }
                location / {
                    return 404;
                }
            }
        """
                .formatted(
                        Instant.now(),
                        config.getDockerHostId(),
                        NJS_DIR,
                        AUTH_SCRIPT,
                        AUTH_CHECK_PORT,
                        AUTH_DIR,
                        ACME_ROOT)
                .indent(0);
    }

    private String serverBlocks(
            String hostname,
            List<IngressRoute> routes,
            IngressCertificate cert,
            IngressConfig config) {
        boolean tls =
                usable(cert)
                        && routes.stream()
                                .anyMatch(r -> r.getTlsMode() != IngressRoute.TlsMode.NONE);
        boolean redirect = tls && routes.stream().anyMatch(IngressRoute::isForceHttpsRedirect);

        StringBuilder sb = new StringBuilder();
        sb.append("\n    # ").append(hostname).append('\n');
        sb.append("    server {\n");
        sb.append("        listen 80;\n");
        sb.append("        server_name ").append(hostname).append(";\n");
        sb.append("        location ^~ /.well-known/acme-challenge/ {\n");
        sb.append("            root ").append(ACME_ROOT).append(";\n");
        sb.append("            default_type text/plain;\n");
        sb.append("        }\n");
        if (redirect) {
            sb.append("        location / {\n");
            sb.append("            return 301 https://$host$request_uri;\n");
            sb.append("        }\n");
        } else {
            sb.append(locations(routes));
        }
        sb.append("    }\n");

        if (tls) {
            sb.append("    server {\n");
            sb.append("        listen 443 ssl;\n");
            sb.append("        http2 on;\n");
            sb.append("        server_name ").append(hostname).append(";\n");
            sb.append("        ssl_certificate ")
                    .append(CERT_DIR)
                    .append('/')
                    .append(hostname)
                    .append("/fullchain.pem;\n");
            sb.append("        ssl_certificate_key ")
                    .append(CERT_DIR)
                    .append('/')
                    .append(hostname)
                    .append("/privkey.pem;\n");
            sb.append("        ssl_protocols TLSv1.2 TLSv1.3;\n");
            sb.append("        ssl_prefer_server_ciphers off;\n");
            sb.append("        ssl_session_cache shared:SSL:10m;\n");
            sb.append("        ssl_session_timeout 1d;\n");
            if (redirect) {
                sb.append(
                        "        add_header Strict-Transport-Security \"max-age=31536000\""
                                + " always;\n");
            }
            sb.append(locations(routes));
            sb.append("    }\n");
        } else if (routes.stream().anyMatch(r -> r.getTlsMode() != IngressRoute.TlsMode.NONE)) {
            sb.append("    # HTTPS for ")
                    .append(hostname)
                    .append(" starts once its certificate is ready.\n");
        }
        return sb.toString();
    }

    static String path(IngressRoute route) {
        String p =
                route.getPathPrefix() == null || route.getPathPrefix().isBlank()
                        ? "/"
                        : route.getPathPrefix();
        return p.startsWith("/") ? p : "/" + p;
    }

    public String generateLocationBlock(IngressRoute route) {
        return location(route);
    }

    private static boolean protectedRoute(IngressRoute route) {
        return route.isAuthEnabled()
                && route.getAuthType() == IngressRoute.AuthType.BASIC
                && route.getId() != null;
    }

    private String locations(List<IngressRoute> routes) {
        StringBuilder sb = new StringBuilder();
        boolean anyAuth = false;
        for (IngressRoute r : routes) {
            if (protectedRoute(r)) {
                sb.append(authLocations(r));
                anyAuth = true;
            }
        }
        if (anyAuth) {
            sb.append(
                    """
                            location ^~ /.dm-auth/ {
                                return 404;
                            }
                    """);
        }
        for (IngressRoute r : routes) sb.append(location(r));
        return sb.toString();
    }

    /** The sign-in page, sign-out and the session check for one protected route. */
    private static String authLocations(IngressRoute route) {
        String id = route.getId();
        return """
                # Sign-in for %2$s%3$s
                location = /.dm-auth/%1$s/login {
                    set $dm_route %1$s;
                    limit_req zone=dm_login burst=10 nodelay;
                    js_content dmauth.login;
                }
                location = /.dm-auth/%1$s/logout {
                    set $dm_route %1$s;
                    js_content dmauth.logout;
                }
                location = /.dm-auth/%1$s/verify {
                    internal;
                    set $dm_route %1$s;
                    js_content dmauth.verify;
                }
                location @%4$s {
                    set $dm_route %1$s;
                    js_content dmauth.challenge;
                }
        """
                .formatted(id, route.getHostname(), path(route), loginLocation(id));
    }

    private static String loginLocation(String id) {
        return "dm_login_" + id.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private String location(IngressRoute route) {
        String path = path(route);
        String scheme = route.getProtocol() == IngressRoute.Protocol.HTTPS ? "https" : "http";
        String var = sanitizeVar(route.getId() == null ? "new" : route.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("        # ")
                .append(route.getTargetContainerName())
                .append(':')
                .append(route.getTargetPort())
                .append('\n');
        sb.append("        location ").append(path.equals("/") ? "/" : "^~ " + path).append(" {\n");
        if (protectedRoute(route)) {
            // A signed-in visitor (session cookie) or an Authorization: Basic header gets in;
            // anyone else is sent to the sign-in page (browsers) or gets a 401 (scripts).
            sb.append("            satisfy any;\n");
            sb.append("            auth_basic \"Restricted\";\n");
            sb.append("            auth_basic_user_file ")
                    .append(AUTH_DIR)
                    .append('/')
                    .append(route.getId())
                    .append(".htpasswd;\n");
            sb.append("            auth_request /.dm-auth/")
                    .append(route.getId())
                    .append("/verify;\n");
            sb.append("            error_page 401 = @")
                    .append(loginLocation(route.getId()))
                    .append(";\n");
            sb.append("            limit_req zone=dm_basic burst=40 nodelay;\n");
            sb.append("            proxy_set_header Authorization $dm_upstream_authorization;\n");
        }
        // A variable makes nginx resolve the container at request time.
        sb.append("            set $")
                .append(var)
                .append(' ')
                .append(scheme)
                .append("://")
                .append(route.getTargetContainerName())
                .append(':')
                .append(route.getTargetPort())
                .append(";\n");
        sb.append("            proxy_pass $").append(var).append(";\n");
        sb.append("            proxy_http_version 1.1;\n");
        sb.append("            proxy_set_header Upgrade $http_upgrade;\n");
        sb.append("            proxy_set_header Connection $connection_upgrade;\n");
        sb.append("            proxy_set_header Host $host;\n");
        sb.append("            proxy_set_header X-Real-IP $remote_addr;\n");
        sb.append("            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n");
        sb.append("            proxy_set_header X-Forwarded-Proto $scheme;\n");
        sb.append("            proxy_set_header X-Forwarded-Host $host;\n");
        sb.append("            proxy_read_timeout 3600s;\n");
        sb.append("            proxy_send_timeout 3600s;\n");
        if (Boolean.TRUE.equals(route.getUnlimitedUploads())) {
            // Image layers can be several GB; stream them straight through.
            sb.append("            client_max_body_size 0;\n");
            sb.append("            proxy_request_buffering off;\n");
            sb.append("            proxy_buffering off;\n");
        }
        if (scheme.equals("https")) {
            sb.append("            proxy_ssl_server_name on;\n");
        }
        sb.append("        }\n");
        return sb.toString();
    }

    private static String sanitizeVar(String id) {
        return "upstream_" + id.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** A readable preview of the blocks a new route would add. */
    public String generateServerBlockPreview(IngressRoute route, IngressCertificate cert) {
        IngressConfig config = new IngressConfig();
        if (route.getId() == null) route.setId("preview");
        return serverBlocks(route.getHostname(), List.of(route), cert, config).indent(-4);
    }
}
