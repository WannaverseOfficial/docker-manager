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

    private static IngressService.ExposeAppRequest redirectRequest(
            String url, boolean permanent, boolean keepPath) {
        return new IngressService.ExposeAppRequest(
                null,
                null,
                "go.example.com",
                "/",
                0,
                null,
                "NONE",
                null,
                false,
                null,
                null,
                false,
                null,
                null,
                "REDIRECT",
                null,
                url,
                permanent,
                keepPath);
    }

    @Test
    void redirectRoutesReturnInsteadOfProxying() {
        IngressRoute temp =
                IngressService.toRedirect(
                        redirectRequest("google.com", false, false), "go.example.com", "/");
        temp.setId("r-temp");
        temp.setEnabled(true);
        assertEquals("https://google.com", temp.getRedirectUrl());
        IngressRoute kept =
                IngressService.toRedirect(
                        redirectRequest("https://example.org/base/", true, true),
                        "old.example.com",
                        "/");
        kept.setHostname("old.example.com");
        kept.setId("r-kept");
        kept.setEnabled(true);
        String conf =
                generator.generateFullConfig(new IngressConfig(), List.of(temp, kept), Map.of());
        writeSample("nginx-redirect.conf", conf);
        assertTrue(conf.contains("return 302 \"https://google.com\";"));
        assertTrue(conf.contains("return 301 \"https://example.org/base$request_uri\";"));
        assertFalse(conf.contains("proxy_pass"), "a redirect has no upstream");
        assertEquals(count(conf, "{"), count(conf, "}"));
    }

    @Test
    void redirectAddressesThatCouldBreakTheConfigAreRefused() {
        for (String bad :
                List.of(
                        "https://evil.example/$host",
                        "https://a.example/x\"; return 200 \"",
                        "https://a.example/ x",
                        "https://a.example/{x}",
                        "ftp://a.example",
                        "https://user:pass@a.example",
                        "")) {
            assertThrows(
                    Exception.class,
                    () ->
                            IngressService.toRedirect(
                                    redirectRequest(bad, false, false), "go.example.com", "/"),
                    bad);
        }
        // Back to the same route: an endless loop.
        assertThrows(
                Exception.class,
                () ->
                        IngressService.toRedirect(
                                redirectRequest("https://go.example.com/", false, true),
                                "go.example.com",
                                "/"));
        // Another path on the same hostname is fine.
        IngressService.toRedirect(
                redirectRequest("https://go.example.com/new", false, true),
                "go.example.com",
                "/old");
        // Keeping the path needs an address without a query.
        assertThrows(
                Exception.class,
                () ->
                        IngressService.toRedirect(
                                redirectRequest("https://a.example/?x=1", false, true),
                                "go.example.com",
                                "/"));
    }

    @Test
    void addressRoutesProxyToTheTypedHost() {
        IngressRoute r = route("nas.example.com", "/", IngressRoute.TlsMode.NONE);
        r.setTargetType(IngressRoute.TargetType.ADDRESS);
        r.setTargetContainerName("");
        r.setTargetHost("192.168.1.20");
        r.setTargetPort(5000);
        String conf = generator.generateFullConfig(new IngressConfig(), List.of(r), Map.of());
        writeSample("nginx-address.conf", conf);
        assertTrue(conf.contains("set $upstream_r_nas_example_com_ http://192.168.1.20:5000;"));
        assertEquals("192.168.1.20", IngressService.targetHost(" http://192.168.1.20/ "));
        assertEquals("nas.lan", IngressService.targetHost("NAS.lan"));
        for (String bad : List.of("localhost", "127.0.0.1", "0.0.0.0", "999.1.1.1", "a b", "")) {
            assertThrows(Exception.class, () -> IngressService.targetHost(bad), bad);
        }
    }

    private static void writeSample(String name, String conf) {
        // Kept for checking with a real nginx (nginx -t) outside the unit tests.
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/tmp"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/tmp/" + name), conf);
        } catch (java.io.IOException ignored) {
        }
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

    @Test
    void canarySplitsTrafficAndLogsIt() {
        IngressRoute r = route("www.example.com", "/", IngressRoute.TlsMode.NONE);
        r.setId("11111111-2222-3333-4444-555555555555");
        r.setTargetContainerName("site-blue");
        r.setCanaryContainerName("site-green");
        r.setCanaryWeight(25);
        r.setTrafficLog(true);
        String conf = generator.generateFullConfig(new IngressConfig(), List.of(r), Map.of());
        assertTrue(conf.contains("log_format dm_traffic"));
        assertTrue(conf.contains("split_clients \"${remote_addr}${http_user_agent}"));
        assertTrue(conf.contains("25% canary;"));
        assertTrue(conf.contains("canary site-green;"));
        assertTrue(conf.contains("default site-blue;"));
        assertTrue(conf.contains("set $dm_variant $dm_target_"));
        assertTrue(
                conf.contains(
                        "access_log"
                            + " /var/log/nginx/dm-traffic-11111111-2222-3333-4444-555555555555.log"
                            + " dm_traffic;"));
        assertTrue(conf.contains("http://$dm_target_"));
    }

    @Test
    void fullCanaryAndPlainRoutesNeedNoSplit() {
        IngressRoute r = route("www.example.com", "/", IngressRoute.TlsMode.NONE);
        r.setTargetContainerName("site-blue");
        r.setCanaryContainerName("site-green");
        r.setCanaryWeight(100);
        r.setCanarySticky(false);
        String conf = generator.generateFullConfig(new IngressConfig(), List.of(r), Map.of());
        assertFalse(conf.contains("split_clients"));
        assertFalse(conf.contains("log_format dm_traffic"), "no log unless a rollout watches");
        assertTrue(conf.contains("http://site-green:8080"));

        r.setCanaryWeight(0);
        conf = generator.generateFullConfig(new IngressConfig(), List.of(r), Map.of());
        assertTrue(conf.contains("http://site-blue:8080"));
        assertFalse(conf.contains("site-green"));
    }
}
