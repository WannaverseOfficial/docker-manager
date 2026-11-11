package com.wannaverse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.DockerOperation;
import com.wannaverse.persistence.SecurityScan;
import com.wannaverse.persistence.SecurityScanRepository;
import com.wannaverse.persistence.StateSnapshot;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Security checks.
 *
 * <ul>
 *   <li><b>Trivy</b> (Aqua Security) lists known vulnerabilities in an image's packages and which
 *       version fixes each.
 *   <li><b>Docker Scout</b> does the same with Docker's own advisory data; it needs a Docker Hub
 *       account, taken from the Docker Hub entry under Registries.
 *   <li><b>Docker Bench for Security</b> checks the host and its containers against the CIS Docker
 *       Benchmark. Its scripts ship with Docker Manager and are built into a local image on the
 *       host being checked (the published image is from 2019 and no longer works).
 * </ul>
 *
 * <p>Each tool runs as a short-lived container on the host it checks, so remote hosts work the same
 * way. Fixes are never applied automatically: the UI offers them and the user confirms.
 */
@Service
public class SecurityScanService {
    private static final Logger log = LoggerFactory.getLogger(SecurityScanService.class);

    static final String TRIVY_IMAGE = "aquasec/trivy:0.67.2";
    static final String SCOUT_IMAGE = "docker/scout-cli:latest";
    static final String BENCH_COMMIT = "154869d";
    static final String BENCH_IMAGE = "dm-docker-bench:" + BENCH_COMMIT;
    private static final String SOCKET = "/var/run/docker.sock";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SecurityScanRepository scanRepository;
    private final DockerHostRepository hostRepository;
    private final DockerHostService dockerHostService;
    private final ToolRunner tools;
    private final DockerRegistryService registryService;
    private final ContainerSpecService specService;
    private final OperationTrackingService trackingService;
    private final SelfContainer selfContainer;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Scans are heavy (CPU, network for databases): two at a time. */
    private final Semaphore slots = new Semaphore(2);

    public SecurityScanService(
            SecurityScanRepository scanRepository,
            DockerHostRepository hostRepository,
            DockerHostService dockerHostService,
            ToolRunner tools,
            DockerRegistryService registryService,
            ContainerSpecService specService,
            OperationTrackingService trackingService,
            SelfContainer selfContainer) {
        this.selfContainer = selfContainer;
        this.scanRepository = scanRepository;
        this.hostRepository = hostRepository;
        this.dockerHostService = dockerHostService;
        this.tools = tools;
        this.registryService = registryService;
        this.specService = specService;
        this.trackingService = trackingService;
    }

