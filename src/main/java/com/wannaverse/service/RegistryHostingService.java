package com.wannaverse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import com.wannaverse.persistence.DockerRegistry;
import com.wannaverse.persistence.HostedRegistry;
import com.wannaverse.persistence.HostedRegistryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Runs a private Docker registry (the official {@code registry:3}, CNCF Distribution) on a host.
 *
 * <ul>
 *   <li><b>Only this server</b>: published on 127.0.0.1, which Docker allows without TLS, so the
 *       server can push to and pull from {@code localhost:PORT}.
 *   <li><b>HTTPS on a hostname</b>: served through the built-in ingress with a Let's Encrypt
 *       certificate, so other machines can use it.
 * </ul>
 *
 * <p>Users sign in with passwords kept as bcrypt hashes in the registry's htpasswd file (the
 * registry picks up changes without a restart). Docker Manager has its own account to list
 * repositories and pull, and adds the registry under Registries so deployments can pull from it.
 */
@Service
public class RegistryHostingService {
    private static final Logger log = LoggerFactory.getLogger(RegistryHostingService.class);
    static final String IMAGE = "registry:3";
    static final String CURL_IMAGE = "curlimages/curl:8.11.1";
    static final String CONTAINER = "dm-registry";
    static final String VOLUME = "dm-registry-data";
    static final String ROBOT = "dm-robot";
    private static final String MANIFEST_TYPES =
            "application/vnd.oci.image.index.v1+json,"
                    + " application/vnd.docker.distribution.manifest.list.v2+json,"
                    + " application/vnd.oci.image.manifest.v1+json,"
                    + " application/vnd.docker.distribution.manifest.v2+json";
    private static final Pattern USER = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern REPO =
            Pattern.compile(
                    "^[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*)*$");
    private static final Pattern TAG = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HostedRegistryRepository repository;
    private final DockerHostService dockerHostService;
    private final EncryptionService encryptionService;
    private final DockerRegistryService registryService;
    private final IngressService ingressService;
    private final ToolRunner tools;
    private final com.wannaverse.persistence.IngressRouteRepository routes;
    private final com.wannaverse.persistence.IngressCertificateRepository certificates;

    public RegistryHostingService(
            HostedRegistryRepository repository,
            DockerHostService dockerHostService,
            EncryptionService encryptionService,
            DockerRegistryService registryService,
            IngressService ingressService,
            ToolRunner tools,
            com.wannaverse.persistence.IngressRouteRepository routes,
            com.wannaverse.persistence.IngressCertificateRepository certificates) {
        this.routes = routes;
        this.certificates = certificates;
        this.repository = repository;
        this.dockerHostService = dockerHostService;
        this.encryptionService = encryptionService;
        this.registryService = registryService;
        this.ingressService = ingressService;
        this.tools = tools;
    }

    public Optional<HostedRegistry> get(String hostId) {
        return repository.findByDockerHostId(hostId);
    }

    /** HTTPS state of a public registry: its certificate's status and message, or null. */
    public Map<String, String> certificate(HostedRegistry r) {
        if (r.getIngressRouteId() == null) return null;
        var route = routes.findById(r.getIngressRouteId()).orElse(null);
        if (route == null)
            return Map.of("status", "MISSING", "message", "The ingress route was removed");
        if (route.getCertificateId() == null) return Map.of("status", "PENDING", "message", "");
        return certificates
                .findById(route.getCertificateId())
                .map(
                        c -> {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put("status", c.getStatus().name());
                            String msg =
                                    c.getLastRenewalError() != null
                                            ? c.getLastRenewalError()
                                            : c.getStatusMessage();
                            m.put("message", msg == null ? "" : msg);
                            return m;
                        })
                .orElse(Map.of("status", "PENDING", "message", ""));
    }

    /** The address to push to, e.g. localhost:5000 or registry.example.com. */
    public static String address(HostedRegistry r) {
        return "PUBLIC".equals(r.getMode()) ? r.getHostname() : "localhost:" + r.getLocalPort();
    }

