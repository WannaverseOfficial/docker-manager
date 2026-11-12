package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import com.wannaverse.persistence.IngressConfig;
import com.wannaverse.persistence.IngressRoute;
import com.wannaverse.persistence.ObservabilityStack;
import com.wannaverse.persistence.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

class ObservabilityConfigsTest {

    private static ObservabilityStack stack(boolean mimir, boolean loki, boolean tempo) {
        ObservabilityStack s = new ObservabilityStack();
        s.setDockerHostId("host-1");
        s.setMimirEnabled(mimir);
        s.setLokiEnabled(loki);
        s.setTempoEnabled(tempo);
        s.setCollectLogs(loki);
        s.setCollectMetrics(true);
        s.setOtlpEnabled(true);
        s.setMetricsRetentionDays(30);
        s.setLogsRetentionDays(14);
        s.setTracesRetentionDays(7);
        s.setAccess("LOCAL");
        s.setLocalPort(3000);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        return (Map<String, Object>) new Yaml().load(text);
    }

    @Test
    void everyYamlFileParsesForEveryCombination() {
        for (boolean mimir : List.of(true, false)) {
            for (boolean loki : List.of(true, false)) {
                for (boolean tempo : List.of(true, false)) {
                    ObservabilityStack s = stack(mimir, loki, tempo);
                    assertNotNull(yaml(ObservabilityConfigs.prometheus(s)));
                    assertNotNull(yaml(ObservabilityConfigs.mimir(s)));
                    assertNotNull(yaml(ObservabilityConfigs.loki(s)));
                    assertNotNull(yaml(ObservabilityConfigs.tempo(s)));
                    assertNotNull(yaml(ObservabilityConfigs.grafanaDatasources(s)));
                    assertNotNull(yaml(ObservabilityConfigs.grafanaDashboardProvider()));
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void prometheusWritesToMimirOnlyWhenMimirRuns() {
        Map<String, Object> with = yaml(ObservabilityConfigs.prometheus(stack(true, true, true)));
        var writes = (List<Map<String, Object>>) with.get("remote_write");
        assertEquals("http://dm-obs-mimir:9009/api/v1/push", writes.getFirst().get("url"));
        assertTrue(
                ObservabilityConfigs.prometheusArgs(stack(true, true, true))
                        .contains("--storage.tsdb.retention.time=2d"));

        ObservabilityStack alone = stack(false, true, true);
        assertNull(yaml(ObservabilityConfigs.prometheus(alone)).get("remote_write"));
        List<String> args = ObservabilityConfigs.prometheusArgs(alone);
        assertTrue(args.contains("--storage.tsdb.retention.time=30d"));
        // Tempo's span metrics and apps' OTLP metrics then go into Prometheus itself.
        assertTrue(args.contains("--web.enable-remote-write-receiver"));
        assertTrue(args.contains("--web.enable-otlp-receiver"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retentionReachesEachComponent() {
        ObservabilityStack s = stack(true, true, true);
        s.setMetricsRetentionDays(90);
        s.setLogsRetentionDays(10);
        s.setTracesRetentionDays(3);
        var mimirLimits = (Map<String, Object>) yaml(ObservabilityConfigs.mimir(s)).get("limits");
        assertEquals("90d", mimirLimits.get("compactor_blocks_retention_period"));
        var lokiLimits =
                (Map<String, Object>) yaml(ObservabilityConfigs.loki(s)).get("limits_config");
        assertEquals("240h", lokiLimits.get("retention_period"));
        var overrides =
                (Map<String, Object>)
                        ((Map<String, Object>) yaml(ObservabilityConfigs.tempo(s)).get("overrides"))
                                .get("defaults");
        assertEquals(
                "72h", ((Map<String, Object>) overrides.get("compaction")).get("block_retention"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void datasourcesFollowTheComponents() {
        var all =
                (List<Map<String, Object>>)
                        yaml(ObservabilityConfigs.grafanaDatasources(stack(true, true, true)))
                                .get("datasources");
        assertEquals(
                List.of("dm-metrics", "dm-prometheus", "dm-loki", "dm-tempo"),
                all.stream().map(d -> d.get("uid")).toList());
        assertEquals("http://dm-obs-mimir:9009/prometheus", all.getFirst().get("url"));

        var minimal =
                (List<Map<String, Object>>)
                        yaml(ObservabilityConfigs.grafanaDatasources(stack(false, false, false)))
                                .get("datasources");
        assertEquals(1, minimal.size());
        assertEquals("http://dm-obs-prometheus:9090", minimal.getFirst().get("url"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceIdLinkRegexWorksInBrowsers() {
        // Grafana runs this regex in the browser, where inline flags such as (?i) are invalid.
        var loki =
                ((List<Map<String, Object>>)
                                yaml(ObservabilityConfigs.grafanaDatasources(
                                                stack(true, true, true)))
                                        .get("datasources"))
                        .stream().filter(d -> "dm-loki".equals(d.get("uid"))).findFirst().get();
        var fields =
                (List<Map<String, Object>>)
                        ((Map<String, Object>) loki.get("jsonData")).get("derivedFields");
        String regex = (String) fields.getFirst().get("matcherRegex");
        assertFalse(regex.contains("(?"), regex);
        var m = Pattern.compile(regex).matcher("msg=done traceId=5b8efff798038103d269b633813fc60c");
        assertTrue(m.find());
        assertEquals("5b8efff798038103d269b633813fc60c", m.group(1));
        // Grafana expands $VAR in provisioning files; $$ is how a literal $ is written.
        assertEquals("$${__value.raw}", fields.getFirst().get("url"));
    }

    @Test
    void alloyOnlyWiresWhatRuns() {
        String full = ObservabilityConfigs.alloy(stack(true, true, true));
        assertTrue(full.contains("loki.source.docker \"containers\""));
        assertTrue(full.contains("otelcol.exporter.otlphttp \"tempo\""));
        assertTrue(full.contains("otelcol.exporter.otlphttp \"loki\""));
        assertTrue(full.contains("endpoint = \"http://dm-obs-mimir:9009/otlp\""));

        String noBackends = ObservabilityConfigs.alloy(stack(false, false, false));
        assertFalse(noBackends.contains("loki.source.docker"));
        assertFalse(noBackends.contains("otelcol.exporter.otlphttp \"tempo\""));
        assertTrue(noBackends.contains("logs    = []"));
        assertTrue(noBackends.contains("endpoint = \"http://dm-obs-prometheus:9090/api/v1/otlp\""));

        ObservabilityStack nothing = stack(false, false, false);
        nothing.setOtlpEnabled(false);
        assertFalse(ObservabilityConfigs.alloyNeeded(nothing));
    }

    @Test
    void grafanaTokenIsSignedForGrafanaToVerify() throws Exception {
        KeyPair keys = ObservabilityService.newKeys();
        String pem = ObservabilityService.pem(keys.getPublic().getEncoded());
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n"));
        assertTrue(pem.endsWith("-----END PUBLIC KEY-----\n"));

        // Read the key back from the PEM, as Grafana does.
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        PublicKey publicKey =
                KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));

        EncryptionService encryption =
                new EncryptionService("a-test-key-that-is-long-enough-for-the-service-1234");
        ObservabilityService service =
                new ObservabilityService(null, null, encryption, null, null, null, null);
        ObservabilityStack s = stack(true, true, true);
        s.setSigningKey(
                encryption.encrypt(
                        Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())));
        User user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        user.setEmail("alice@example.org");

        Claims claims =
                Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(service.token(s, user))
                        .getPayload();
        assertEquals("docker-manager", claims.getIssuer());
        assertEquals("user-1", claims.getSubject());
        assertEquals("alice", claims.get("login"));
        assertEquals("alice@example.org", claims.get("email"));
        assertEquals("GrafanaAdmin", claims.get("role"));
        long hours = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 3_600_000;
        assertTrue(hours >= 7 && hours <= 8, "expires in about 8 hours, was " + hours);
    }

    @Test
    void portConflictsReadAsPlainMessages() {
        String docker =
                "Status 500: {\"message\":\"failed to set up container networking: driver failed"
                        + " programming external connectivity on endpoint dm-obs-grafana (59b94d):"
                        + " Bind for 0.0.0.0:3000 failed: port is already allocated\"}";
        String m = ObservabilityService.message(new RuntimeException(docker));
        assertTrue(m.startsWith("Port 3000 is already used on this host"), m);
        String linux =
                "Error starting userland proxy: listen tcp4 127.0.0.1:3300: bind: address already"
                        + " in use";
        assertTrue(
                ObservabilityService.message(new RuntimeException(linux)).startsWith("Port 3300"));
        assertEquals("other", ObservabilityService.message(new RuntimeException("other")));
    }

    // --- the ingress side ----------------------------------------------------------------------

    private static IngressRoute grafanaRoute() {
        IngressRoute r = new IngressRoute();
        r.setId("route-1");
        r.setHostname("grafana.example.com");
        r.setPathPrefix("/");
        r.setTargetContainerName("dm-obs-grafana");
        r.setTargetPort(3000);
        r.setTlsMode(IngressRoute.TlsMode.NONE);
        r.setEnabled(true);
        r.setAuthType(IngressRoute.AuthType.GRAFANA_SSO);
        r.setAuthConfig("https://dm.example.com/observability/grafana/?host=h1");
        return r;
    }

    @Test
    void grafanaRouteGetsDockerManagerSignIn() {
        String conf =
                new NginxConfigGenerator()
                        .generateFullConfig(new IngressConfig(), List.of(grafanaRoute()), Map.of());
        assertTrue(conf.contains("js_set $dm_grafana_token dmauth.grafanaToken;"));
        assertTrue(conf.contains("location = /.dm-sso/route-1/callback {"));
        assertTrue(conf.contains("location = /.dm-sso/signed-out {"));
        assertTrue(conf.contains("location = /login {"));
        assertTrue(
                conf.contains(
                        "set $dm_sso_url"
                                + " \"https://dm.example.com/observability/grafana/?host=h1\";"));
        // Both the sign-in page and every other request carry only the cookie's token.
        assertEquals(
                2,
                conf.split(
                                        Pattern.quote(
                                                "proxy_set_header X-DM-Grafana-Token"
                                                        + " $dm_grafana_token;"),
                                        -1)
                                .length
                        - 1);
        assertFalse(conf.contains("/.dm-auth/route-1/"), "not a password-protected route");
    }

    @Test
    void routesWithoutSignInAreUnchanged() {
        IngressRoute plain = grafanaRoute();
        plain.setAuthType(null);
        plain.setAuthConfig(null);
        String conf =
                new NginxConfigGenerator()
                        .generateFullConfig(new IngressConfig(), List.of(plain), Map.of());
        assertFalse(conf.contains("/.dm-sso/"));
        assertFalse(conf.contains("X-DM-Grafana-Token"));
    }

    @Test
    void signInUrlRejectsAnythingNginxCouldReadAsSyntax() {
        Pattern p = IngressService.SIGN_IN_URL;
        assertTrue(p.matcher("https://dm.example.com/observability/grafana/?host=abc-1").matches());
        assertTrue(p.matcher("http://localhost:8112/observability/grafana/?host=a").matches());
        for (String bad :
                List.of(
                        "https://dm.example.com/\"; return 200 x; #",
                        "https://dm.example.com/$host",
                        "https://dm.example.com/a b",
                        "https://dm.example.com/{x}",
                        "javascript:alert(1)",
                        "https://dm.example.com/;x")) {
            assertFalse(p.matcher(bad).matches(), bad);
        }
    }
}