    /** Scans still marked as running when the server stopped will never finish. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        for (SecurityScan s : scanRepository.findByStatus(SecurityScan.Status.RUNNING.name())) {
            s.setStatus(SecurityScan.Status.FAILED.name());
            s.setError("Interrupted because Docker Manager restarted");
            s.setFinishedAt(System.currentTimeMillis());
            scanRepository.save(s);
        }
    }

    // --- starting scans ----------------------------------------------------------------------

    public SecurityScan start(
            String hostId, SecurityScan.Kind kind, String image, String username) {
        DockerHost host =
                hostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Host not found"));
        if (kind != SecurityScan.Kind.BENCH) {
            if (image == null
                    || image.isBlank()
                    || !com.wannaverse.security.InputValidator.isValidImageName(image.trim())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Choose an image to scan");
            }
            if (kind == SecurityScan.Kind.SCOUT && scoutLogin() == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Docker Scout needs a Docker Hub account. Add one under Registries (type"
                                + " Docker Hub, with an access token as the password).");
            }
        }
        SecurityScan scan = new SecurityScan();
        scan.setDockerHostId(hostId);
        scan.setKind(kind.name());
        scan.setTarget(kind == SecurityScan.Kind.BENCH ? hostLabel(host) : image.trim());
        scan.setStatus(SecurityScan.Status.RUNNING.name());
        scan.setStartedAt(System.currentTimeMillis());
        scan.setStartedBy(username);
        SecurityScan saved = scanRepository.save(scan);
        executor.execute(() -> runScan(saved.getId()));
        return saved;
    }

    public boolean scoutAvailable() {
        return scoutLogin() != null;
    }

    private void runScan(String scanId) {
        SecurityScan scan = scanRepository.findById(scanId).orElse(null);
        if (scan == null) return;
        try {
            slots.acquire();
            try {
                DockerAPI api = dockerHostService.getDockerAPI(scan.getDockerHostId());
                switch (SecurityScan.Kind.valueOf(scan.getKind())) {
                    case TRIVY -> trivy(api, scan);
                    case SCOUT -> scout(api, scan);
                    case BENCH -> bench(api, scan);
                }
                scan.setStatus(SecurityScan.Status.DONE.name());
            } finally {
                slots.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scan.setStatus(SecurityScan.Status.FAILED.name());
            scan.setError("Interrupted");
        } catch (Exception e) {
            log.warn("Security scan {} failed: {}", scanId, e.getMessage());
            scan.setStatus(SecurityScan.Status.FAILED.name());
            scan.setError(truncate(e.getMessage(), 1900));
        }
        scan.setFinishedAt(System.currentTimeMillis());
        scanRepository.save(scan);
    }

    // --- Trivy -------------------------------------------------------------------------------

    private void trivy(DockerAPI api, SecurityScan scan) throws Exception {
        HostConfig hc =
                HostConfig.newHostConfig()
                        .withBinds(
                                new Bind(SOCKET, new Volume(SOCKET), AccessMode.ro),
                                // The vulnerability database is downloaded once and kept.
                                new Bind("dm-trivy-cache", new Volume("/root/.cache")));
        var result =
                tools.run(
                        api,
                        new ToolRunner.Spec(
                                TRIVY_IMAGE,
                                List.of(
                                        "image",
                                        "--quiet",
                                        "--format",
                                        "json",
                                        "--scanners",
                                        "vuln",
                                        "--timeout",
                                        "15m",
                                        scan.getTarget()),
                                Map.of(),
                                hc,
                                null,
                                20 * 60,
                                false));
        if (!result.ok() || result.stdout().isBlank()) {
            throw new IllegalStateException("Trivy failed: " + lastLines(result.stderr(), 6));
        }
        JsonNode root = JSON.readTree(result.stdout());
        List<Map<String, Object>> findings = new ArrayList<>();
        for (JsonNode r : root.path("Results")) {
            for (JsonNode v : r.path("Vulnerabilities")) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", v.path("VulnerabilityID").asText());
                f.put("package", v.path("PkgName").asText());
                f.put("installed", v.path("InstalledVersion").asText());
                f.put("fixed", v.path("FixedVersion").asText(""));
                f.put("severity", v.path("Severity").asText("UNKNOWN"));
                f.put("title", v.path("Title").asText(""));
                f.put("url", v.path("PrimaryURL").asText(""));
                f.put("target", r.path("Target").asText(""));
                findings.add(f);
            }
        }
        findings.sort(Comparator.comparingInt(f -> severityRank((String) f.get("severity"))));
        Map<String, Object> summary = severitySummary(findings);
        JsonNode os = root.path("Metadata").path("OS");
        if (!os.isMissingNode()) {
            summary.put("os", os.path("Family").asText() + " " + os.path("Name").asText());
            summary.put("osEol", os.path("EOSL").asBoolean(false));
        }
        scan.setSummaryJson(JSON.writeValueAsString(summary));
        scan.setFindingsJson(JSON.writeValueAsString(findings));
    }

    // --- Docker Scout ------------------------------------------------------------------------

    private String[] scoutLogin() {
        try {
            var auth = registryService.getAuthConfig("docker.io/library/alpine");
            if (auth == null || auth.getUsername() == null || auth.getPassword() == null)
                return null;
            return new String[] {auth.getUsername(), auth.getPassword()};
        } catch (Exception e) {
            return null;
        }
    }

    private void scout(DockerAPI api, SecurityScan scan) throws Exception {
        String[] login = scoutLogin();
        if (login == null) throw new IllegalStateException("No Docker Hub account saved");
        HostConfig hc =
                HostConfig.newHostConfig()
                        .withBinds(new Bind(SOCKET, new Volume(SOCKET), AccessMode.ro));
        var result =
                tools.run(
                        api,
                        new ToolRunner.Spec(
                                SCOUT_IMAGE,
                                List.of("cves", "--format", "sarif", "local://" + scan.getTarget()),
                                Map.of(
                                        "DOCKER_SCOUT_HUB_USER", login[0],
                                        "DOCKER_SCOUT_HUB_PASSWORD", login[1]),
                                hc,
                                null,
                                20 * 60,
                                false));
        String out = result.stdout();
        int start = out.indexOf('{');
        if (start < 0)
            throw new IllegalStateException(
                    "Docker Scout failed: " + lastLines(result.stderr() + out, 6));
        JsonNode run = JSON.readTree(out.substring(start)).path("runs").path(0);
        Map<String, JsonNode> rules = new HashMap<>();
        for (JsonNode rule : run.path("tool").path("driver").path("rules")) {
            rules.put(rule.path("id").asText(), rule);
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        for (JsonNode res : run.path("results")) {
            String id = res.path("ruleId").asText();
            JsonNode rule = rules.getOrDefault(id, JSON.createObjectNode());
            String severity = rule.path("properties").path("cvssV3_severity").asText("");
            if (severity.isBlank()) severity = levelToSeverity(res.path("level").asText(""));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", id);
            f.put("severity", severity.toUpperCase(Locale.ROOT));
            f.put("title", rule.path("shortDescription").path("text").asText(""));
            f.put("message", res.path("message").path("text").asText(""));
            f.put("url", rule.path("helpUri").asText(""));
            f.put("fixed", rule.path("properties").path("fixed_version").asText(""));
            findings.add(f);
        }
        findings.sort(Comparator.comparingInt(f -> severityRank((String) f.get("severity"))));
        scan.setSummaryJson(JSON.writeValueAsString(severitySummary(findings)));
        scan.setFindingsJson(JSON.writeValueAsString(findings));
    }

    // --- Docker Bench for Security -----------------------------------------------------------

    private void bench(DockerAPI api, SecurityScan scan) throws Exception {
        ensureBenchImage(api);
        HostConfig hc =
                HostConfig.newHostConfig()
                        .withNetworkMode("host")
                        .withPidMode("host")
                        .withUsernsMode("host")
                        .withCapAdd(Capability.AUDIT_CONTROL)
                        .withBinds(
                                new Bind("/etc", new Volume("/etc"), AccessMode.ro),
                                new Bind("/var/lib", new Volume("/var/lib"), AccessMode.ro),
                                new Bind(SOCKET, new Volume(SOCKET), AccessMode.ro));
        var result =
                tools.run(
                        api,
                        new ToolRunner.Spec(
                                BENCH_IMAGE,
                                List.of("-b", "-p"),
                                Map.of(),
                                hc,
                                null,
                                20 * 60,
                                true));
        try {
            byte[] json =
                    readFile(
                            api,
                            result.containerId(),
                            "/usr/local/bin/log/docker-bench-security.log.json");
            if (json == null) {
                throw new IllegalStateException(
                        "Docker Bench didn't produce a report: "
                                + lastLines(result.stdout() + result.stderr(), 6));
            }
            JsonNode root = JSON.readTree(json);
            List<Map<String, Object>> findings = new ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (JsonNode section : root.path("tests")) {
                for (JsonNode t : section.path("results")) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    String result1 = t.path("result").asText();
                    f.put("section", section.path("desc").asText());
                    f.put("id", t.path("id").asText());
                    f.put("title", t.path("desc").asText());
                    f.put("result", result1);
                    f.put("details", t.path("details").asText(""));
                    List<String> items = new ArrayList<>();
                    t.path("items").forEach(i -> items.add(i.asText()));
                    f.put("items", items);
                    f.put("remediation", t.path("remediation").asText(""));
                    f.put("impact", t.path("remediation-impact").asText(""));
                    String fix = fixFor(t.path("desc").asText(), result1);
                    if (fix != null) f.put("fix", fix);
                    String daemon = daemonSetting(t.path("desc").asText(), result1);
                    if (daemon != null) f.put("daemonSetting", daemon);
                    counts.merge(result1, 1, Integer::sum);
                    findings.add(f);
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>(counts);
            summary.put("score", root.path("score").asInt());
            summary.put("checks", root.path("checks").asInt());
            summary.put("daemonJson", daemonJsonSuggestion(findings));
            scan.setSummaryJson(JSON.writeValueAsString(summary));
            scan.setFindingsJson(JSON.writeValueAsString(findings));
        } finally {
            tools.remove(api, result.containerId());
        }
    }

    /** Container-level findings with a one-click fix, recognised by their description. */
    static String fixFor(String desc, String result) {
        if (!"WARN".equals(result)) return null;
        String d = desc.toLowerCase(Locale.ROOT);
        if (d.contains("memory usage for containers is limited")) return "MEMORY_LIMIT";
        if (d.contains("container is restricted from acquiring additional privileges"))
            return "NO_NEW_PRIVILEGES";
        if (d.contains("pids cgroup limit")) return "PIDS_LIMIT";
        return null;
    }

