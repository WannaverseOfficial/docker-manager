package com.wannaverse.service;

import com.wannaverse.persistence.IngressCertificate;
import com.wannaverse.persistence.IngressConfig;
import com.wannaverse.persistence.IngressRoute;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NginxConfigGenerator {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Generate complete nginx.conf from all enabled routes.
     *
     * @param config The ingress configuration
     * @param routes List of routes to include
     * @param certificates Map of hostname to certificate
     * @return Complete nginx configuration
     */
    public String generateFullConfig(
            IngressConfig config,
            List<IngressRoute> routes,
            Map<String, IngressCertificate> certificates) {
        StringBuilder sb = new StringBuilder();

        // Main nginx.conf header
        sb.append(generateMainConfig(config));

        // Group routes by hostname for server blocks
        Map<String, List<IngressRoute>> routesByHostname = new HashMap<>();
        for (IngressRoute route : routes) {
            if (route.isEnabled()) {
                routesByHostname
                        .computeIfAbsent(route.getHostname(), k -> new ArrayList<>())
                        .add(route);
            }
        }

        // Generate server block for each hostname
        for (Map.Entry<String, List<IngressRoute>> entry : routesByHostname.entrySet()) {
            String hostname = entry.getKey();
            List<IngressRoute> hostnameRoutes = entry.getValue();
            IngressCertificate cert = certificates.get(hostname);

            sb.append("\n");
            sb.append(generateServerBlock(hostname, hostnameRoutes, cert, config));
        }

        sb.append("}\n"); // Close http block

        return sb.toString();
    }

    /** Generate main nginx.conf header and defaults. */
    private String generateMainConfig(IngressConfig config) {
        return """
        # Managed Ingress Configuration
        # Generated: %s
        # Host ID: %s
        # DO NOT EDIT MANUALLY - Changes will be overwritten

        user nginx;
        worker_processes auto;
        error_log /var/log/nginx/error.log warn;
        pid /var/run/nginx.pid;

        events {
            worker_connections 1024;
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

            # ACME challenge handler (always present for Let's Encrypt)
            server {
                listen %d default_server;
                server_name _;

                location /.well-known/acme-challenge/ {
                    proxy_pass http://host.docker.internal:%d/api/ingress/acme/;
                    proxy_set_header Host $host;
                }

                location / {
                    return 404;
                }
            }
        """
                .formatted(
                        DATE_FORMATTER.format(Instant.now()),
                        config.getDockerHostId(),
                        config.getHttpPort(),
                        config.getAcmeProxyPort());
    }

    /** Generate server block for a hostname with its routes. */
    private String generateServerBlock(
            String hostname,
            List<IngressRoute> routes,
            IngressCertificate cert,
            IngressConfig config) {
        StringBuilder sb = new StringBuilder();

        // Check if any route needs HTTPS AND we have a valid certificate
        // Without a valid cert, serve HTTP and allow ACME challenge to complete first
        boolean hasCert =
                cert != null && cert.getStatus() == IngressCertificate.CertificateStatus.ACTIVE;
        boolean hasHttps =
                hasCert
                        && routes.stream()
                                .anyMatch(
                                        r ->
                                                r.getProtocol() == IngressRoute.Protocol.HTTPS
                                                        && r.getTlsMode()
                                                                != IngressRoute.TlsMode.NONE);

        // HTTP server block
        sb.append(
                """

                    # Hostname: %s
                    server {
                        listen %d;
                        server_name %s;
                """
                        .formatted(hostname, config.getHttpPort(), hostname));

        // ACME challenge location
        sb.append(
                """

                        location /.well-known/acme-challenge/ {
                            proxy_pass http://host.docker.internal:%d/api/ingress/acme/;
                            proxy_set_header Host $host;
                        }
                """
                        .formatted(config.getAcmeProxyPort()));

        if (hasHttps) {
            // Redirect HTTP to HTTPS
            sb.append(
                    """

                            location / {
                                return 301 https://$host$request_uri;
                            }
                        }
                    """);

            // HTTPS server block
            sb.append(generateHttpsServerBlock(hostname, routes, cert, config));
        } else {
            // HTTP-only: add location blocks directly
            for (IngressRoute route : routes) {
                sb.append(generateLocationBlock(route));
            }
            sb.append("    }\n");
        }

        return sb.toString();
    }

    /** Generate HTTPS server block. */
    private String generateHttpsServerBlock(
            String hostname,
            List<IngressRoute> routes,
            IngressCertificate cert,
            IngressConfig config) {
        StringBuilder sb = new StringBuilder();

        // Get first route for metadata
        IngressRoute firstRoute = routes.get(0);

        sb.append(
                """

                    # HTTPS server for %s
                    # Route ID: %s | Created by: %s | %s
                    server {
                        listen %d ssl http2;
                        server_name %s;

                """
                        .formatted(
                                hostname,
                                firstRoute.getId(),
                                firstRoute.getCreatedByUsername() != null
                                        ? firstRoute.getCreatedByUsername()
                                        : "system",
                                DATE_FORMATTER.format(
                                        Instant.ofEpochMilli(firstRoute.getCreatedAt())),
                                config.getHttpsPort(),
                                hostname));

        // TLS configuration
        if (cert != null && cert.getStatus() == IngressCertificate.CertificateStatus.ACTIVE) {
            sb.append(
                    """
                        # TLS Configuration
                        ssl_certificate /etc/nginx/certs/%s/fullchain.pem;
                        ssl_certificate_key /etc/nginx/certs/%s/privkey.pem;
                        ssl_protocols TLSv1.2 TLSv1.3;
                        ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
                        ssl_prefer_server_ciphers off;

                    """
                            .formatted(hostname, hostname));
        } else {
            sb.append(
                    """
                        # WARNING: No valid certificate - using self-signed placeholder
                        # ssl_certificate /etc/nginx/certs/default/fullchain.pem;
                        # ssl_certificate_key /etc/nginx/certs/default/privkey.pem;

                    """);
        }

        // Location blocks for each route
        for (IngressRoute route : routes) {
            sb.append(generateLocationBlock(route));
        }

        sb.append("    }\n");

        return sb.toString();
    }

    /** Generate location block for a route. */
    public String generateLocationBlock(IngressRoute route) {
        StringBuilder sb = new StringBuilder();

        String path = route.getPathPrefix() != null ? route.getPathPrefix() : "/";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        sb.append(
                """

                        # Route: %s -> %s:%d
                        location %s {
                """
                        .formatted(
                                route.getHostname(),
                                route.getTargetContainerName(),
                                route.getTargetPort(),
                                path));

        // Optional Basic Auth
        if (route.isAuthEnabled() && route.getAuthType() == IngressRoute.AuthType.BASIC) {
            sb.append(
                    """
                            auth_basic "Restricted";
                            auth_basic_user_file /etc/nginx/auth/%s.htpasswd;

                    """
                            .formatted(route.getHostname().replace(".", "-")));
        }

        // Proxy configuration
        sb.append(
                """
                            proxy_pass http://%s:%d;
                            proxy_http_version 1.1;
                            proxy_set_header Upgrade $http_upgrade;
                            proxy_set_header Connection "upgrade";
                            proxy_set_header Host $host;
                            proxy_set_header X-Real-IP $remote_addr;
                            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                            proxy_set_header X-Forwarded-Proto $scheme;
                            proxy_read_timeout 86400;
                        }
                """
                        .formatted(route.getTargetContainerName(), route.getTargetPort()));

        return sb.toString();
    }

    /**
     * Generate a single server block preview for the UI.
     *
     * @param route The route to generate config for
     * @param cert Optional certificate
     * @return Preview of nginx configuration
     */
    public String generateServerBlockPreview(IngressRoute route, IngressCertificate cert) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Preview: Server block for ").append(route.getHostname()).append("\n\n");

        if (route.getProtocol() == IngressRoute.Protocol.HTTPS
                && route.getTlsMode() != IngressRoute.TlsMode.NONE) {
            sb.append("server {\n");
            sb.append("    listen 443 ssl http2;\n");
            sb.append("    server_name ").append(route.getHostname()).append(";\n\n");

            if (cert != null) {
                sb.append("    ssl_certificate /etc/nginx/certs/")
                        .append(route.getHostname())
                        .append("/fullchain.pem;\n");
                sb.append("    ssl_certificate_key /etc/nginx/certs/")
                        .append(route.getHostname())
                        .append("/privkey.pem;\n");
            } else {
                sb.append("    # Certificate will be obtained from: ")
                        .append(route.getTlsMode())
                        .append("\n");
            }

            sb.append("\n");
            sb.append(generateLocationBlock(route).replaceAll("(?m)^", ""));
            sb.append("}\n");

            // HTTP redirect block
            sb.append("\nserver {\n");
            sb.append("    listen 80;\n");
            sb.append("    server_name ").append(route.getHostname()).append(";\n");
            sb.append("    return 301 https://$host$request_uri;\n");
            sb.append("}\n");
        } else {
            sb.append("server {\n");
            sb.append("    listen 80;\n");
            sb.append("    server_name ").append(route.getHostname()).append(";\n\n");
            sb.append(generateLocationBlock(route).replaceAll("(?m)^", ""));
            sb.append("}\n");
        }

        return sb.toString();
    }

    /** Validate nginx configuration syntax. Returns null if valid, error message if invalid. */
    public ValidationResult validateConfig(String config) {
        // Basic validation - check for balanced braces
        int braceCount = 0;
        for (char c : config.toCharArray()) {
            if (c == '{') braceCount++;
            if (c == '}') braceCount--;
            if (braceCount < 0) {
                return new ValidationResult(false, "Unmatched closing brace", null);
            }
        }
        if (braceCount != 0) {
            return new ValidationResult(false, "Unmatched opening brace", null);
        }

        // Check for required directives
        if (!config.contains("events {")) {
            return new ValidationResult(false, "Missing 'events' block", null);
        }
        if (!config.contains("http {")) {
            return new ValidationResult(false, "Missing 'http' block", null);
        }

        return new ValidationResult(true, null, null);
    }

    public record ValidationResult(boolean valid, String error, List<String> warnings) {}
}
