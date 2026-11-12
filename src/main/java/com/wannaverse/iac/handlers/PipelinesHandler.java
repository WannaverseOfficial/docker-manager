package com.wannaverse.iac.handlers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.wannaverse.dto.PipelineRequest;
import com.wannaverse.dto.PipelineResponse;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.Pipeline;
import com.wannaverse.persistence.PipelineRepository;
import com.wannaverse.persistence.PipelineStage;
import com.wannaverse.persistence.PipelineStep;
import com.wannaverse.persistence.Rollout;
import com.wannaverse.persistence.RolloutRepository;
import com.wannaverse.service.PipelineService;

import lombok.Data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code pipelines}: build and test pipelines with stages and steps. Applying the file never runs
 * one; runs start from a push, polling, a rollout test or the Run button.
 */
@Component
public class PipelinesHandler implements ResourceHandler<PipelinesHandler.PipelineSpec> {

    @Data
    @Doc("A pipeline of stages and steps. Applying the file never runs it.")
    public static class PipelineSpec {
        @Doc("The Docker host the steps run on (a key of hosts:). Default: the only host.")
        private String host;

        @Doc("The pipeline's name. Defaults to the entry's key.")
        private String name;

        @Doc("What the pipeline does")
        private String description;

        @Doc("The Git repository it checks out (a key of gitRepositories:, or a repository's name)")
        private String repository;

        @Doc("Runs when triggered (default true)")
        private Boolean enabled;

        @Doc("Run when the Git server calls the pipeline's webhook (default true)")
        private Boolean webhook;

        @Doc("Check the repository for new commits regularly (default false)")
        private Boolean polling;

        @Doc("Seconds between checks (default 300)")
        private Integer pollingInterval;

        @Doc("Only run for these branches, such as main or release/*")
        private String branchFilter;

        @Doc("Stages, run in order unless dependsOn says otherwise")
        private List<StageSpec> stages = new ArrayList<>();
    }

    @Data
    @Doc("A stage: a group of steps")
    public static class StageSpec {
        @Doc(value = "The stage's name, unique in the pipeline", required = true)
        private String name;

        @Doc("sequential (default): steps one after another; parallel: all at once")
        private PipelineStage.ExecutionMode mode;

        @Doc("Names of stages that must finish first")
        private List<String> dependsOn = new ArrayList<>();

        @Doc("Stop the pipeline when a step fails (default true)")
        private Boolean stopOnFailure;

        @Doc("The stage's steps")
        private List<StepSpec> steps = new ArrayList<>();
    }

    @Data
    @Doc("A step of a stage")
    public static class StepSpec {
        @Doc(value = "The step's name", required = true)
        private String name;

        @Doc("shell (default), jar, dockerfile, docker-compose, custom-image or rollout")
        private PipelineStep.StepType type;

        @Doc(
                "The step's settings, as in the step editor: shell {image, script}, jar {jarPath,"
                        + " jvmOpts, args}, custom-image {image, command}, rollout {rollout: <key"
                        + " of rollouts:>, image, wait}...")
        private Map<String, Object> config = new LinkedHashMap<>();

        @Doc("Working directory in the checkout")
        private String workingDirectory;

        @Doc("Time limit in seconds (default 3600)")
        private Integer timeoutSeconds;

        @Doc("Carry on with the next step when this one fails (default false)")
        private Boolean continueOnFailure;

        @Doc("Environment variables for this step (values may be ${env:...} secrets)")
        private Map<String, Secret> env = new LinkedHashMap<>();

        @Doc("Artifacts from earlier steps to copy in (a glob)")
        private String artifactsIn;

        @Doc("Files to keep as artifacts (a glob)")
        private String artifactsOut;
    }

    private final PipelineService service;
    private final PipelineRepository pipelines;
    private final RolloutRepository rollouts;
    private final RolloutsHandler rolloutsHandler;
    private final String baseUrl;

    public PipelinesHandler(
            PipelineService service,
            PipelineRepository pipelines,
            RolloutRepository rollouts,
            RolloutsHandler rolloutsHandler,
            @Value("${app.base-url}") String baseUrl) {
        this.service = service;
        this.pipelines = pipelines;
        this.rollouts = rollouts;
        this.rolloutsHandler = rolloutsHandler;
        this.baseUrl = baseUrl;
    }

    @Override
    public String section() {
        return "pipelines";
    }

    @Override
    public int order() {
        return 320;
    }

    @Override
    public Class<PipelineSpec> specType() {
        return PipelineSpec.class;
    }