    /** Daemon-level findings and the daemon.json setting that addresses each. */
    static String daemonSetting(String desc, String result) {
        if (!"WARN".equals(result)) return null;
        String d = desc.toLowerCase(Locale.ROOT);
        if (d.contains("network traffic is restricted between containers")) return "\"icc\": false";
        if (d.contains("live restore")) return "\"live-restore\": true";
        if (d.contains("userland proxy")) return "\"userland-proxy\": false";
        if (d.contains("containers are restricted from acquiring new privileges"))
            return "\"no-new-privileges\": true";
        if (d.contains("centralized and remote logging")) return null;
        return null;
    }

    private static String daemonJsonSuggestion(List<Map<String, Object>> findings) {
        List<String> settings = new ArrayList<>();
        for (var f : findings) {
            Object s = f.get("daemonSetting");
            if (s != null && !settings.contains(s.toString())) settings.add(s.toString());
        }
        if (settings.isEmpty()) return null;
        return "{\n  " + String.join(",\n  ", settings) + "\n}";
    }

    private void ensureBenchImage(DockerAPI api) throws Exception {
        try {
            api.inspectImage(BENCH_IMAGE);
            return;
        } catch (com.github.dockerjava.api.exception.NotFoundException missing) {
            // build below
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(buf)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            var resolver = new PathMatchingResourcePatternResolver();
            Resource base = resolver.getResource("classpath:bench/");
            String baseUrl = base.getURL().toString();
            for (Resource r : resolver.getResources("classpath:bench/**")) {
                if (!r.isReadable()) continue;
                String url = r.getURL().toString();
                if (url.endsWith("/")) continue;
                String rel = url.substring(url.indexOf("bench/") + "bench/".length());
                if (!url.startsWith(baseUrl) && rel.isBlank()) continue;
                byte[] content;
                try (InputStream in = r.getInputStream()) {
                    content = in.readAllBytes();
                }
                TarArchiveEntry e = new TarArchiveEntry(rel);
                e.setSize(content.length);
                e.setMode(rel.endsWith(".sh") ? 0100755 : 0100644);
                tar.putArchiveEntry(e);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        }
        log.info("Building {} on this host", BENCH_IMAGE);
        api.client()
                .buildImageCmd(new ByteArrayInputStream(buf.toByteArray()))
                .withTags(Set.of(BENCH_IMAGE))
                .withPull(true)
                .start()
                .awaitImageId();
    }

    private static byte[] readFile(DockerAPI api, String containerId, String path) {
        try (InputStream in = api.client().copyArchiveFromContainerCmd(containerId, path).exec();
                TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null || !entry.isFile()) return null;
            return tar.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    // --- fixes -------------------------------------------------------------------------------

    public enum Fix {
        NO_NEW_PRIVILEGES,
        PIDS_LIMIT,
        MEMORY_LIMIT
    }

    /**
     * Applies one hardening setting to a container by recreating it (volumes and other settings
     * stay; if the new one doesn't start, the original comes back). Recorded in History, so it can
     * be undone.
     */
    public String applyFix(String hostId, String containerRef, Fix fix, Long value) {
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        var inspect = api.inspectContainer(containerRef);
        selfContainer.protect(inspect.getId(), "recreate");
        var labels = inspect.getConfig().getLabels();
        String project =
                labels == null ? null : labels.get(ContainerSpecService.COMPOSE_PROJECT_LABEL);
        if (project != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This container belongs to the stack "
                            + project
                            + ". Add this to its service in the"
                            + " compose file and deploy: "
                            + composeSnippet(fix, value));
        }
        var spec = specService.specFrom(api, inspect);
        java.util.function.Consumer<HostConfig> tweak;
        switch (fix) {
            case NO_NEW_PRIVILEGES ->
                    tweak =
                            hc -> {
                                List<String> opts =
                                        new ArrayList<>(
                                                hc.getSecurityOpts() == null
                                                        ? List.of()
                                                        : hc.getSecurityOpts());
                                opts.removeIf(o -> o.startsWith("no-new-privileges"));
                                opts.add("no-new-privileges:true");
                                hc.withSecurityOpts(opts);
                            };
            case PIDS_LIMIT -> {
                long limit = value == null || value < 32 ? 1024 : value;
                tweak = hc -> hc.withPidsLimit(limit);
            }
            case MEMORY_LIMIT -> {
                if (value == null || value < 16) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Enter a memory limit of at least 16 MB");
                }
                spec.setMemoryLimitMb(value);
                tweak = null;
            }
            default -> throw new IllegalArgumentException("Unknown fix");
        }
        String name =
                inspect.getName() == null ? containerRef : inspect.getName().replaceFirst("^/", "");
        DockerOperation operation =
                trackingService.beginOperation(
                        hostId,
                        DockerOperation.OperationType.CONTAINER_UPDATE,
                        inspect.getId(),
                        name);
        trackingService.captureContainerState(
                operation, inspect.getId(), StateSnapshot.SnapshotType.BEFORE, hostId);
        try {
            String newId = specService.recreate(api, inspect.getId(), spec, false, true, tweak);
            operation.setResourceId(newId);
            trackingService.captureContainerState(
                    operation, newId, StateSnapshot.SnapshotType.AFTER, hostId);
            trackingService.completeOperation(operation, true, null);
            return newId;
        } catch (RuntimeException e) {
            trackingService.completeOperation(operation, false, e.getMessage());
            throw e;
        }
    }

