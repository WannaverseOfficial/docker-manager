package com.wannaverse.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.model.Container;
import com.wannaverse.dto.ContainerSpec;
import com.wannaverse.dto.GitRepositoryResponse;
import com.wannaverse.dto.RolloutRequest;
import com.wannaverse.dto.RolloutTest;
import com.wannaverse.persistence.*;
import com.wannaverse.persistence.RolloutRun.Status;
import com.wannaverse.security.InputValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Rollout settings, what the UI shows about them, and starting releases from Git and pipelines. */
@Service
public class RolloutService {
    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);

    private final RolloutRepository rollouts;
    private final RolloutRunRepository runs;
    private final RolloutRunner runner;
    private final DockerHostRepository hosts;
    private final IngressService ingress;
    private final NginxApplier applier;
    private final ContainerSpecService specService;
    private final PipelineRepository pipelineRepository;
    private final GitRepositoryRepository gitRepositories;

    public RolloutService(
            RolloutRepository rollouts,
            RolloutRunRepository runs,
            RolloutRunner runner,
            DockerHostRepository hosts,
            IngressService ingress,
            NginxApplier applier,
            ContainerSpecService specService,
            PipelineRepository pipelineRepository,
            GitRepositoryRepository gitRepositories) {
        this.rollouts = rollouts;
        this.runs = runs;
        this.runner = runner;
        this.hosts = hosts;
        this.ingress = ingress;
        this.applier = applier;
        this.specService = specService;
        this.pipelineRepository = pipelineRepository;
        this.gitRepositories = gitRepositories;
    }

    // --- views -------------------------------------------------------------------------------

    public record Slot(
            String name,
            String role,
            boolean exists,
            String state,
            String image,
            Integer traffic) {}

    public record RunView(
            String id,
            int number,
            String kind,
            String image,
            String targetContainer,
            String stableContainer,
            String stableImage,
            String status,
            String phase,
            int trafficPercent,
            String triggerType,
            String triggeredBy,
            String commitSha,
            List<RolloutRunner.Step> steps,
            List<RolloutRunner.Sample> metrics,
            String logs,
            String errorMessage,
            String approvalStep,
            String approvedBy,
            long createdAt,
            long startedAt,
            long finishedAt) {}

    public record LinkedRepo(String id, String name) {}

    public record RolloutView(
            String id,
            String name,
            String description,
            String dockerHostId,
            String dockerHostName,
            String strategy,
            String containerName,
            ContainerSpec containerSpec,
            int containerPort,
            String routeId,
            String hostname,
            String pathPrefix,
            String tlsMode,
            String url,
            boolean routeEnabled,
            String previewHostname,
            String healthPath,
            int startTimeoutSeconds,
            List<RolloutTest> tests,
            String canarySteps,
            int stepSeconds,
            boolean stickySessions,
            boolean autoRollback,
            double maxErrorRate,
            int maxAvgResponseMs,
            int minRequests,
            int bakeSeconds,
            boolean keepPreviousRunning,
            String activeContainer,
            String activeImage,
            String previousContainer,
            String previousImage,
            List<Slot> slots,
            RunView currentRun,
            RunView lastRun,
            List<LinkedRepo> gitRepositories,
            long createdAt,
            String createdBy) {}

    public List<RolloutView> list(String hostId) {
        List<Rollout> list =
                hostId == null
                        ? rollouts.findAllByOrderByNameAsc()
                        : rollouts.findByDockerHostIdOrderByNameAsc(hostId);
        return list.stream().map(r -> view(r, false)).toList();
    }

    public Rollout require(String id) {
        return rollouts.findById(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Rollout not found"));
    }

    public RolloutView view(Rollout r, boolean live) {
        IngressRoute route =
                r.getRouteId() == null ? null : ingress.getRoute(r.getRouteId()).orElse(null);
        String hostname = route != null ? route.getHostname() : r.getHostname();
        String path = route != null ? NginxConfigGenerator.path(route) : r.getPathPrefix();
        String tls = route != null ? route.getTlsMode().name() : r.getTlsMode();
        String url =
                hostname == null
                        ? null
                        : (tls != null && !"NONE".equals(tls) ? "https://" : "http://")
                                + hostname
                                + (path == null ? "/" : path);
        List<RolloutRun> recent = runs.findTop50ByRolloutIdOrderByCreatedAtDesc(r.getId());
        RolloutRun current =
                recent.stream()
                        .filter(x -> !x.getStatus().finished())
                        .reduce((a, b) -> b)
                        .orElse(null);
        RolloutRun last =
                recent.stream().filter(x -> x.getStatus().finished()).findFirst().orElse(null);
        return new RolloutView(
                r.getId(),
                r.getName(),
                r.getDescription(),
                r.getDockerHost().getId(),
                r.getDockerHost().getName(),
                r.getStrategy().name(),
                r.getContainerName(),
                GitRepositoryResponse.readSpec(r.getContainerSpecJson()),
                r.getContainerPort(),
                r.getRouteId(),
                hostname,
                path,
                tls,
                url,
                route == null || route.isEnabled(),
                r.getPreviewHostname(),
                r.getHealthPath(),
                r.getStartTimeoutSeconds(),
                RolloutRunner.tests(r),
                r.getCanarySteps(),
                r.getStepSeconds(),
                r.isStickySessions(),
                r.isAutoRollback(),
                r.getMaxErrorRate(),
                r.getMaxAvgResponseMs(),
                r.getMinRequests(),
                r.getBakeSeconds(),
                r.isKeepPreviousRunning(),
                r.getActiveContainer(),
                r.getActiveImage(),
                r.getPreviousContainer(),
                r.getPreviousImage(),
                live ? slots(r, route, current) : List.of(),
                current == null ? null : runView(current, false),
                last == null ? null : runView(last, false),
                gitRepositories.findByRolloutId(r.getId()).stream()
                        .map(g -> new LinkedRepo(g.getId(), g.getName()))
                        .toList(),
                r.getCreatedAt(),
                r.getCreatedBy());
    }

    /** Live state of the containers involved: both slots and the app's original container. */
    private List<Slot> slots(Rollout r, IngressRoute route, RolloutRun current) {
        Map<String, Integer> traffic = new HashMap<>();
        if (route != null && route.isEnabled()) {
            if (route.splitsTraffic()) {
                traffic.put(route.getCanaryContainerName(), route.getCanaryWeight());
                if (route.getCanaryWeight() < 100) {
                    traffic.put(route.getTargetContainerName(), 100 - route.getCanaryWeight());
                }
            } else {
                traffic.put(route.getTargetContainerName(), 100);
            }
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(r.blueName());
        names.add(r.greenName());
        if (r.getActiveContainer() != null) names.add(r.getActiveContainer());
        if (r.getPreviousContainer() != null) names.add(r.getPreviousContainer());
        Map<String, Container> found = new HashMap<>();
        try {
            DockerHost host = r.getDockerHost();
            IngressConfig cfg = ingress.getIngressConfig(host.getId()).orElse(null);
            if (cfg != null) {
                for (Container c : applier.api(cfg).listAllContainers()) {
                    if (c.getNames() == null) continue;
                    for (String n : c.getNames()) found.put(n.replaceFirst("^/", ""), c);
                }
            }
        } catch (Exception e) {
            log.debug("Could not list containers for rollout {}: {}", r.getId(), e.getMessage());
        }
        List<Slot> out = new ArrayList<>();
        for (String n : names) {
            Container c = found.get(n);
            String role =
                    current != null
                                    && current.getKind() == RolloutRun.Kind.RELEASE
                                    && n.equals(current.getTargetContainer())
                            ? "new"
                            : n.equals(r.getActiveContainer())
                                    ? "active"
                                    : n.equals(r.getPreviousContainer()) ? "previous" : "idle";
            if (c == null
                    && !n.equals(r.blueName())
                    && !n.equals(r.greenName())
                    && !role.equals("active")) {
                continue;
            }
            out.add(
                    new Slot(
                            n,
                            role,
                            c != null,
                            c == null ? null : c.getState(),
                            c == null ? null : c.getImage(),
                            traffic.get(n)));
        }
        return out;
    }

    public RunView runView(RolloutRun run, boolean details) {
        List<RolloutRunner.Step> steps = List.of();
        List<RolloutRunner.Sample> metrics = List.of();
        try {
            if (run.getStepsJson() != null) {
                steps = RolloutRunner.JSON.readValue(run.getStepsJson(), new TypeReference<>() {});
            }
            if (details && run.getMetricsJson() != null) {
                metrics =
                        RolloutRunner.JSON.readValue(
                                run.getMetricsJson(), new TypeReference<>() {});
            }
        } catch (Exception ignored) {
        }
        return new RunView(
                run.getId(),
                run.getNumber(),
                run.getKind().name(),
                run.getImage(),
                run.getTargetContainer(),
                run.getStableContainer(),
                run.getStableImage(),
                run.getStatus().name(),
                run.getPhase(),
                run.getTrafficPercent(),
                run.getTriggerType().name(),
                run.getTriggeredBy(),
                run.getCommitSha(),
                steps,
                metrics,
                details ? run.getLogs() : null,
                run.getErrorMessage(),
                run.getApprovalStep(),
                run.getApprovedBy(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getFinishedAt());
    }

    public List<RunView> runs(String rolloutId) {
        return runs.findTop50ByRolloutIdOrderByCreatedAtDesc(rolloutId).stream()
                .map(r -> runView(r, false))
                .toList();
    }

    public RunView run(String rolloutId, String runId) {
        RolloutRun run =
                runs.findById(runId)
                        .filter(r -> r.getRolloutId().equals(rolloutId))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Run not found"));
        return runView(run, true);
    }

    public RolloutRun requireRun(String rolloutId, String runId) {
        return runs.findById(runId)
                .filter(r -> r.getRolloutId().equals(rolloutId))
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    // --- settings ----------------------------------------------------------------------------

    public Rollout create(RolloutRequest req, String username) {
        DockerHost host =
                hosts.findById(req.getDockerHostId() == null ? "" : req.getDockerHostId())
                        .orElseThrow(() -> bad("Choose a Docker host"));
        Rollout r = new Rollout();
        r.setDockerHost(host);
        r.setCreatedBy(username);
        apply(r, req, true);
        r = rollouts.save(r);
        if (req.getRouteId() != null && !req.getRouteId().isBlank()) {
            try {
                IngressRoute route = ingress.setRolloutOwner(req.getRouteId(), r.getId());
                r.setRouteId(route.getId());
                r.setActiveContainer(route.getTargetContainerName());
                r.setActiveImage(imageOf(r, route.getTargetContainerName()));
                r = rollouts.save(r);
            } catch (RuntimeException e) {
                rollouts.delete(r);
                throw e;
            }
        }
        return r;
    }

    public Rollout update(String id, RolloutRequest req) {
        // Changes go onto a copy: if a check fails, the loaded rollout is untouched, so nothing
        // else
        // written during this request (the audit log) can save half of the change with it.
        Rollout r = new Rollout();
        org.springframework.beans.BeanUtils.copyProperties(require(id), r);
        String oldPreview = r.getPreviewHostname();
        String stalePreview = apply(r, req, false);
        if (stalePreview == null && !Objects.equals(oldPreview, r.getPreviewHostname())) {
            stalePreview = runner.ownRoute(r, oldPreview); // its id was lost
        }
        r = rollouts.save(r);
        removePreviewRoute(stalePreview, r.getName());
        return r;
    }

    /**
     * Deletes the rollout. Its route stays and keeps sending visitors to the current version (it
     * becomes an ordinary route); the preview route is removed. Containers are left as they are.
     */
    public void delete(String id) {
        Rollout r = require(id);
        if (runner.active(id).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A release is in progress. Stop it first.");
        }
        if (r.getRouteId() != null && ingress.getRoute(r.getRouteId()).isPresent()) {
            ingress.setRolloutOwner(r.getRouteId(), null);
        }
        if (r.getPreviewRouteId() != null && ingress.getRoute(r.getPreviewRouteId()).isPresent()) {
            try {
                ingress.setRolloutOwner(r.getPreviewRouteId(), null);
                ingress.removeRoute(r.getPreviewRouteId(), false, null, "rollout " + r.getName());
            } catch (Exception e) {
                log.warn("Could not remove the preview route: {}", e.getMessage());
            }
        }
        for (GitRepository g : gitRepositories.findByRolloutId(id)) {
            g.setRolloutId(null);
            gitRepositories.save(g);
        }
        runs.deleteAll(runs.findByRolloutId(id));
        rollouts.delete(r);
    }

    private String imageOf(Rollout r, String container) {
        try {
            IngressConfig cfg = ingress.requireEnabledForRollout(r.getDockerHost().getId());
            DockerAPI api = applier.api(cfg);
            String id = NginxApplier.findContainer(api, container);
            return id == null ? null : api.inspectContainer(id).getConfig().getImage();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates the request and copies it onto the rollout. Makes no other changes, so a request
     * that fails a check leaves everything as it was. Returns a preview route that is no longer
     * used (to remove after saving), or null.
     */
    private String apply(Rollout r, RolloutRequest req, boolean creating) {
        String name = trim(req.getName());
        if (name == null) throw bad("Enter a name");
        if (name.length() > 100) throw bad("Use a shorter name");
        r.setName(name);
        r.setDescription(trim(req.getDescription()));

        try {
            r.setStrategy(
                    Rollout.Strategy.valueOf(
                            req.getStrategy() == null ? "BLUE_GREEN" : req.getStrategy()));
        } catch (IllegalArgumentException e) {
            throw bad("Choose blue/green or canary");
        }

        String container = trim(req.getContainerName());
        if (container == null) container = StackService.projectNameFor(name);
        if (!InputValidator.isValidContainerName(container) || container.length() > 100) {
            throw bad("Container names may only use letters, digits, '_', '.' and '-'");
        }
        for (Rollout other : rollouts.findByDockerHostIdOrderByNameAsc(r.getDockerHost().getId())) {
            if (!other.getId().equals(r.getId()) && other.getContainerName().equals(container)) {
                throw bad("Another rollout already uses the container name " + container);
            }
        }
        r.setContainerName(container);

        ContainerSpec spec =
                req.getContainerSpec() == null ? new ContainerSpec() : req.getContainerSpec();
        spec.setImage("placeholder:latest");
        spec.setName(null);
        spec.setPorts(new ArrayList<>()); // visitors come through the proxy
        specService.validateSettings(spec, true);
        spec.setImage(null);
        if (spec.getEnv() != null)
            spec.setEnv(spec.getEnv().stream().filter(e -> e != null && !e.isBlank()).toList());
        if (spec.getVolumes() != null) {
            spec.setVolumes(
                    spec.getVolumes().stream().filter(v -> v != null && !v.isBlank()).toList());
        }
        r.setContainerSpecJson(GitRepositoryResponse.writeSpec(spec));

        boolean takingRoute = creating && trim(req.getRouteId()) != null;
        int port = req.getContainerPort();
        if (takingRoute && port == 0) {
            port = ingress.getRoute(req.getRouteId()).map(IngressRoute::getTargetPort).orElse(0);
        }
        if (port < 1 || port > 65535) throw bad("Enter the port the app listens on (1-65535)");
        r.setContainerPort(port);

        // The address can only be set while the rollout has no route yet; afterwards it is
        // changed on the Ingress page like any route.
        boolean hasRoute = r.getRouteId() != null && ingress.getRoute(r.getRouteId()).isPresent();
        if (takingRoute) {
            IngressRoute route =
                    ingress.getRoute(req.getRouteId()).orElseThrow(() -> bad("Route not found"));
            IngressConfig cfg =
                    ingress.getIngressConfig(r.getDockerHost().getId())
                            .orElseThrow(() -> bad("Turn ingress on first"));
            if (!route.getIngressConfigId().equals(cfg.getId())) {
                throw bad("That route belongs to another Docker host");
            }
            if (route.kind() != IngressRoute.TargetType.CONTAINER) {
                throw bad("Choose a route that sends traffic to a container");
            }
            if (route.getRolloutId() != null)
                throw bad("Another rollout already manages that route");
            r.setHostname(route.getHostname());
            r.setPathPrefix(NginxConfigGenerator.path(route));
            r.setTlsMode(route.getTlsMode().name());
        } else if (!hasRoute) {
            String hostname = trim(req.getHostname());
            hostname = hostname == null ? null : hostname.toLowerCase(Locale.ROOT);
            if (hostname == null
                    || !InputValidator.isValidHostname(hostname)
                    || !hostname.contains(".")) {
                throw bad("Enter the address visitors use, for example www.example.com");
            }
            String path = trim(req.getPathPrefix());
            if (path == null) path = "/";
            if (!path.startsWith("/")) path = "/" + path;
            if (!path.matches("^/[A-Za-z0-9._~/-]*$")
                    || path.contains("..")
                    || path.contains("//")) {
                throw bad("The path may only use letters, digits and - . _ ~ /");
            }
            r.setHostname(hostname);
            r.setPathPrefix(path);
            String tls =
                    req.getTlsMode() == null || req.getTlsMode().isBlank()
                            ? "NONE"
                            : req.getTlsMode();
            if (!tls.equals("NONE") && !tls.equals("LETS_ENCRYPT"))
                throw bad("Unknown HTTPS setting");
            r.setTlsMode(tls);
        }

        String preview = trim(req.getPreviewHostname());
        if (preview != null) {
            preview = preview.toLowerCase(Locale.ROOT);
            if (!InputValidator.isValidHostname(preview) || !preview.contains(".")) {
                throw bad("Enter a full preview address, for example next.example.com");
            }
            if (preview.equals(r.getHostname()))
                throw bad("The preview address must differ from the main one");
        }
        // The old preview route is removed by the caller once everything is valid and saved:
        // writing here would also save this half-changed rollout if a later check fails.
        String stalePreviewRoute = null;
        if (!Objects.equals(preview, r.getPreviewHostname()) && r.getPreviewRouteId() != null) {
            stalePreviewRoute = r.getPreviewRouteId();
            r.setPreviewRouteId(null);
        }
        r.setPreviewHostname(preview);

        String health = trim(req.getHealthPath());
        if (health == null) health = "/";
        if (!health.startsWith("/")) health = "/" + health;
        if (!RolloutChecks.safePath(health))
            throw bad("The health path may not contain spaces or quotes");
        r.setHealthPath(health);
        r.setStartTimeoutSeconds(
                range(req.getStartTimeoutSeconds(), 180, 10, 3600, "Start time limit"));

        r.setTestsJson(writeTests(validateTests(req.getTests(), r.getDockerHost().getId())));

        try {
            List<Integer> steps = RolloutChecks.parseSteps(req.getCanarySteps());
            if (r.getStrategy() == Rollout.Strategy.CANARY && steps.isEmpty()) {
                throw bad("Add at least one canary step, for example 10, 50");
            }
            r.setCanarySteps(
                    steps.isEmpty()
                            ? "10,25,50"
                            : String.join(",", steps.stream().map(String::valueOf).toList()));
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        r.setStepSeconds(range(req.getStepSeconds(), 120, 10, 86_400, "Step length"));
        r.setStickySessions(req.getStickySessions() == null || req.getStickySessions());
        r.setAutoRollback(req.getAutoRollback() == null || req.getAutoRollback());
        double maxError = req.getMaxErrorRate() == null ? 5 : req.getMaxErrorRate();
        if (maxError < 0 || maxError > 100) throw bad("The error limit must be 0-100%");
        r.setMaxErrorRate(maxError);
        r.setMaxAvgResponseMs(
                range(req.getMaxAvgResponseMs(), 0, 0, 600_000, "Response time limit"));
        r.setMinRequests(range(req.getMinRequests(), 20, 1, 1_000_000, "Minimum requests"));
        r.setBakeSeconds(range(req.getBakeSeconds(), 120, 0, 86_400, "Watch time"));
        r.setKeepPreviousRunning(Boolean.TRUE.equals(req.getKeepPreviousRunning()));
        return stalePreviewRoute;
    }

    private void removePreviewRoute(String routeId, String rolloutName) {
        if (routeId == null || ingress.getRoute(routeId).isEmpty()) return;
        try {
            ingress.setRolloutOwner(routeId, null);
            ingress.removeRoute(routeId, false, null, "rollout " + rolloutName);
        } catch (Exception e) {
            log.warn("Could not remove the old preview route: {}", e.getMessage());
        }
    }

    private List<RolloutTest> validateTests(List<RolloutTest> tests, String hostId) {
        List<RolloutTest> out = new ArrayList<>();
        if (tests == null) return out;
        if (tests.size() > 20) throw bad("Use at most 20 test steps");
        for (RolloutTest t : tests) {
            if (t == null) continue;
            String kind = t.kindOrHttp();
            t.setKind(kind);
            t.setName(trim(t.getName()));
            if (t.getName() != null && t.getName().length() > 100)
                throw bad("Use shorter step names");
            if (t.getTimeoutSeconds() != null
                    && (t.getTimeoutSeconds() < 0 || t.getTimeoutSeconds() > 7 * 86_400)) {
                throw bad("A step's time limit must be at most a week");
            }
            switch (kind) {
                case "HTTP" -> {
                    String path = trim(t.getPath());
                    if (path == null) path = "/";
                    if (!path.startsWith("/")) path = "/" + path;
                    if (!RolloutChecks.safePath(path))
                        throw bad("Check paths may not contain spaces or quotes");
                    t.setPath(path);
                    if (!RolloutChecks.validStatusList(t.getExpectStatus())) {
                        throw bad(
                                "Expected status: codes such as 200, 2xx or 200-299, separated by"
                                        + " commas");
                    }
                    if (t.getRetries() != null && (t.getRetries() < 1 || t.getRetries() > 30)) {
                        throw bad("Tries must be 1-30");
                    }
                    t.setImage(null);
                    t.setScript(null);
                    t.setPipelineId(null);
                }
                case "COMMAND" -> {
                    String image = trim(t.getImage());
                    if (image == null || !InputValidator.isValidImageName(image)) {
                        throw bad("Enter the image to run the test command in");
                    }
                    t.setImage(image);
                    if (t.getScript() == null || t.getScript().isBlank())
                        throw bad("Enter the test command");
                    if (t.getScript().length() > 20_000) throw bad("The test command is too long");
                    t.setPath(null);
                    t.setPipelineId(null);
                }
                case "PIPELINE" -> {
                    Pipeline p =
                            pipelineRepository
                                    .findById(t.getPipelineId() == null ? "" : t.getPipelineId())
                                    .orElseThrow(() -> bad("Choose the pipeline to run"));
                    if (p.getDockerHost() == null || !p.getDockerHost().getId().equals(hostId)) {
                        throw bad("The pipeline " + p.getName() + " runs on another Docker host");
                    }
                    t.setPath(null);
                    t.setImage(null);
                    t.setScript(null);
                }
                case "APPROVAL" -> {
                    t.setPath(null);
                    t.setImage(null);
                    t.setScript(null);
                    t.setPipelineId(null);
                }
                default -> throw bad("Unknown test step kind " + kind);
            }
            out.add(t);
        }
        return out;
    }

    private static String writeTests(List<RolloutTest> tests) {
        try {
            return RolloutRunner.JSON.writeValueAsString(tests);
        } catch (Exception e) {
            throw bad("Invalid test steps");
        }
    }

    private static int range(Integer value, int fallback, int min, int max, String label) {
        int v = value == null ? fallback : value;
        if (v < min || v > max) throw bad(label + " must be between " + min + " and " + max);
        return v;
    }

    // --- releases from Git and pipelines -----------------------------------------------------

    public RolloutRun releaseFromGit(
            String rolloutId, String image, String repoName, String commit) {
        Rollout r =
                rollouts.findById(rolloutId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "The rollout this repository releases through was"
                                                        + " deleted"));
        return runner.release(r, image, RolloutRun.Trigger.GIT, "repository " + repoName, commit);
    }

    /** A pipeline step: release the image and (optionally) wait until the release finishes. */
    public StepExecutorService.StepResult releaseFromPipeline(
            String rolloutId,
            String image,
            String hostUrl,
            String triggeredBy,
            String commit,
            boolean wait,
            int timeoutSeconds,
            Consumer<String> out,
            BooleanSupplier cancelled) {
        Rollout r = rollouts.findById(rolloutId).orElse(null);
        if (r == null)
            return new StepExecutorService.StepResult(false, 1, "", "The rollout was deleted");
        if (!r.getDockerHost().getDockerHostUrl().equals(hostUrl)) {
            return new StepExecutorService.StepResult(
                    false, 1, "", "The rollout " + r.getName() + " runs on another Docker host");
        }
        RolloutRun run;
        try {
            run = runner.release(r, image, RolloutRun.Trigger.PIPELINE, triggeredBy, commit);
        } catch (ResponseStatusException e) {
            return new StepExecutorService.StepResult(false, 1, "", e.getReason());
        }
        out.accept(
                "Started release #"
                        + run.getNumber()
                        + " of "
                        + image
                        + " on rollout "
                        + r.getName());
        if (!wait) return new StepExecutorService.StepResult(true, 0, "", null);
        long deadline = System.currentTimeMillis() + Math.max(60, timeoutSeconds) * 1000L;
        String lastPhase = null;
        while (true) {
            RolloutRun now = runs.findById(run.getId()).orElse(null);
            if (now == null)
                return new StepExecutorService.StepResult(false, 1, "", "The release was deleted");
            if (!Objects.equals(lastPhase, now.getPhase()) && now.getPhase() != null) {
                lastPhase = now.getPhase();
                out.accept("Release #" + now.getNumber() + ": " + lastPhase);
            }
            if (now.getStatus().finished()) {
                boolean ok = now.getStatus() == Status.SUCCEEDED;
                return new StepExecutorService.StepResult(
                        ok,
                        ok ? 0 : 1,
                        "",
                        ok
                                ? null
                                : "Release #"
                                        + now.getNumber()
                                        + " "
                                        + now.getStatus()
                                                .name()
                                                .toLowerCase(Locale.ROOT)
                                                .replace('_', ' ')
                                        + (now.getErrorMessage() == null
                                                ? ""
                                                : ": " + now.getErrorMessage()));
            }
            if (cancelled.getAsBoolean() || System.currentTimeMillis() > deadline) {
                runner.abort(run.getId(), triggeredBy);
                return new StepExecutorService.StepResult(
                        false,
                        1,
                        "",
                        cancelled.getAsBoolean() ? "Cancelled" : "The release took too long");
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new StepExecutorService.StepResult(false, 1, "", "Interrupted");
            }
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