    @Override
    public String description() {
        return "Build and test pipelines. Applying the file never runs one.";
    }

    @Override
    public void validate(Entry<PipelineSpec> entry, ApplyContext ctx) {
        PipelineSpec s = entry.spec();
        ctx.hostId(s.getHost());
        if (s.getRepository() != null && !s.getRepository().isBlank()) {
            ctx.ref("gitRepositories", s.getRepository().trim());
        }
        if (s.getPollingInterval() != null && s.getPollingInterval() < 60) {
            throw new IacException("pollingInterval: at least 60 seconds");
        }
        Set<String> names = new HashSet<>();
        for (StageSpec st : s.getStages()) {
            if (!names.add(st.getName())) {
                throw new IacException("stages: two stages are called \"" + st.getName() + "\"");
            }
        }
        for (StageSpec st : s.getStages()) {
            for (String dep : st.getDependsOn()) {
                if (!names.contains(dep)) {
                    throw new IacException(
                            "stages."
                                    + st.getName()
                                    + ".dependsOn: there is no stage called \""
                                    + dep
                                    + "\"");
                }
                if (dep.equals(st.getName())) {
                    throw new IacException(
                            "stages." + st.getName() + ".dependsOn: a stage can't wait for itself");
                }
            }
            for (StepSpec step : st.getSteps()) {
                if (step.getName() == null || step.getName().isBlank()) {
                    throw new IacException(
                            "stages." + st.getName() + ".steps: every step needs a name");
                }
                if (step.getType() == PipelineStep.StepType.ROLLOUT) {
                    Object key = step.getConfig().get("rollout");
                    if (key == null && step.getConfig().get("rolloutId") == null) {
                        throw new IacException(
                                "stages."
                                        + st.getName()
                                        + ".steps."
                                        + step.getName()
                                        + ": a rollout step needs config.rollout");
                    }
                    if (key != null) ctx.ref("rollouts", key.toString().trim());
                }
            }
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<PipelineSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        if (hostId == null) return Optional.empty();
        String name = entry.nameOr(entry.spec().getName());
        return pipelines.findByDockerHostIdOrderByCreatedAtDesc(hostId).stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst()
                .map(p -> new Target(p.getId(), hostId, p.getName()));
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        if (pipelines.existsById(name)) return Optional.of(name);
        List<Pipeline> named =
                pipelines.findAllByOrderByCreatedAtDesc().stream()
                        .filter(p -> name.equals(p.getName()))
                        .toList();
        if (named.size() > 1) {
            throw new IacException(
                    "several pipelines are called \""
                            + name
                            + "\"; add the one you mean under pipelines:");
        }
        return named.stream().findFirst().map(Pipeline::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return pipelines.existsById(record.getTargetId());
    }

    @Override
    @Transactional
    public Target create(Entry<PipelineSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        PipelineRequest req = request(entry, hostId, null, ctx);
        PipelineResponse created = service.createPipeline(req, ctx.actor());
        if (created.isWebhookEnabled()) {
            ctx.note(
                    "Pipeline "
                            + created.getName()
                            + ": webhook address "
                            + baseUrl
                            + "/api/pipelines/webhook/"
                            + created.getWebhookSecret());
        }
        rolloutsHandler.linkPipeline(entry.key(), created.getId(), ctx);
        return new Target(created.getId(), hostId, created.getName());
    }

    @Override
    @Transactional
    public Target update(
            Entry<PipelineSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        Pipeline existing =
                pipelines
                        .findById(record.getTargetId())
                        .orElseThrow(() -> new IacException("the pipeline no longer exists"));
        PipelineRequest req = request(entry, hostId, existing, ctx);
        PipelineResponse saved = service.updatePipeline(existing.getId(), req);
        return new Target(saved.getId(), hostId, saved.getName());
    }

    /**
     * The editor's request for the entry. Stages and steps keep their ids (matched by name), so
     * past runs keep pointing at them, and their places on the canvas.
     */
    private PipelineRequest request(
            Entry<PipelineSpec> entry, String hostId, Pipeline existing, ApplyContext ctx) {
        PipelineSpec s = entry.spec();
        PipelineRequest req = new PipelineRequest();
        req.setName(entry.nameOr(s.getName()));
        req.setDescription(s.getDescription());
        req.setDockerHostId(hostId);
        req.setGitRepositoryId(
                s.getRepository() == null || s.getRepository().isBlank()
                        ? null
                        : ctx.ref("gitRepositories", s.getRepository().trim()));
        req.setEnabled(s.getEnabled() == null || s.getEnabled());
        req.setWebhookEnabled(s.getWebhook() == null || s.getWebhook());
        req.setPollingEnabled(Boolean.TRUE.equals(s.getPolling()));
        req.setPollingIntervalSeconds(
                s.getPollingInterval() == null ? 300 : s.getPollingInterval());
        req.setBranchFilter(blankToNull(s.getBranchFilter()));
        req.setGraphLayout(existing == null ? null : existing.getGraphLayout());

        Map<String, PipelineStage> oldStages = new HashMap<>();
        if (existing != null) {
            for (PipelineStage st : existing.getStages()) oldStages.putIfAbsent(st.getName(), st);
        }
        Map<String, String> clientIds = new HashMap<>();
        for (int i = 0; i < s.getStages().size(); i++) {
            StageSpec st = s.getStages().get(i);
            PipelineStage old = oldStages.get(st.getName());
            clientIds.put(st.getName(), old != null ? old.getId() : "config-stage-" + i);
        }
        List<PipelineRequest.StageRequest> stages = new ArrayList<>();
        for (int i = 0; i < s.getStages().size(); i++) {
            StageSpec st = s.getStages().get(i);
            PipelineStage old = oldStages.get(st.getName());
            PipelineRequest.StageRequest sr = new PipelineRequest.StageRequest();
            sr.setId(clientIds.get(st.getName()));
            sr.setName(st.getName());
            sr.setOrderIndex(i);
            sr.setExecutionMode(
                    st.getMode() == null ? PipelineStage.ExecutionMode.SEQUENTIAL : st.getMode());
            if (old != null) {
                sr.setPositionX(old.getPositionX());
                sr.setPositionY(old.getPositionY());
            }
            sr.setDependsOn(st.getDependsOn().stream().map(clientIds::get).toList());
            sr.setStopOnFailure(st.getStopOnFailure() == null || st.getStopOnFailure());

            List<PipelineStep> oldSteps =
                    old == null ? new ArrayList<>() : new ArrayList<>(old.getSteps());
            List<PipelineRequest.StepRequest> steps = new ArrayList<>();
            for (int j = 0; j < st.getSteps().size(); j++) {
                StepSpec step = st.getSteps().get(j);
                PipelineStep match =
                        oldSteps.stream()
                                .filter(o -> step.getName().equals(o.getName()))
                                .findFirst()
                                .orElse(null);
                if (match != null) oldSteps.remove(match);
                PipelineRequest.StepRequest q = new PipelineRequest.StepRequest();
                q.setId(match == null ? null : match.getId());
                q.setName(step.getName());
                q.setOrderIndex(j);
                q.setStepType(
                        step.getType() == null ? PipelineStep.StepType.SHELL : step.getType());
                q.setConfiguration(json(config(step, ctx)));
                q.setWorkingDirectory(blankToNull(step.getWorkingDirectory()));
                q.setTimeoutSeconds(step.getTimeoutSeconds());
                q.setContinueOnFailure(Boolean.TRUE.equals(step.getContinueOnFailure()));
                q.setEnvironmentVariables(
                        step.getEnv().isEmpty() ? null : json(clear(step.getEnv())));
                q.setArtifactInputPattern(blankToNull(step.getArtifactsIn()));
                q.setArtifactOutputPattern(blankToNull(step.getArtifactsOut()));
                q.setPositionX(match == null ? 0 : match.getPositionX());
                q.setPositionY(match == null ? 0 : match.getPositionY());
                steps.add(q);
            }
            sr.setSteps(steps);
            stages.add(sr);
        }
        req.setStages(stages);
        return req;
    }

    /** The step's settings as stored: a rollout's key becomes its id. */
    private static Map<String, Object> config(StepSpec step, ApplyContext ctx) {
        Map<String, Object> c = new LinkedHashMap<>(step.getConfig());
        if (step.getType() == PipelineStep.StepType.ROLLOUT && c.get("rollout") != null) {
            String key = c.remove("rollout").toString().trim();
            c.put("rolloutId", ctx.ref("rollouts", key));
        }
        return c;
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (pipelines.existsById(record.getTargetId())) {
            service.deletePipeline(record.getTargetId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, PipelineSpec> export(ExportContext ctx) {
        Map<String, PipelineSpec> out = new LinkedHashMap<>();
        List<Pipeline> all = new ArrayList<>(pipelines.findAllByOrderByCreatedAtDesc());
        all.sort(Comparator.comparing(Pipeline::getCreatedAt));
        for (Pipeline p : all) {
            PipelineSpec s = new PipelineSpec();
            String hostKey = ctx.hostKey(p.getDockerHost().getId());
            String key = ctx.key(section(), p.getId(), p.getName());
            if (out.containsKey(key)) key = key + "-" + hostKey;
            s.setHost(hostKey);
            if (!key.equals(p.getName())) s.setName(p.getName());
            s.setDescription(p.getDescription());
            GitRepository repo = p.getGitRepository();
            if (repo != null)
                s.setRepository(ctx.key("gitRepositories", repo.getId(), repo.getName()));
            if (!p.isEnabled()) s.setEnabled(false);
            if (!p.isWebhookEnabled()) s.setWebhook(false);
            if (p.isPollingEnabled()) s.setPolling(true);
            if (p.getPollingIntervalSeconds() != 300)
                s.setPollingInterval(p.getPollingIntervalSeconds());
            s.setBranchFilter(blankToNull(p.getBranchFilter()));

            Map<String, String> stageNames = new HashMap<>();
            for (PipelineStage st : p.getStages()) stageNames.put(st.getId(), st.getName());
            List<PipelineStage> stages = new ArrayList<>(p.getStages());
            stages.sort(Comparator.comparingInt(PipelineStage::getOrderIndex));
            for (PipelineStage st : stages) {
                StageSpec ss = new StageSpec();
                ss.setName(st.getName());
                if (st.getExecutionMode() != PipelineStage.ExecutionMode.SEQUENTIAL) {
                    ss.setMode(st.getExecutionMode());
                }
                ss.setDependsOn(
                        st.getDependsOn() == null
                                ? new ArrayList<>()
                                : st.getDependsOn().stream()
                                        .map(stageNames::get)
                                        .filter(n -> n != null)
                                        .toList());
                if (!st.isStopOnFailure()) ss.setStopOnFailure(false);
                List<PipelineStep> steps = new ArrayList<>(st.getSteps());
                steps.sort(Comparator.comparingInt(PipelineStep::getOrderIndex));
                for (PipelineStep step : steps) {
                    StepSpec x = new StepSpec();
                    x.setName(step.getName());
                    if (step.getStepType() != PipelineStep.StepType.SHELL)
                        x.setType(step.getStepType());
                    Map<String, Object> config = readMap(step.getConfiguration());
                    if (step.getStepType() == PipelineStep.StepType.ROLLOUT
                            && config.get("rolloutId") != null) {
                        String id = config.remove("rolloutId").toString();
                        String name = rollouts.findById(id).map(Rollout::getName).orElse(id);
                        config.put("rollout", ctx.key("rollouts", id, name));
                    }
                    x.setConfig(config);
                    x.setWorkingDirectory(blankToNull(step.getWorkingDirectory()));
                    if (step.getTimeoutSeconds() != 3600)
                        x.setTimeoutSeconds(step.getTimeoutSeconds());
                    if (step.isContinueOnFailure()) x.setContinueOnFailure(true);
                    Map<String, Secret> env = new LinkedHashMap<>();
                    readMap(step.getEnvironmentVariables())
                            .forEach(
                                    (k, v) ->
                                            env.put(
                                                    k,
                                                    Secret.placeholder(
                                                            DockerEntries.escape(
                                                                    v == null
                                                                            ? ""
                                                                            : v.toString()))));
                    x.setEnv(
                            DockerEntries.hideSecrets(
                                    env,
                                    ctx,
                                    section(),
                                    key + "_" + st.getName() + "_" + step.getName(),
                                    "pipeline " + p.getName() + ", step " + step.getName()));
                    x.setArtifactsIn(blankToNull(step.getArtifactInputPattern()));
                    x.setArtifactsOut(blankToNull(step.getArtifactOutputPattern()));
                    ss.getSteps().add(x);
                }
                s.getStages().add(ss);
            }
            out.put(key, s);
        }
        return out;
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> m = PLAIN.readValue(json, new TypeReference<>() {});
            return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Step settings as the pipeline engine reads them: a plain mapper, since IacJson's leave out
     * empty values.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper PLAIN =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static Map<String, String> clear(Map<String, Secret> env) {
        Map<String, String> out = new LinkedHashMap<>();
        if (env != null) env.forEach((k, v) -> out.put(k, v == null ? "" : Secret.valueOf(v)));
        return out;
    }

    private static String json(Object value) {
        try {
            return PLAIN.writeValueAsString(value);
        } catch (Exception e) {
            throw new IacException("could not write the step settings: " + e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