    // --- create and remove -------------------------------------------------------------------

    public HostedRegistry create(
            String hostId,
            String mode,
            String hostname,
            Integer port,
            String firstUser,
            String firstPassword,
            String userId,
            String username) {
        if (repository.findByDockerHostId(hostId).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This host already has a registry");
        }
        boolean pub = "PUBLIC".equalsIgnoreCase(mode);
        int localPort = port == null ? 5000 : port;
        if (!pub && (localPort < 1024 || localPort > 65535)) {
            throw bad("Choose a port between 1024 and 65535");
        }
        if (pub
                && (hostname == null
                        || !com.wannaverse.security.InputValidator.isValidHostname(hostname.trim())
                        || !hostname.contains("."))) {
            throw bad("Enter the registry's full hostname, for example registry.example.com");
        }
        checkUser(firstUser, firstPassword);

        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        if (NginxApplier.findContainer(api, CONTAINER) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A container named " + CONTAINER + " already exists on this host");
        }
        String robotPassword = randomPassword();
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        String htpasswd =
                ROBOT
                        + ":"
                        + bcrypt.encode(robotPassword)
                        + "\n"
                        + firstUser
                        + ":"
                        + bcrypt.encode(firstPassword)
                        + "\n";

        HostedRegistry r = new HostedRegistry();
        r.setDockerHostId(hostId);
        r.setContainerName(CONTAINER);
        r.setVolumeName(VOLUME);
        r.setMode(pub ? "PUBLIC" : "LOCAL");
        r.setHostname(pub ? hostname.trim().toLowerCase(Locale.ROOT) : null);
        r.setLocalPort(pub ? null : localPort);
        r.setHtpasswd(htpasswd);
        r.setRobotPassword(encryptionService.encrypt(robotPassword));
        r.setCreatedAt(System.currentTimeMillis());

        ToolRunner.ensureImage(api, IMAGE);
        try {
            api.client().inspectVolumeCmd(VOLUME).exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            api.client()
                    .createVolumeCmd()
                    .withName(VOLUME)
                    .withLabels(Map.of("com.wannaverse.registry", hostId))
                    .exec();
        }
        HostConfig hc =
                HostConfig.newHostConfig()
                        .withBinds(new Bind(VOLUME, new Volume("/var/lib/registry")))
                        .withRestartPolicy(RestartPolicy.unlessStoppedRestart());
        var create =
                api.client()
                        .createContainerCmd(IMAGE)
                        .withName(CONTAINER)
                        .withLabels(Map.of("com.wannaverse.registry", hostId))
                        .withEnv(
                                "REGISTRY_AUTH=htpasswd",
                                "REGISTRY_AUTH_HTPASSWD_REALM=Docker Registry",
                                "REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd",
                                "REGISTRY_STORAGE_DELETE_ENABLED=true",
                                "REGISTRY_HTTP_SECRET=" + randomPassword());
        if (!pub) {
            Ports ports = new Ports();
            ports.bind(ExposedPort.tcp(5000), Ports.Binding.bindIpAndPort("127.0.0.1", localPort));
            hc.withPortBindings(ports);
            create.withExposedPorts(ExposedPort.tcp(5000));
        }
        String id = create.withHostConfig(hc).exec().getId();
        try {
            api.putFiles(
                    id, Map.of("/auth/htpasswd", htpasswd.getBytes(StandardCharsets.UTF_8)), 0644);
            api.startContainer(id);
            r = repository.save(r);
            if (pub) {
                var route =
                        ingressService.exposeApp(
                                hostId,
                                new IngressService.ExposeAppRequest(
                                        id,
                                        CONTAINER,
                                        r.getHostname(),
                                        "/",
                                        5000,
                                        "HTTP",
                                        "LETS_ENCRYPT",
                                        null,
                                        false,
                                        null,
                                        null,
                                        true,
                                        null,
                                        true,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null),
                                userId,
                                username);
                r.setIngressRouteId(route.getId());
            }
            DockerRegistry entry =
                    registryService.createRegistry(
                            new DockerRegistryService.RegistryCreateRequest(
                                    entryName(hostId),
                                    address(r),
                                    DockerRegistry.RegistryType.PRIVATE,
                                    ROBOT,
                                    robotPassword,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    false));
            r.setRegistryEntryId(entry.getId());
            return repository.save(r);
        } catch (RuntimeException e) {
            // Leave nothing half-made behind; the data volume stays (it's empty, or someone's
            // data).
            try {
                api.client().removeContainerCmd(id).withForce(true).exec();
            } catch (Exception ignored) {
            }
            if (r.getId() != null) {
                if (r.getIngressRouteId() != null) {
                    try {
                        ingressService.removeRoute(r.getIngressRouteId(), false, userId, username);
                    } catch (Exception ignored) {
                    }
                }
                repository.delete(r);
            }
            throw e;
        }
    }