    static String composeSnippet(Fix fix, Long value) {
        return switch (fix) {
            case NO_NEW_PRIVILEGES -> "security_opt: [\"no-new-privileges:true\"]";
            case PIDS_LIMIT -> "pids_limit: " + (value == null || value < 32 ? 1024 : value);
            case MEMORY_LIMIT -> "mem_limit: " + (value == null ? 512 : value) + "m";
        };
    }

    // --- reading -----------------------------------------------------------------------------

    public List<SecurityScan> list(String hostId) {
        return scanRepository.findTop200ByDockerHostIdOrderByStartedAtDesc(hostId);
    }

    public SecurityScan get(String id) {
        return scanRepository
                .findById(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scan not found"));
    }

    // --- helpers -----------------------------------------------------------------------------

    static int severityRank(String s) {
        return switch (s == null ? "" : s.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private static String levelToSeverity(String level) {
        return switch (level) {
            case "error" -> "HIGH";
            case "warning" -> "MEDIUM";
            case "note" -> "LOW";
            default -> "UNKNOWN";
        };
    }

    static Map<String, Object> severitySummary(List<Map<String, Object>> findings) {
        Map<String, Object> s = new LinkedHashMap<>();
        for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")) s.put(sev, 0);
        int fixable = 0;
        for (var f : findings) {
            String sev = String.valueOf(f.get("severity")).toUpperCase(Locale.ROOT);
            if (!s.containsKey(sev)) sev = "UNKNOWN";
            s.put(sev, (Integer) s.get(sev) + 1);
            Object fixed = f.get("fixed");
            if (fixed != null && !fixed.toString().isBlank()) fixable++;
        }
        s.put("total", findings.size());
        s.put("fixable", fixable);
        return s;
    }

    private static String hostLabel(DockerHost host) {
        return host.getName() != null && !host.getName().isBlank()
                ? host.getName()
                : host.getDockerHostUrl();
    }

    private static String lastLines(String text, int n) {
        if (text == null) return "";
        String[] lines = text.strip().split("\n");
        return String.join(
                        " ", Arrays.copyOfRange(lines, Math.max(0, lines.length - n), lines.length))
                .strip();
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
