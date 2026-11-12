package com.wannaverse.iac.handlers;

import com.wannaverse.dto.RolloutRequest;
import com.wannaverse.dto.RolloutTest;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.IacJson;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.Pipeline;
import com.wannaverse.persistence.PipelineRepository;
import com.wannaverse.persistence.Rollout;
import com.wannaverse.persistence.RolloutRepository;
import com.wannaverse.service.RolloutChecks;
import com.wannaverse.service.RolloutService;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code rollouts}: blue/green and canary release settings. Defining or changing a rollout never
 * releases anything; releases are started from the Rollouts page, a Git repository or a pipeline.
 */
@Component
public class RolloutsHandler implements ResourceHandler<RolloutsHandler.RolloutSpec> {

    public enum TlsMode {
        NONE,
        LETS_ENCRYPT
    }

    public enum TestKind {
        HTTP,
        COMMAND,
        PIPELINE,
        APPROVAL
    }

    @Data
    @Doc("A blue/green or canary rollout. Applying never starts a release.")
    public static class RolloutSpec {
        @Doc("The Docker host (a key of hosts:, or a host's name). Default: the only host.")
        private String host;

        @Doc("The rollout's name. Defaults to the entry's key.")
        private String name;

        @Doc("What the rollout is for")
        private String description;

        @Doc("blue-green (switch all traffic at once, default) or canary (shift it in steps)")
        private Rollout.Strategy strategy;

        @Doc(
                "Base name of the app's containers (-blue and -green are added). Default: from the"
                        + " name.")
        private String containerName;

        @Doc(
                "How the app's container runs. Ports are not published: visitors come through"
                        + " ingress.")
        private AppContainerSpec container;

        @Doc(value = "The port the app listens on inside the container", required = true)
        private Integer port;

        @Doc(
                "The address visitors use, such as www.example.com. Its route is created at the"
                        + " first release; after that the address is changed on the route.")
        private String hostname;

        @Doc("Path prefix of the address (default /)")
        private String path;

        @Doc("HTTPS for the address: none (default) or lets-encrypt")
        private TlsMode tls;

        @Doc("A second address that always shows the new version, such as next.example.com")
        private String previewHostname;

        @Doc("Path checked to see that a new version is up (default /)")
        private String healthPath;

        @Doc("How long a new version may take to start, in seconds (default 180)")
        private Integer startTimeoutSeconds;

        @Doc("Checks run against a new version before it gets visitors")
        private List<TestSpec> tests = new ArrayList<>();

        @Doc("Canary: traffic percentages to step through (default [10, 25, 50])")
        private List<Integer> canarySteps;

        @Doc("Canary: seconds at each step (default 120)")
        private Integer stepSeconds;

        @Doc("Canary: keep each visitor on one version (default true)")
        private Boolean stickySessions;

        @Doc("Roll back automatically when the limits below are passed (default true)")
        private Boolean autoRollback;

        @Doc("Highest acceptable share of failed requests, in percent (default 5)")
        private Double maxErrorRate;

        @Doc("Highest acceptable average response time in ms (default 0: no limit)")
        private Integer maxAvgResponseMs;

        @Doc("Requests needed before the limits are judged (default 20)")
        private Integer minRequests;

        @Doc("Seconds to watch a new version after it gets all traffic (default 120)")
        private Integer bakeSeconds;

        @Doc("Keep the previous version's container running after a release (default false)")
        private Boolean keepPreviousRunning;
    }

    @Data
    @Doc("A check run against a new version before it gets visitors")
    public static class TestSpec {
        @Doc("Shown in the release's steps")
        private String name;

        @Doc("http (default), command, pipeline or approval")
        private TestKind kind;

        @Doc("http: the path to request (default /)")
        private String path;

        @Doc("http: expected status codes, such as 200, 2xx or 200-299")
        private String expectStatus;

        @Doc("http: text the response must contain")
        private String bodyContains;

        @Doc("http: tries before failing, 1-30")
        private Integer retries;

        @Doc("command: the image to run the script in")
        private String image;

        @Doc("command: the script; TARGET_URL points at the new version")
        private String script;

        @Doc("pipeline: the pipeline to run (a key of pipelines:, or a pipeline's name)")
        private String pipeline;

        @Doc("Time limit in seconds. approval: empty waits until someone decides.")
        private Integer timeoutSeconds;
    }

    private final RolloutService service;
    private final RolloutRepository rollouts;
    private final PipelineRepository pipelines;

    public RolloutsHandler(
            RolloutService service, RolloutRepository rollouts, PipelineRepository pipelines) {
        this.service = service;
        this.rollouts = rollouts;
        this.pipelines = pipelines;
    }