    public void remove(String hostId, boolean deleteImages, String userId, String username) {
        HostedRegistry r = require(hostId);
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        if (r.getIngressRouteId() != null) {
            try {
                ingressService.removeRoute(r.getIngressRouteId(), true, userId, username);
            } catch (Exception e) {
                log.warn("Could not remove the registry's route: {}", e.getMessage());
            }
        }
        String id = NginxApplier.findContainer(api, r.getContainerName());
        if (id != null) api.client().removeContainerCmd(id).withForce(true).exec();
        if (deleteImages) {
            try {
                api.removeVolume(r.getVolumeName());
            } catch (Exception e) {
                log.warn("Could not remove {}: {}", r.getVolumeName(), e.getMessage());
            }
        }
        if (r.getRegistryEntryId() != null) {
            try {
                registryService.deleteRegistry(r.getRegistryEntryId());
            } catch (Exception ignored) {
            }
        }
        repository.delete(r);
    }

    // --- users -------------------------------------------------------------------------------

    public List<String> users(String hostId) {
        List<String> names = new ArrayList<>();
        for (String line : require(hostId).getHtpasswd().split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith(ROBOT + ":")) names.add(line.substring(0, colon));
        }
        return names;
    }

    /** Adds a user, or sets a new password for an existing one. */
    public void setUser(String hostId, String user, String password) {
        checkUser(user, password);
        if (ROBOT.equals(user)) throw bad("That name is reserved");
        HostedRegistry r = require(hostId);
        List<String> lines = new ArrayList<>();
        for (String line : r.getHtpasswd().split("\n")) {
            if (!line.isBlank() && !line.startsWith(user + ":")) lines.add(line);
        }
        lines.add(user + ":" + new BCryptPasswordEncoder().encode(password));
        writeUsers(r, lines);
    }

    public void removeUser(String hostId, String user) {
        if (ROBOT.equals(user)) throw bad("That account is Docker Manager's own");
        HostedRegistry r = require(hostId);
        List<String> lines = new ArrayList<>();
        for (String line : r.getHtpasswd().split("\n")) {
            if (!line.isBlank() && !line.startsWith(user + ":")) lines.add(line);
        }
        if (lines.size() < 2) throw bad("Keep at least one user");
        writeUsers(r, lines);
    }

    private void writeUsers(HostedRegistry r, List<String> lines) {
        String htpasswd = String.join("\n", lines) + "\n";
        DockerAPI api = dockerHostService.getDockerAPI(r.getDockerHostId());
        String id = containerId(api, r);
        // The registry notices the changed file by itself; no restart, no interruption.
        api.putFiles(id, Map.of("/auth/htpasswd", htpasswd.getBytes(StandardCharsets.UTF_8)), 0644);
        r.setHtpasswd(htpasswd);
        repository.save(r);
    }

    // --- browsing ----------------------------------------------------------------------------

    public record Repository(String name, List<String> tags) {}

    public record Tag(String tag, String digest, Long size, String mediaType, int platforms) {}

    public List<Repository> repositories(String hostId) {
        HostedRegistry r = require(hostId);
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        String id = containerId(api, r);
        // The registry hands out at most 1000 names per page (a larger n is refused with 400).
        List<String> names = new ArrayList<>();
        String last = null;
        for (int page = 0; page < 100; page++) {
            JsonNode catalog =
                    getJson(
                            api,
                            r,
                            id,
                            "/v2/_catalog?n=1000"
                                    + (last == null
                                            ? ""
                                            : "&last="
                                                    + java.net.URLEncoder.encode(
                                                            last, StandardCharsets.UTF_8)));
            int count = 0;
            for (JsonNode n : catalog.path("repositories")) {
                names.add(n.asText());
                last = n.asText();
                count++;
            }
            if (count < 1000) break;
        }
        List<Repository> repos = new ArrayList<>();
        for (String name : names) {
            if (!REPO.matcher(name).matches()) continue;
            JsonNode tags = getJson(api, r, id, "/v2/" + name + "/tags/list");
            List<String> list = new ArrayList<>();
            tags.path("tags").forEach(t -> list.add(t.asText()));
            Collections.sort(list);
            if (!list.isEmpty()) repos.add(new Repository(name, list));
        }
        return repos;
    }

    public Tag tag(String hostId, String repo, String tag) {
        checkRef(repo, tag);
        HostedRegistry r = require(hostId);
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        var out = wget(api, r, containerId(api, r), "/v2/" + repo + "/manifests/" + tag, true);
        String digest = header(out.stderr(), "Docker-Content-Digest");
        try {
            JsonNode m = JSON.readTree(out.stdout());
            String type = m.path("mediaType").asText("");
            if (m.has("manifests")) {
                return new Tag(tag, digest, null, type, m.path("manifests").size());
            }
            long size = m.path("config").path("size").asLong(0);
            for (JsonNode l : m.path("layers")) size += l.path("size").asLong(0);
            return new Tag(tag, digest, size, type, 1);
        } catch (Exception e) {
            return new Tag(tag, digest, null, null, 0);
        }
    }

    /** Deletes a tag (its manifest). The space comes back after "Reclaim space". */
    public void deleteTag(String hostId, String repo, String tag) {
        Tag t = tag(hostId, repo, tag);
        if (t.digest() == null || !t.digest().matches("^sha256:[a-f0-9]{64}$")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That tag doesn't exist");
        }
        HostedRegistry r = require(hostId);
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        String id = containerId(api, r);
        // The registry image has no HTTP client that can send DELETE, so a curl container
        // borrows its network.
        var result =
                tools.run(
                        api,
                        new ToolRunner.Spec(
                                CURL_IMAGE,
                                List.of(
                                        "-s",
                                        "-o",
                                        "/dev/null",
                                        "-w",
                                        "%{http_code}",
                                        "-X",
                                        "DELETE",
                                        "-H",
                                        "Authorization: Basic " + robotToken(r),
                                        "http://127.0.0.1:5000/v2/"
                                                + repo
                                                + "/manifests/"
                                                + t.digest()),
                                Map.of(),
                                HostConfig.newHostConfig().withNetworkMode("container:" + id),
                                null,
                                60,
                                false));
        if (!result.stdout().strip().startsWith("202")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The registry refused: HTTP " + result.stdout().strip());
        }
    }

    /**
     * Frees the space of deleted images. The registry is stopped for the few seconds this takes
     * (garbage collection must not run while images are being pushed), then started again.
     */
    public String reclaimSpace(String hostId) {
        HostedRegistry r = require(hostId);
        DockerAPI api = dockerHostService.getDockerAPI(hostId);
        String id = containerId(api, r);
        api.client().stopContainerCmd(id).withTimeout(30).exec();
        try {
            var result =
                    tools.run(
                            api,
                            new ToolRunner.Spec(
                                    IMAGE,
                                    List.of(
                                            "b=$(du -sk /var/lib/registry | cut -f1); if ! registry"
                                                + " garbage-collect /etc/distribution/config.yml"
                                                + " --delete-untagged > /tmp/gc.log 2>&1; then tail"
                                                + " -5 /tmp/gc.log; exit 1; fi; a=$(du -sk"
                                                + " /var/lib/registry | cut -f1); n=$(grep -c"
                                                + " 'Deleting blob' /tmp/gc.log); echo \"DMRESULT"
                                                + " $b $a $n\""),
                                    Map.of(),
                                    HostConfig.newHostConfig()
                                            .withBinds(
                                                    new Bind(
                                                            r.getVolumeName(),
                                                            new Volume("/var/lib/registry"))),
                                    List.of("sh", "-c"),
                                    30 * 60,
                                    false));
            String out = (result.stdout() + "\n" + result.stderr()).strip();
            if (!result.ok())
                throw new IllegalStateException("Garbage collection failed: " + tail(out));
            var m = java.util.regex.Pattern.compile("DMRESULT (\\d+) (\\d+) (\\d+)").matcher(out);
            if (!m.find()) return "Done";
            long freed =
                    Math.max(0, Long.parseLong(m.group(1)) - Long.parseLong(m.group(2))) * 1024;
            int blobs = Integer.parseInt(m.group(3));
            if (blobs == 0) return "Nothing to reclaim: every stored layer is still used by a tag.";
            return "Freed "
                    + humanBytes(freed)
                    + " ("
                    + blobs
                    + (blobs == 1 ? " file" : " files")
                    + " deleted).";
        } finally {
            api.startContainer(id);
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = b;
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[i]);
    }

    private JsonNode getJson(DockerAPI api, HostedRegistry r, String id, String path) {
        var out = wget(api, r, id, path, false);
        if (!out.ok()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "The registry didn't answer: " + tail(out.stderr()));
        }
        try {
            return JSON.readTree(out.stdout());
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Unexpected answer from the registry");
        }
    }

    /** GET inside the registry container, so it works on any host and in either mode. */
    private DockerAPI.ExecOutput wget(
            DockerAPI api, HostedRegistry r, String id, String path, boolean manifest) {
        List<String> cmd = new ArrayList<>(List.of("wget", "-q", "-O", "-"));
        if (manifest) {
            cmd.add("-S");
            cmd.add("--header");
            cmd.add("Accept: " + MANIFEST_TYPES);
        }
        cmd.add("--header");
        cmd.add("Authorization: Basic " + robotToken(r));
        cmd.add("http://127.0.0.1:5000" + path);
        var out = api.execFull(id, Map.of(), 60, cmd.toArray(String[]::new));
        return new DockerAPI.ExecOutput(out.exitCode(), out.stdout(), out.stderr());
    }

    private static String header(String headers, String name) {
        for (String line : headers.split("\n")) {
            String l = line.strip();
            if (l.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                return l.substring(name.length() + 1).strip();
            }
        }
        return null;
    }

    private String robotToken(HostedRegistry r) {
        String pw = encryptionService.decrypt(r.getRobotPassword());
        return Base64.getEncoder()
                .encodeToString((ROBOT + ":" + pw).getBytes(StandardCharsets.UTF_8));
    }

    private static String containerId(DockerAPI api, HostedRegistry r) {
        String id = NginxApplier.findContainer(api, r.getContainerName());
        if (id == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The registry container is missing. Remove the registry and create it again.");
        }
        return id;
    }

    private HostedRegistry require(String hostId) {
        return repository
                .findByDockerHostId(hostId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "No registry on this host"));
    }

    private static void checkUser(String user, String password) {
        if (user == null || !USER.matcher(user).matches()) {
            throw bad("Usernames use lowercase letters, digits and . _ -");
        }
        if (password == null || password.length() < 10) {
            throw bad("Use a password of at least 10 characters");
        }
    }

    private static void checkRef(String repo, String tag) {
        if (repo == null
                || !REPO.matcher(repo).matches()
                || tag == null
                || !TAG.matcher(tag).matches()) {
            throw bad("Invalid image name or tag");
        }
    }

    private static String entryName(String hostId) {
        return "Docker Manager registry (" + hostId.substring(0, 8) + ")";
    }

    static String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }

    private static String tail(String s) {
        if (s == null) return "";
        String[] lines = s.strip().split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
