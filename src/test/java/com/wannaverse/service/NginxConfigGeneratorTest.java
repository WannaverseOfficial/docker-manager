package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import com.wannaverse.persistence.IngressCertificate;
import com.wannaverse.persistence.IngressConfig;
import com.wannaverse.persistence.IngressRoute;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class NginxConfigGeneratorTest {
    private final NginxConfigGenerator generator = new NginxConfigGenerator();

    private static IngressRoute route(String host, String path, IngressRoute.TlsMode tls) {
        IngressRoute r = new IngressRoute();
        r.setId("r-" + host.replace('.', '-') + path.replace('/', '_'));
        r.setHostname(host);
        r.setPathPrefix(path);
        r.setTargetContainerName("app");
        r.setTargetPort(8080);
        r.setTlsMode(tls);
        r.setForceHttpsRedirect(tls != IngressRoute.TlsMode.NONE);
        r.setEnabled(true);
        return r;
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void pendingCertificateDoesNotProduceAnSslServerWithoutACertificate() {
        IngressCertificate pending = new IngressCertificate();
        pending.setHostname("a.example.com");
        pending.setStatus(IngressCertificate.CertificateStatus.PENDING);
        String conf =
                generator.generateFullConfig(
                        new IngressConfig(),
                        List.of(route("a.example.com", "/", IngressRoute.TlsMode.LETS_ENCRYPT)),
                        Map.of("a.example.com", pending));
        // Only the default server listens on 443, and it rejects TLS handshakes.
        assertEquals(1, count(conf, "listen 443 ssl"));
        assertTrue(conf.contains("ssl_reject_handshake on"));
        assertFalse(conf.contains("return 301 https"), "must not redirect before HTTPS exists");
        assertTrue(conf.contains("proxy_pass $upstream_"));
    }

    @Test
    void usableCertificateEnablesHttpsAndRedirect() {
        IngressCertificate cert = new IngressCertificate();
        cert.setHostname("a.example.com");
        cert.setStatus(IngressCertificate.CertificateStatus.ACTIVE);
        cert.setCertificatePem("-----BEGIN CERTIFICATE-----x");
        cert.setPrivateKeyPem("encrypted");
        cert.setExpiresAt(System.currentTimeMillis() + 86_400_000L);
        String conf =
                generator.generateFullConfig(
                        new IngressConfig(),
                        List.of(route("a.example.com", "/", IngressRoute.TlsMode.LETS_ENCRYPT)),
                        Map.of("a.example.com", cert));
        assertEquals(2, count(conf, "listen 443 ssl"));
        assertTrue(conf.contains("ssl_certificate /etc/nginx/certs/a.example.com/fullchain.pem"));
        assertTrue(conf.contains("return 301 https://$host$request_uri"));
    }

    @Test
    void upstreamsAreResolvedAtRequestTimeAndBracesBalance() {
        String conf =
                generator.generateFullConfig(
                        new IngressConfig(),
                        List.of(
                                route("a.example.com", "/", IngressRoute.TlsMode.NONE),
                                route("a.example.com", "/api", IngressRoute.TlsMode.NONE)),
                        Map.of());
        assertTrue(conf.contains("resolver 127.0.0.11"));
        assertTrue(conf.contains("location ^~ /api {"));
        assertEquals(count(conf, "{"), count(conf, "}"));
        // Longer paths come first.
        assertTrue(
                conf.indexOf("location ^~ /api") < conf.indexOf("location / {\n            set"));
    }

    @Test
    void protectedRoutesGetASignInPageAndAcceptBasicAuth() {
        IngressRoute r = route("a.example.com", "/", IngressRoute.TlsMode.NONE);
        r.setId("1b2c3d4e-0000-4000-8000-000000000001");
        r.setAuthEnabled(true);
        r.setAuthType(IngressRoute.AuthType.BASIC);
        r.setAuthConfig("alice:$2a$10$hash");
        String conf = generator.generateFullConfig(new IngressConfig(), List.of(r), Map.of());
        // Kept for checking with a real nginx (nginx -t) outside the unit tests.
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/tmp"));
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("build/tmp/nginx-sample.conf"), conf);
        } catch (java.io.IOException ignored) {
        }
        assertTrue(
                conf.startsWith("# Managed")
                        || conf.contains("load_module modules/ngx_http_js_module.so;"));
        assertTrue(conf.indexOf("load_module") < conf.indexOf("events {"));
        assertTrue(conf.contains("listen 127.0.0.1:8088;"));
        assertTrue(conf.contains("location = /.dm-auth/" + r.getId() + "/login {"));
        assertTrue(conf.contains("satisfy any;"));
        assertTrue(conf.contains("auth_request /.dm-auth/" + r.getId() + "/verify;"));
        assertTrue(
                conf.contains("error_page 401 = @dm_login_1b2c3d4e_0000_4000_8000_000000000001;"));
        assertTrue(conf.contains("location @dm_login_1b2c3d4e_0000_4000_8000_000000000001 {"));
        assertEquals(count(conf, "{"), count(conf, "}"));
        // Secrets never appear in the config, which is shown in the UI.
        assertFalse(conf.contains("$2a$10$hash"));
    }

    @Test
    void usersKeepTheirPasswordWhenNoneIsSent() {
        String first =
                IngressService.htpasswd(
                        List.of(
                                new IngressService.AuthUser("alice", "password-1"),
                                new IngressService.AuthUser("bob", "password-2")),
                        null);
        String aliceLine = first.split("\n")[0];
        String second =
                IngressService.htpasswd(
                        List.of(
                                new IngressService.AuthUser("alice", ""),
                                new IngressService.AuthUser("carol", "password-3")),
                        first);
        assertEquals(List.of("alice", "carol"), IngressService.authUsernames(second));
        assertEquals(aliceLine, second.split("\n")[0]);
        assertThrows(
                Exception.class,
                () ->
                        IngressService.htpasswd(
                                List.of(new IngressService.AuthUser("dave", "")), first));
        assertThrows(
                Exception.class,
                () ->
                        IngressService.htpasswd(
                                List.of(
                                        new IngressService.AuthUser("x", "password-1"),
                                        new IngressService.AuthUser("x", "password-2")),
                                null));
    }
}