    @Override
    public String section() {
        return "rollouts";
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public Class<RolloutSpec> specType() {
        return RolloutSpec.class;
    }

    @Override
    public String description() {
        return "Blue/green and canary rollouts. Applying the file never starts a release.";
    }

    @Override
    public void validate(Entry<RolloutSpec> entry, ApplyContext ctx) {
        RolloutSpec s = entry.spec();
        ctx.hostId(s.getHost());
        if (s.getPort() < 1 || s.getPort() > 65535) throw new IacException("port: use 1-65535");
        AppContainerSpec c = s.getContainer();
        if (c != null) {
            if (c.getName() != null) {
                throw new IacException(
                        "container.name: a rollout names its containers itself; use containerName");
            }
            if (!c.getPorts().isEmpty()) {
                throw new IacException(
                        "container.ports: a rollout publishes no ports; visitors come through"
                                + " ingress");
            }
        }
        if (s.getCanarySteps() != null) {
            try {
                RolloutChecks.parseSteps(steps(s.getCanarySteps()));
            } catch (IllegalArgumentException e) {
                throw new IacException("canarySteps: " + e.getMessage());
            }
        }
        for (TestSpec t : s.getTests()) {
            if (t.getKind() == TestKind.PIPELINE) {
                if (t.getPipeline() == null || t.getPipeline().isBlank()) {
                    throw new IacException("tests: a pipeline test needs pipeline: <name>");
                }
                ctx.ref("pipelines", t.getPipeline().trim());
            }
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<RolloutSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        if (hostId == null) return Optional.empty();
        String name = entry.nameOr(entry.spec().getName());
        return rollouts.findByDockerHostIdOrderByNameAsc(hostId).stream()
                .filter(r -> r.getName().equals(name))
                .findFirst()
                .map(RolloutsHandler::target);
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        if (rollouts.existsById(name)) return Optional.of(name);
        List<Rollout> named =
                rollouts.findAllByOrderByNameAsc().stream()
                        .filter(r -> r.getName().equals(name))
                        .toList();
        if (named.size() > 1) {
            throw new IacException(
                    "several rollouts are called \""
                            + name
                            + "\"; add the one you mean under rollouts:");
        }
        return named.stream().findFirst().map(Rollout::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return rollouts.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<RolloutSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        RolloutRequest req = request(entry, hostId, ctx, null, null);
        return target(service.create(req, ctx.actor()));
    }

    @Override
    public Target update(
            Entry<RolloutSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        Rollout current = service.require(record.getTargetId());
        if (!current.getDockerHost().getId().equals(hostId)) {
            throw new IacException(
                    "host: a rollout can't move to another Docker host; give it a new key to"
                            + " create it there");
        }
        RolloutRequest req = request(entry, hostId, ctx, null, null);
        Rollout saved = service.update(record.getTargetId(), req);
        if (saved.getRouteId() != null && changes.has("hostname", "path", "tls")) {
            ctx.note(
                    "Rollout "
                            + saved.getName()
                            + ": it has a route already, so its address, path and HTTPS are"
                            + " changed on the Ingress page, not in the file");
        }
        return target(saved);
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (rollouts.existsById(record.getTargetId())) service.delete(record.getTargetId());
    }

    /**
     * Called when the file creates a pipeline: rollout tests that run it were applied without it
     * (pipelines are set up after rollouts), so they are added now.
     */
    void linkPipeline(String pipelineKey, String pipelineId, ApplyContext ctx) {
        for (ManagedResource r : ctx.records(section())) {
            RolloutSpec spec;
            try {
                spec = IacJson.SPEC.readValue(r.getAppliedSpec(), RolloutSpec.class);
            } catch (Exception e) {
                continue;
            }
            boolean uses =
                    spec.getTests().stream()
                            .anyMatch(
                                    t ->
                                            t.getKind() == TestKind.PIPELINE
                                                    && pipelineKey.equals(t.getPipeline()));
            if (!uses || !rollouts.existsById(r.getTargetId())) continue;
            String key = r.getEntryKey();
            Entry<RolloutSpec> entry = new Entry<>(key, null, key, spec);
            Rollout current = service.require(r.getTargetId());
            service.update(
                    r.getTargetId(),
                    request(entry, current.getDockerHost().getId(), ctx, pipelineKey, pipelineId));
        }
    }

    private RolloutRequest request(
            Entry<RolloutSpec> entry,
            String hostId,
            ApplyContext ctx,
            String knownPipelineKey,
            String knownPipelineId) {
        RolloutSpec s = entry.spec();
        RolloutRequest req = new RolloutRequest();
        req.setName(entry.nameOr(s.getName()));
        req.setDescription(s.getDescription());
        req.setDockerHostId(hostId);
        req.setStrategy(
                (s.getStrategy() == null ? Rollout.Strategy.BLUE_GREEN : s.getStrategy()).name());
        req.setContainerName(s.getContainerName());
        req.setContainerSpec(s.getContainer() == null ? null : s.getContainer().toContainerSpec());
        req.setContainerPort(s.getPort());
        req.setHostname(s.getHostname());
        req.setPathPrefix(s.getPath());
        req.setTlsMode(s.getTls() == null ? null : s.getTls().name());
        req.setPreviewHostname(s.getPreviewHostname());
        req.setHealthPath(s.getHealthPath());
        req.setStartTimeoutSeconds(s.getStartTimeoutSeconds());
        List<RolloutTest> tests = new ArrayList<>();
        for (TestSpec t : s.getTests()) {
            RolloutTest rt = new RolloutTest();
            rt.setName(t.getName());
            rt.setKind((t.getKind() == null ? TestKind.HTTP : t.getKind()).name());
            rt.setPath(t.getPath());
            rt.setExpectStatus(t.getExpectStatus());
            rt.setBodyContains(t.getBodyContains());
            rt.setRetries(t.getRetries());
            rt.setImage(t.getImage());
            rt.setScript(t.getScript());
            rt.setTimeoutSeconds(t.getTimeoutSeconds());
            if (t.getKind() == TestKind.PIPELINE) {
                String key = t.getPipeline().trim();
                if (key.equals(knownPipelineKey)) {
                    rt.setPipelineId(knownPipelineId);
                } else if (ctx.declared("pipelines", key)
                        && ctx.record("pipelines", key).isEmpty()) {
                    // Created later in this apply; linkPipeline adds the test then.
                    continue;
                } else {
                    rt.setPipelineId(ctx.ref("pipelines", key));
                }
            }
            tests.add(rt);
        }
        req.setTests(tests);
        req.setCanarySteps(s.getCanarySteps() == null ? null : steps(s.getCanarySteps()));
        req.setStepSeconds(s.getStepSeconds());
        req.setStickySessions(s.getStickySessions());
        req.setAutoRollback(s.getAutoRollback());
        req.setMaxErrorRate(s.getMaxErrorRate());
        req.setMaxAvgResponseMs(s.getMaxAvgResponseMs());
        req.setMinRequests(s.getMinRequests());
        req.setBakeSeconds(s.getBakeSeconds());
        req.setKeepPreviousRunning(s.getKeepPreviousRunning());
        return req;
    }

    private static String steps(List<Integer> steps) {
        return String.join(",", steps.stream().map(String::valueOf).toList());
    }

    @Override
    public Map<String, RolloutSpec> export(ExportContext ctx) {
        Map<String, RolloutSpec> out = new LinkedHashMap<>();
        for (Rollout r : rollouts.findAllByOrderByNameAsc()) {
            RolloutService.RolloutView v = service.view(r, false);
            RolloutSpec s = new RolloutSpec();
            s.setHost(ctx.hostKey(r.getDockerHost().getId()));
            s.setDescription(r.getDescription());
            if (r.getStrategy() != Rollout.Strategy.BLUE_GREEN) s.setStrategy(r.getStrategy());
            s.setContainerName(r.getContainerName());
            AppContainerSpec c = AppContainerSpec.from(v.containerSpec());
            if (c != null) {
                c.setName(null);
                c.setPorts(new ArrayList<>());
            }
            s.setContainer(c);
            s.setPort(r.getContainerPort());
            s.setHostname(v.hostname());
            if (v.pathPrefix() != null && !"/".equals(v.pathPrefix())) s.setPath(v.pathPrefix());
            if ("LETS_ENCRYPT".equals(v.tlsMode())) s.setTls(TlsMode.LETS_ENCRYPT);
            s.setPreviewHostname(r.getPreviewHostname());
            if (!"/".equals(r.getHealthPath())) s.setHealthPath(r.getHealthPath());
            s.setStartTimeoutSeconds(r.getStartTimeoutSeconds());
            List<TestSpec> tests = new ArrayList<>();
            for (RolloutTest t : v.tests()) {
                TestSpec ts = new TestSpec();
                ts.setName(t.getName());
                ts.setKind(TestKind.valueOf(t.kindOrHttp()));
                ts.setPath(t.getPath());
                ts.setExpectStatus(t.getExpectStatus());
                ts.setBodyContains(t.getBodyContains());
                ts.setRetries(t.getRetries());
                ts.setImage(t.getImage());
                ts.setScript(t.getScript());
                ts.setTimeoutSeconds(t.getTimeoutSeconds());
                if (t.getPipelineId() != null) {
                    String name =
                            pipelines
                                    .findById(t.getPipelineId())
                                    .map(Pipeline::getName)
                                    .orElse(t.getPipelineId());
                    ts.setPipeline(ctx.key("pipelines", t.getPipelineId(), name));
                }
                tests.add(ts);
            }
            s.setTests(tests);
            s.setCanarySteps(RolloutChecks.parseSteps(r.getCanarySteps()));
            s.setStepSeconds(r.getStepSeconds());
            s.setStickySessions(r.isStickySessions());
            s.setAutoRollback(r.isAutoRollback());
            s.setMaxErrorRate(r.getMaxErrorRate());
            if (r.getMaxAvgResponseMs() > 0) s.setMaxAvgResponseMs(r.getMaxAvgResponseMs());
            s.setMinRequests(r.getMinRequests());
            s.setBakeSeconds(r.getBakeSeconds());
            if (r.isKeepPreviousRunning()) s.setKeepPreviousRunning(true);
            String key = ctx.key(section(), r.getId(), r.getName());
            if (out.containsKey(key)) key = key + "-" + s.getHost();
            if (!key.equals(r.getName())) s.setName(r.getName());
            out.put(key, s);
        }
        return out;
    }

    private static Target target(Rollout r) {
        return new Target(r.getId(), r.getDockerHost().getId(), r.getName());
    }
}
