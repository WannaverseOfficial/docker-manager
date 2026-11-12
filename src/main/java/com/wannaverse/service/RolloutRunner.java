package com.wannaverse.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.wannaverse.dto.ContainerSpec;
import com.wannaverse.dto.GitRepositoryResponse;
import com.wannaverse.dto.RolloutTest;
import com.wannaverse.persistence.*;
import com.wannaverse.persistence.RolloutRun.Status;
import com.wannaverse.security.InputValidator;
import com.wannaverse.service.RolloutChecks.Traffic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs releases and rollbacks of {@link Rollout}s.
 *
 * <p>A release starts the new version in the idle slot, waits until it answers, runs the test steps
 * against it, then moves visitors over through the ingress route: all at once (blue/green, then
 * watched for a while) or in growing shares (canary, watched at every step). While the new version
 * has visitors, its share of 5xx answers and its response time are read from the proxy's log, and
 * its health URL is probed; when a limit is crossed, visitors go back to the previous version (if
 * automatic rollback is on).
 *
 * <p>One run per rollout at a time; newer releases wait, and a queued release is skipped when an
 * even newer one is waiting. Every change of traffic is a reload of the proxy, so going back is
 * instant. Runs interrupted by a restart send visitors back to the previous version.
 */
@Service
public class RolloutRunner {
    private static final Logger log = LoggerFactory.getLogger(RolloutRunner.class);

    public static final String LABEL = "com.wannaverse.rollout";
    public static final String SLOT_LABEL = "com.wannaverse.rollout-slot";
    static final String TEST_LABEL = "com.wannaverse.rollout-test";

    private static final long FLUSH_MS = 2_000;
    private static final int MAX_LOG_CHARS = 1_000_000;
    private static final int TICK_SECONDS = 10;
    private static final int MAX_SAMPLES = 600;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RolloutRepository rollouts;
    private final RolloutRunRepository runs;
    private final IngressService ingress;
    private final NginxApplier applier;
    private final ContainerSpecService specService;
    private final SelfContainer selfContainer;
    private final PipelineRepository pipelineRepository;
    private final PipelineExecutionRepository executionRepository;
    private final PipelineExecutionService pipelineExecutions;
    private final Executor executor;

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Control> controls = new ConcurrentHashMap<>();

    public RolloutRunner(
            RolloutRepository rollouts,
            RolloutRunRepository runs,
            IngressService ingress,
            NginxApplier applier,
            ContainerSpecService specService,
            SelfContainer selfContainer,
            PipelineRepository pipelineRepository,
            PipelineExecutionRepository executionRepository,
            @Lazy PipelineExecutionService pipelineExecutions,
            @Qualifier("rolloutExecutor") Executor executor) {
        this.rollouts = rollouts;
        this.runs = runs;
        this.ingress = ingress;
        this.applier = applier;
        this.specService = specService;
        this.selfContainer = selfContainer;
        this.pipelineRepository = pipelineRepository;
        this.executionRepository = executionRepository;
        this.pipelineExecutions = pipelineExecutions;
        this.executor = executor;
    }

    /** Buttons pressed while a run is going. */
    static final class Control {
        volatile boolean abort;
        volatile String abortedBy;
        volatile boolean promote;
        volatile Boolean approval;
        volatile String decidedBy;
    }

    /** One step of a run, as shown in the UI. */
    public static final class Step {
        public String name;
        public String kind;
        public String status = "PENDING";
        public String message;
        public long startedAt;
        public long finishedAt;

        public Step() {}

        Step(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    /** Traffic in one window of a watched step. */
    public record Sample(
            long at,
            int percent,
            long newRequests,
            long newErrors,
            long newAvgMs,
            long oldRequests,
            long oldErrors,
            long oldAvgMs,
            boolean healthy) {}

    // --- starting runs -----------------------------------------------------------------------

    public RolloutRun release(
            Rollout rollout,
            String image,
            RolloutRun.Trigger trigger,
            String triggeredBy,
            String commitSha) {
        String ref = image == null ? "" : image.trim();
        if (!InputValidator.isValidImageName(ref)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Enter a valid image, for example my-app:1.2");
        }
        RolloutRun run = new RolloutRun();
        run.setRolloutId(rollout.getId());
        run.setKind(RolloutRun.Kind.RELEASE);
        run.setImage(ref);
        run.setStatus(Status.PENDING);
        run.setPhase("Waiting to start");
        run.setTriggerType(trigger);
        run.setTriggeredBy(truncate(triggeredBy, 200));
        run.setCommitSha(commitSha);
        run = saveNumbered(run);
        submit(run.getId());
        return run;
    }

    public RolloutRun rollback(Rollout rollout, String triggeredBy) {
        if (rollout.getPreviousContainer() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "There is no previous version to go back to yet");
        }
        if (active(rollout.getId()).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A release is in progress. Stop it instead: visitors go back to the current"
                            + " version.");
        }
        RolloutRun run = new RolloutRun();
        run.setRolloutId(rollout.getId());
        run.setKind(RolloutRun.Kind.ROLLBACK);
        run.setImage(
                rollout.getPreviousImage() == null
                        ? rollout.getPreviousContainer()
                        : rollout.getPreviousImage());
        run.setStatus(Status.PENDING);
        run.setPhase("Waiting to start");
        run.setTriggerType(RolloutRun.Trigger.ROLLBACK);
        run.setTriggeredBy(truncate(triggeredBy, 200));
        run = saveNumbered(run);
        submit(run.getId());
        return run;
    }

    private synchronized RolloutRun saveNumbered(RolloutRun run) {
        run.setNumber(runs.maxNumber(run.getRolloutId()) + 1);
        return runs.save(run);
    }

    /** The run in progress (or waiting to start) for a rollout. */
    public Optional<RolloutRun> active(String rolloutId) {
        return runs
                .findByRolloutIdAndStatusInOrderByCreatedAtAsc(
                        rolloutId, List.of(Status.PENDING, Status.RUNNING, Status.WAITING_APPROVAL))
                .stream()
                .findFirst();
    }

    private void submit(String runId) {
        executor.execute(() -> execute(runId));
    }

    // --- buttons -----------------------------------------------------------------------------

    public void approve(String runId, boolean approved, String username) {
        RolloutRun run = requireRun(runId);
        if (run.getStatus() != Status.WAITING_APPROVAL) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This release isn't waiting for approval");
        }
        Control c = controls.get(runId);
        if (c == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Not running");
        c.decidedBy = username;
        c.approval = approved;
    }

    public void promote(String runId) {
        RolloutRun run = requireRun(runId);
        Control c = controls.get(runId);
        if (run.getStatus() != Status.RUNNING || c == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This release isn't running");
        }
        c.promote = true;
    }

    /** Stops a run: visitors go back to the version that served before it. */
    public void abort(String runId, String username) {
        RolloutRun run = requireRun(runId);
        if (run.getStatus().finished()) return;
        Control c = controls.computeIfAbsent(runId, k -> new Control());
        c.abortedBy = username;
        c.abort = true;
        if (run.getStatus() == Status.PENDING) {
            run.setStatus(Status.CANCELLED);
            run.setPhase("Cancelled");
            run.setErrorMessage("Cancelled by " + (username == null ? "someone" : username));
            run.setFinishedAt(System.currentTimeMillis());
            runs.save(run);
        }
    }

    private RolloutRun requireRun(String runId) {
        return runs.findById(runId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    // --- running -----------------------------------------------------------------------------

    private static final class Aborted extends RuntimeException {
        Aborted(String message) {
            super(message, null, false, false);
        }
    }

    private static final class CheckFailed extends RuntimeException {
        CheckFailed(String message) {
            super(message, null, false, false);
        }
    }

    /** Everything one run needs while it goes. */
    private final class Ctx {
        final RolloutRun run;
        Rollout rollout;
        final Control control;
        final List<Step> steps = new ArrayList<>();
        final List<Sample> samples = new ArrayList<>();
        final StringBuilder logs;
        long lastFlush = System.currentTimeMillis();
        IngressConfig cfg;
        DockerAPI api;
        String routeId;
        boolean trafficMoved;
        Step current;

        Ctx(RolloutRun run, Rollout rollout, Control control) {
            this.run = run;
            this.rollout = rollout;
            this.control = control;
            this.logs = new StringBuilder(run.getLogs() == null ? "" : run.getLogs());
        }

        synchronized void log(String line) {
            if (logs.length() < MAX_LOG_CHARS) {
                logs.append(LocalTime.now().format(TIME)).append("  ").append(line).append('\n');
            }
            if (System.currentTimeMillis() - lastFlush > FLUSH_MS) save();
        }

        synchronized void save() {
            try {
                run.setLogs(logs.toString());
                run.setStepsJson(JSON.writeValueAsString(steps));
                if (!samples.isEmpty()) run.setMetricsJson(JSON.writeValueAsString(samples));
                runs.save(run);
            } catch (Exception e) {
                log.warn("Could not save rollout run {}: {}", run.getId(), e.getMessage());
            }
            lastFlush = System.currentTimeMillis();
        }

        Step step(String name, String kind) {
            Step s = new Step(name, kind);
            steps.add(s);
            return s;
        }

        void begin(Step s) {
            checkAbort();
            current = s;
            s.status = "RUNNING";
            s.startedAt = System.currentTimeMillis();
            run.setPhase(s.name);
            log("== " + s.name);
            save();
        }

        void pass(Step s, String message) {
            s.status = "PASSED";
            s.message = message;
            s.finishedAt = System.currentTimeMillis();
            if (message != null) log(message);
            current = null;
            save();
        }

        void skip(Step s, String message) {
            s.status = "SKIPPED";
            s.message = message;
            save();
        }

        void phase(String phase) {
            run.setPhase(phase);
            save();
        }

        void checkAbort() {
            if (control.abort) {
                throw new Aborted(
                        "Stopped by "
                                + (control.abortedBy == null ? "someone" : control.abortedBy));
            }
        }

        /** Sleeps, waking up early when someone presses a button. */
        void sleep(long millis) {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                checkAbort();
                try {
                    Thread.sleep(Math.min(500, Math.max(1, end - System.currentTimeMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Aborted("Interrupted");
                }
            }
        }
    }

    void execute(String runId) {
        RolloutRun first = runs.findById(runId).orElse(null);
        if (first == null) return;
        ReentrantLock lock = locks.computeIfAbsent(first.getRolloutId(), k -> new ReentrantLock());
        lock.lock();
        Ctx c = null;
        try {
            RolloutRun run = runs.findById(runId).orElse(null);
            if (run == null || run.getStatus() != Status.PENDING) return;
            Control control = controls.computeIfAbsent(runId, k -> new Control());
            if (run.getKind() == RolloutRun.Kind.RELEASE) {
                Optional<RolloutRun> newer =
                        runs
                                .findByRolloutIdAndStatusInOrderByCreatedAtAsc(
                                        run.getRolloutId(), List.of(Status.PENDING))
                                .stream()
                                .filter(
                                        r ->
                                                r.getKind() == RolloutRun.Kind.RELEASE
                                                        && r.getNumber() > first.getNumber())
                                .reduce((a, b) -> b);
                if (newer.isPresent()) {
                    run.setStatus(Status.CANCELLED);
                    run.setPhase("Skipped");
                    run.setErrorMessage(
                            "Skipped: release #" + newer.get().getNumber() + " is newer");
                    run.setFinishedAt(System.currentTimeMillis());
                    runs.save(run);
                    return;
                }
            }
            Rollout rollout = rollouts.findById(run.getRolloutId()).orElse(null);
            if (rollout == null) {
                run.setStatus(Status.CANCELLED);
                run.setErrorMessage("The rollout was deleted");
                run.setFinishedAt(System.currentTimeMillis());
                runs.save(run);
                return;
            }
            c = new Ctx(run, rollout, control);
            run.setStatus(Status.RUNNING);
            run.setStartedAt(System.currentTimeMillis());
            c.log(
                    (run.getKind() == RolloutRun.Kind.ROLLBACK ? "Rolling back " : "Releasing ")
                            + run.getImage()
                            + " ("
                            + (rollout.getStrategy() == Rollout.Strategy.CANARY
                                    ? "canary"
                                    : "blue/green")
                            + ")");
            c.save();
            if (run.getKind() == RolloutRun.Kind.ROLLBACK) rollbackFlow(c);
            else releaseFlow(c);
        } catch (Exception e) {
            if (c != null) fail(c, e);
            else log.error("Rollout run {} failed to start: {}", runId, e.getMessage(), e);
        } finally {
            if (c != null) c.save();
            controls.remove(runId);
            lock.unlock();
        }
    }

    private void connect(Ctx c) {
        Rollout r = c.rollout;
        c.cfg = ingress.requireEnabledForRollout(r.getDockerHost().getId());
        c.api = applier.api(c.cfg);
        c.routeId = r.getRouteId();
        if (c.routeId != null && ingress.getRoute(c.routeId).isEmpty()) c.routeId = null;
    }

    private void releaseFlow(Ctx c) {
        connect(c);
        Rollout r = c.rollout;
        RolloutRun run = c.run;
        String stable = r.getActiveContainer();
        boolean stableExists = stable != null && NginxApplier.findContainer(c.api, stable) != null;
        if (stable != null && !stableExists) {
            c.log(
                    "The current version's container "
                            + stable
                            + " doesn't exist any more, so the new version can't share traffic"
                            + " with it or go back to it.");
            stable = null;
        }
        String target = r.idleSlot();
        run.setTargetContainer(target);
        run.setStableContainer(stable);
        run.setStableImage(stable == null ? null : r.getActiveImage());

        Step start = c.step("Start the new version", "START");
        List<RolloutTest> tests = tests(r);
        List<Step> testSteps = new ArrayList<>();
        for (RolloutTest t : tests) testSteps.add(c.step(t.displayName(), t.kindOrHttp()));
        boolean canary =
                r.getStrategy() == Rollout.Strategy.CANARY && stable != null && c.routeId != null;
        List<Integer> weights = canary ? RolloutChecks.parseSteps(r.getCanarySteps()) : List.of();
        if (canary && weights.isEmpty()) canary = false;
        List<Step> canarySteps = new ArrayList<>();
        Step switchStep = null;
        Step watch = null;
        Step promote = null;
        if (canary) {
            for (int w : weights) canarySteps.add(c.step(w + "% of visitors", "CANARY"));
            promote = c.step("All visitors", "PROMOTE");
        } else {
            switchStep = c.step("Switch visitors to the new version", "SWITCH");
            if (r.getBakeSeconds() > 0 && stable != null) {
                watch = c.step("Watch for " + duration(r.getBakeSeconds()), "WATCH");
            }
        }
        if (r.getStrategy() == Rollout.Strategy.CANARY && stable == null) {
            c.log("No previous version is running, so this release gets all visitors at once.");
        }
        c.save();

        // Start the new version next to the current one.
        c.begin(start);
        startSlot(c, target, run.getImage());
        waitReady(c, target, r.getStartTimeoutSeconds());
        c.pass(start, target + " is running " + run.getImage() + " and answers");

        pointPreview(c, target);

        for (int i = 0; i < tests.size(); i++) {
            Step s = testSteps.get(i);
            c.begin(s);
            String message = runTest(c, tests.get(i), s, target);
            c.pass(s, message);
        }

        // Move visitors.
        int port = r.getContainerPort();
        if (canary) {
            boolean promoted = false;
            for (int i = 0; i < weights.size(); i++) {
                Step s = canarySteps.get(i);
                if (promoted) {
                    c.skip(s, "Promoted early");
                    continue;
                }
                int w = weights.get(i);
                c.begin(s);
                setTraffic(c, stable, target, w, true);
                run.setTrafficPercent(w);
                c.log(w + "% of visitors now go to the new version");
                promoted = watch(c, target, stable, r.getStepSeconds(), w, s);
                c.pass(s, summary(c, s));
            }
            c.begin(promote);
            setTraffic(c, target, null, 0, false);
            run.setTrafficPercent(100);
            c.pass(promote, "All visitors now go to " + target);
        } else {
            c.begin(switchStep);
            if (c.routeId == null) createRoute(c, target);
            setTraffic(c, target, null, 0, watch != null);
            run.setTrafficPercent(100);
            c.pass(switchStep, "All visitors now go to " + target);
            if (watch != null) {
                c.begin(watch);
                watch(c, target, stable, r.getBakeSeconds(), 100, watch);
                setTraffic(c, target, null, 0, false);
                c.pass(watch, summary(c, watch));
            }
        }

        finish(c, target, run.getImage(), stable, run.getStableImage());
    }

    private void rollbackFlow(Ctx c) {
        connect(c);
        Rollout r = c.rollout;
        RolloutRun run = c.run;
        String target = r.getPreviousContainer();
        String current = r.getActiveContainer();
        run.setTargetContainer(target);
        run.setStableContainer(current);
        run.setStableImage(r.getActiveImage());
        Step start = c.step("Start the previous version", "START");
        Step sw = c.step("Switch visitors back", "SWITCH");
        c.save();

        c.begin(start);
        String id = NginxApplier.findContainer(c.api, target);
        if (id == null) {
            throw new CheckFailed(
                    "The previous version's container " + target + " doesn't exist any more");
        }
        var state = c.api.inspectContainer(id).getState();
        if (!Boolean.TRUE.equals(state.getRunning())) {
            c.log("Starting " + target);
            c.api.startContainer(id);
        }
        joinIngress(c, id);
        waitReady(c, target, r.getStartTimeoutSeconds());
        c.pass(start, target + " answers");

        c.begin(sw);
        if (c.routeId == null) createRoute(c, target);
        else setTraffic(c, target, null, 0, false);
        run.setTrafficPercent(100);
        c.pass(sw, "All visitors now go to " + target);

        finish(c, target, r.getPreviousImage(), current, r.getActiveImage());
    }

    /** Records the new state, stops the version that served before, and ends the run. */
    private void finish(Ctx c, String active, String image, String previous, String prevImage) {
        c.phase("Finishing");
        Rollout fresh = rollouts.findById(c.rollout.getId()).orElse(c.rollout);
        if (previous != null && !previous.equals(active)) {
            String id = NginxApplier.findContainer(c.api, previous);
            if (id != null && !fresh.isKeepPreviousRunning()) {
                try {
                    selfContainer.protect(id, "stop");
                    c.api.stopContainer(id, 10);
                    c.log(
                            "Stopped the previous version ("
                                    + previous
                                    + "). Roll back starts it again.");
                } catch (Exception e) {
                    c.log("Could not stop " + previous + ": " + e.getMessage());
                }
            } else if (id != null) {
                c.log(
                        "The previous version ("
                                + previous
                                + ") keeps running for an instant roll back.");
            }
        }
        fresh.setActiveContainer(active);
        fresh.setActiveImage(image);
        fresh.setPreviousContainer(previous);
        fresh.setPreviousImage(prevImage);
        if (c.routeId != null) fresh.setRouteId(c.routeId);
        c.rollout = rollouts.save(fresh);
        pointPreview(c, active);
        RolloutRun run = c.run;
        run.setStatus(Status.SUCCEEDED);
        run.setTrafficPercent(100);
        run.setPhase(run.getKind() == RolloutRun.Kind.ROLLBACK ? "Rolled back" : "Released");
        run.setFinishedAt(System.currentTimeMillis());
        c.log("Done: " + active + " serves all visitors");
        c.save();
    }

    /** Ends a failed or stopped run, sending visitors back where they were. */
    private void fail(Ctx c, Exception e) {
        RolloutRun run = c.run;
        boolean aborted = e instanceof Aborted;
        String message =
                e instanceof Aborted || e instanceof CheckFailed
                        ? e.getMessage()
                        : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (!(e instanceof Aborted) && !(e instanceof CheckFailed)) {
            log.warn("Rollout run {} failed: {}", run.getId(), message, e);
        }
        c.log((aborted ? "Stopped: " : "Failed: ") + message);
        if (c.current != null) {
            c.current.status = "FAILED";
            c.current.message = message;
            c.current.finishedAt = System.currentTimeMillis();
        }
        for (Step s : c.steps) if ("PENDING".equals(s.status)) s.status = "SKIPPED";
        // Show at once that it is ending; stopping containers can take a few seconds.
        run.setStatus(Status.RUNNING);
        run.setApprovalStep(null);
        run.setPhase(c.trafficMoved ? "Sending visitors back" : "Stopping");
        c.save();

        String stable = run.getStableContainer();
        String target = run.getTargetContainer();
        Status end = aborted ? Status.CANCELLED : Status.FAILED;
        boolean targetServes = false;
        if (c.trafficMoved) {
            if (stable != null) {
                try {
                    setTraffic(c, stable, null, 0, false);
                    run.setTrafficPercent(0);
                    c.log("Visitors are back on " + stable);
                    end = Status.ROLLED_BACK;
                } catch (Exception ex) {
                    c.log("Could not send visitors back to " + stable + ": " + ex.getMessage());
                    targetServes = true;
                }
            } else {
                targetServes = true;
                c.log(
                        "There is no previous version to go back to, so the new version keeps"
                                + " serving.");
                try {
                    setTraffic(c, target, null, 0, false);
                } catch (Exception ignored) {
                }
                Rollout fresh = rollouts.findById(c.rollout.getId()).orElse(c.rollout);
                fresh.setActiveContainer(target);
                fresh.setActiveImage(run.getImage());
                if (c.routeId != null) fresh.setRouteId(c.routeId);
                c.rollout = rollouts.save(fresh);
            }
        }
        if (!targetServes
                && target != null
                && run.getKind() == RolloutRun.Kind.RELEASE
                && c.api != null) {
            try {
                String id = NginxApplier.findContainer(c.api, target);
                if (id != null) {
                    c.api.stopContainer(id, 10);
                    c.log(
                            "Stopped the new version ("
                                    + target
                                    + "); its logs are kept until the next release");
                }
            } catch (Exception ex) {
                log.debug("Could not stop {}: {}", target, ex.getMessage());
            }
        }
        if (c.api != null) {
            try {
                String active =
                        rollouts.findById(c.rollout.getId())
                                .map(Rollout::getActiveContainer)
                                .orElse(null);
                if (active != null) pointPreview(c, active);
            } catch (Exception ignored) {
            }
        }
        run.setStatus(end);
        run.setApprovalStep(null);
        run.setErrorMessage(truncate(message, 2000));
        run.setPhase(
                switch (end) {
                    case ROLLED_BACK -> "Rolled back";
                    case CANCELLED -> "Stopped";
                    default -> "Failed";
                });
        run.setFinishedAt(System.currentTimeMillis());
        c.save();
    }

    // --- containers --------------------------------------------------------------------------

    private void startSlot(Ctx c, String name, String image) {
        Rollout r = c.rollout;
        String existing = NginxApplier.findContainer(c.api, name);
        if (existing != null) {
            InspectContainerResponse inspect = c.api.inspectContainer(existing);
            Map<String, String> labels =
                    inspect.getConfig() == null || inspect.getConfig().getLabels() == null
                            ? Map.of()
                            : inspect.getConfig().getLabels();
            if (!r.getId().equals(labels.get(LABEL))) {
                throw new CheckFailed(
                        "A container named "
                                + name
                                + " already exists and wasn't created by this rollout. Rename it,"
                                + " or change the rollout's container name.");
            }
            selfContainer.protect(existing, "replace");
            c.api.forceRemoveContainer(existing);
            c.log(
                    "Removed the older container "
                            + name
                            + " ("
                            + (inspect.getConfig() == null ? "?" : inspect.getConfig().getImage())
                            + ")");
        }
        ContainerSpec spec = GitRepositoryResponse.readSpec(r.getContainerSpecJson());
        if (spec == null) spec = new ContainerSpec();
        spec.setName(name);
        spec.setImage(image);
        spec.setStart(true);
        // Both versions run at once, so neither may take a port on the server; visitors come in
        // through the proxy.
        List<ContainerSpec.PortMapping> ports = new ArrayList<>();
        for (ContainerSpec.PortMapping p :
                spec.getPorts() == null ? List.<ContainerSpec.PortMapping>of() : spec.getPorts()) {
            ports.add(
                    new ContainerSpec.PortMapping(
                            null, null, p.getContainerPort(), p.getProtocol()));
        }
        spec.setPorts(ports);
        Map<String, String> labels =
                new LinkedHashMap<>(spec.getLabels() == null ? Map.of() : spec.getLabels());
        labels.put(LABEL, r.getId());
        labels.put(SLOT_LABEL, name);
        spec.setLabels(labels);
        List<String> networks =
                new ArrayList<>(spec.getNetworks() == null ? List.of() : spec.getNetworks());
        networks.remove(c.cfg.getIngressNetworkName());
        networks.add(0, c.cfg.getIngressNetworkName());
        spec.setNetworks(networks);
        c.log("Starting " + name + " from " + image);
        String id = specService.create(c.api, spec);
        c.log("Started " + name + " (" + id.substring(0, 12) + ")");
    }

    private void joinIngress(Ctx c, String containerId) {
        try {
            c.api.connectContainerToNetwork(c.cfg.getIngressNetworkName(), containerId);
        } catch (Exception e) {
            // Already connected.
        }
    }

    /** Waits until the container runs and its health path answers with 200-399. */
    private void waitReady(Ctx c, String name, int timeoutSeconds) {
        Rollout r = c.rollout;
        long deadline = System.currentTimeMillis() + Math.max(10, timeoutSeconds) * 1000L;
        String url = url(name, r.getContainerPort(), r.getHealthPath());
        String last = "no answer yet";
        c.log("Waiting for " + url + " to answer");
        while (System.currentTimeMillis() < deadline) {
            c.checkAbort();
            String problem = containerProblem(c, name);
            if (problem != null) throw new CheckFailed(problem + logsTail(c, name));
            String id = NginxApplier.findContainer(c.api, name);
            var health = id == null ? null : c.api.inspectContainer(id).getState().getHealth();
            if (health != null && "unhealthy".equals(health.getStatus())) {
                throw new CheckFailed(
                        "Docker reports the new version as unhealthy" + logsTail(c, name));
            }
            boolean healthStarting = health != null && "starting".equals(health.getStatus());
            Probe p = probe(c, url, 5);
            if (p.status() >= 200 && p.status() < 400 && !healthStarting) return;
            last = p.status() > 0 ? "HTTP " + p.status() : p.error();
            if (healthStarting && p.status() > 0) last += " (Docker health check still starting)";
            c.sleep(2000);
        }
        throw new CheckFailed(
                "Not ready after " + duration(timeoutSeconds) + ": " + last + logsTail(c, name));
    }

    /** Why the container isn't usable (stopped, crashing), or null when it runs. */
    private String containerProblem(Ctx c, String name) {
        String id = NginxApplier.findContainer(c.api, name);
        if (id == null) return "The container " + name + " disappeared";
        var state = c.api.inspectContainer(id).getState();
        if (Boolean.TRUE.equals(state.getRestarting())) return name + " keeps crashing";
        if (!Boolean.TRUE.equals(state.getRunning())) {
            return name + " stopped (exit code " + state.getExitCodeLong() + ")";
        }
        return null;
    }

    private String logsTail(Ctx c, String name) {
        try {
            String id = NginxApplier.findContainer(c.api, name);
            if (id == null) return "";
            String tail = c.api.getContainerLogs(id, 25, false);
            if (tail == null || tail.isBlank()) return "";
            c.log("Last lines from " + name + ":");
            tail.lines().forEach(l -> c.log("  | " + l));
            return ". See the log for its last lines.";
        } catch (Exception e) {
            return "";
        }
    }

    // --- traffic -----------------------------------------------------------------------------

    private void setTraffic(Ctx c, String stable, String canary, int weight, boolean logTraffic) {
        Rollout r = c.rollout;
        ingress.setTraffic(
                c.routeId,
                stable,
                r.getContainerPort(),
                canary,
                weight,
                r.isStickySessions(),
                logTraffic);
        c.trafficMoved = true;
    }

    private void createRoute(Ctx c, String target) {
        Rollout r = c.rollout;
        IngressRoute route =
                ingress.createRolloutRoute(
                        r.getDockerHost().getId(),
                        r.getId(),
                        r.getHostname(),
                        r.getPathPrefix(),
                        r.getTlsMode(),
                        target,
                        r.getContainerPort(),
                        "rollout " + r.getName());
        c.routeId = route.getId();
        c.trafficMoved = true;
        Rollout fresh = rollouts.findById(r.getId()).orElse(r);
        fresh.setRouteId(route.getId());
        c.rollout = rollouts.save(fresh);
        c.log("Created the route " + route.getHostname() + route.getPathPrefix());
    }

    /** Points the preview address at a container, creating its route the first time. */
    private void pointPreview(Ctx c, String container) {
        Rollout r = c.rollout;
        if (r.getPreviewHostname() == null || r.getPreviewHostname().isBlank()) return;
        try {
            String id = r.getPreviewRouteId();
            if (id == null || ingress.getRoute(id).isEmpty()) {
                // A route this rollout already made for the address (its id was lost): reuse it.
                id = ownRoute(r, r.getPreviewHostname());
                if (id != null) {
                    Rollout fresh = rollouts.findById(r.getId()).orElse(r);
                    fresh.setPreviewRouteId(id);
                    c.rollout = rollouts.save(fresh);
                }
            }
            if (id != null) {
                ingress.setTraffic(id, container, r.getContainerPort(), null, 0, true, false);
            } else {
                IngressRoute route =
                        ingress.createRolloutRoute(
                                r.getDockerHost().getId(),
                                r.getId(),
                                r.getPreviewHostname(),
                                "/",
                                r.getTlsMode(),
                                container,
                                r.getContainerPort(),
                                "rollout " + r.getName());
                Rollout fresh = rollouts.findById(r.getId()).orElse(r);
                fresh.setPreviewRouteId(route.getId());
                c.rollout = rollouts.save(fresh);
            }
            c.log("Preview address " + r.getPreviewHostname() + " shows " + container);
        } catch (Exception e) {
            c.log("Could not update the preview address: " + e.getMessage());
        }
    }

    /** The id of a route for {@code hostname} that this rollout manages, or null. */
    String ownRoute(Rollout r, String hostname) {
        if (hostname == null) return null;
        return ingress.getRoutes(r.getDockerHost().getId()).stream()
                .filter(x -> r.getId().equals(x.getRolloutId()) && hostname.equals(x.getHostname()))
                .map(IngressRoute::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Watches the new version for {@code seconds}: probes its health path and reads the share of
     * failed requests. Throws {@link CheckFailed} when a limit is crossed (and automatic rollback
     * is on). Returns true when someone pressed Promote.
     */
    private boolean watch(
            Ctx c, String target, String stable, int seconds, int percent, Step step) {
        Rollout r = c.rollout;
        readTraffic(c); // start from an empty log
        long started = System.currentTimeMillis();
        long end = started + Math.max(10, seconds) * 1000L;
        Traffic newTotal = Traffic.NONE;
        Traffic oldTotal = Traffic.NONE;
        int probeFailures = 0;
        Set<String> warned = new HashSet<>();
        String url = url(target, r.getContainerPort(), r.getHealthPath());
        while (System.currentTimeMillis() < end) {
            long tickEnd = Math.min(end, System.currentTimeMillis() + TICK_SECONDS * 1000L);
            while (System.currentTimeMillis() < tickEnd) {
                c.sleep(Math.min(1000, Math.max(1, tickEnd - System.currentTimeMillis())));
                if (c.control.promote) {
                    c.control.promote = false;
                    c.log("Promoted by hand");
                    return true;
                }
            }
            Map<String, Traffic> window = readTraffic(c);
            Traffic tNew = window.getOrDefault(target, Traffic.NONE);
            Traffic tOld =
                    stable == null ? Traffic.NONE : window.getOrDefault(stable, Traffic.NONE);
            newTotal = newTotal.plus(tNew);
            oldTotal = oldTotal.plus(tOld);
            Probe p = probe(c, url, 5);
            boolean healthy = p.status() >= 200 && p.status() < 400;
            probeFailures = healthy ? 0 : probeFailures + 1;
            if (c.samples.size() < MAX_SAMPLES) {
                c.samples.add(
                        new Sample(
                                System.currentTimeMillis(),
                                percent,
                                tNew.requests(),
                                tNew.errors(),
                                tNew.avgMillis(),
                                tOld.requests(),
                                tOld.errors(),
                                tOld.avgMillis(),
                                healthy));
            }
            step.message =
                    String.format(
                            Locale.ROOT,
                            "%d requests, %.1f%% failed, %d ms on average",
                            newTotal.requests(),
                            newTotal.errorRate(),
                            newTotal.avgMillis());
            long left = Math.max(0, (end - System.currentTimeMillis()) / 1000);
            c.run.setPhase(step.name + ", " + duration((int) left) + " left");
            c.save();

            List<String> problems = new ArrayList<>();
            String crashed = containerProblem(c, target);
            if (crashed != null) problems.add(crashed);
            if (probeFailures >= 3) {
                problems.add(
                        r.getHealthPath()
                                + " failed 3 times in a row ("
                                + (p.status() > 0 ? "HTTP " + p.status() : p.error())
                                + ")");
            }
            String judged =
                    RolloutChecks.judge(
                            newTotal,
                            r.getMaxErrorRate(),
                            r.getMaxAvgResponseMs(),
                            r.getMinRequests());
            if (judged != null) problems.add(judged);
            for (String problem : problems) {
                if (r.isAutoRollback() || crashed != null) {
                    throw new CheckFailed("The new version failed its checks: " + problem);
                }
                if (warned.add(problem.replaceAll("\\d", ""))) {
                    c.log("Warning: " + problem + " (automatic rollback is off, carrying on)");
                }
            }
        }
        return false;
    }

    private String summary(Ctx c, Step s) {
        return s.message == null ? "Done" : s.message;
    }

    private Map<String, Traffic> readTraffic(Ctx c) {
        try {
            DockerAPI.ExecOutput out =
                    applier.execInProxy(
                            c.cfg,
                            30,
                            "sh",
                            "-c",
                            RolloutChecks.TRAFFIC_SCRIPT,
                            "sh",
                            NginxConfigGenerator.trafficLogFile(c.routeId));
            return RolloutChecks.parseTraffic(out.stdoutText());
        } catch (Exception e) {
            c.log("Could not read the proxy's traffic log: " + e.getMessage());
            return Map.of();
        }
    }

    // --- tests -------------------------------------------------------------------------------

    record Probe(int status, String body, String error) {}

    private static String url(String container, int port, String path) {
        String p = path == null || path.isBlank() ? "/" : path.trim();
        if (!p.startsWith("/")) p = "/" + p;
        return "http://" + container + ":" + port + p;
    }

    /** An HTTP request from inside the proxy, which shares a network with every slot. */
    private Probe probe(Ctx c, String url, int timeoutSeconds) {
        List<String> cmd =
                new ArrayList<>(
                        List.of(
                                "wget",
                                "-q",
                                "-S",
                                "-T",
                                String.valueOf(timeoutSeconds),
                                "-O",
                                "-"));
        String host = hostHeader(c);
        if (host != null) {
            cmd.add("--header");
            cmd.add("Host: " + host);
        }
        cmd.add(url);
        try {
            DockerAPI.ExecOutput out =
                    applier.execInProxy(c.cfg, timeoutSeconds + 10L, cmd.toArray(String[]::new));
            int status = RolloutChecks.lastStatus(out.stderr());
            String error = null;
            if (status == 0) {
                String err = out.stderr() == null ? "" : out.stderr().strip();
                error = err.isEmpty() ? "no answer" : err.lines().reduce((a, b) -> b).orElse(err);
                error = error.replaceFirst("^wget: ", "");
            }
            return new Probe(status, out.stdoutText(), error);
        } catch (Exception e) {
            return new Probe(0, "", e.getMessage());
        }
    }

    private String hostHeader(Ctx c) {
        if (c.routeId != null) {
            var route = ingress.getRoute(c.routeId);
            if (route.isPresent()) return route.get().getHostname();
        }
        String h = c.rollout.getHostname();
        return h == null || h.isBlank() ? null : h;
    }

    private String runTest(Ctx c, RolloutTest t, Step step, String target) {
        return switch (t.kindOrHttp()) {
            case "COMMAND" -> commandTest(c, t, target);
            case "PIPELINE" -> pipelineTest(c, t, target);
            case "APPROVAL" -> approval(c, t, step);
            default -> httpTest(c, t, target);
        };
    }

    private String httpTest(Ctx c, RolloutTest t, String target) {
        String url = url(target, c.rollout.getContainerPort(), t.getPath());
        int tries = t.getRetries() == null || t.getRetries() < 1 ? 3 : Math.min(t.getRetries(), 30);
        int timeout =
                t.getTimeoutSeconds() == null || t.getTimeoutSeconds() < 1
                        ? 10
                        : Math.min(t.getTimeoutSeconds(), 300);
        String last = null;
        for (int i = 1; i <= tries; i++) {
            Probe p = probe(c, url, timeout);
            if (p.status() == 0) {
                last = "No answer from " + url + ": " + p.error();
            } else if (!RolloutChecks.statusMatches(p.status(), t.getExpectStatus())) {
                last =
                        url
                                + " answered HTTP "
                                + p.status()
                                + ", expected "
                                + (t.getExpectStatus() == null || t.getExpectStatus().isBlank()
                                        ? "200-399"
                                        : t.getExpectStatus());
            } else if (t.getBodyContains() != null
                    && !t.getBodyContains().isEmpty()
                    && (p.body() == null || !p.body().contains(t.getBodyContains()))) {
                last = "The page doesn't contain \"" + t.getBodyContains() + "\"";
            } else {
                return "HTTP " + p.status() + " from " + url;
            }
            c.log("Try " + i + "/" + tries + ": " + last);
            if (i < tries) c.sleep(3000);
        }
        throw new CheckFailed(last);
    }

    private Map<String, String> testEnv(Ctx c, String target) {
        Rollout r = c.rollout;
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TARGET_URL", "http://" + target + ":" + r.getContainerPort());
        env.put("TARGET_HOST", target);
        env.put("TARGET_PORT", String.valueOf(r.getContainerPort()));
        String host = hostHeader(c);
        if (host != null) env.put("PUBLIC_HOSTNAME", host);
        env.put("RELEASE_IMAGE", c.run.getImage());
        env.put("RELEASE_NUMBER", String.valueOf(c.run.getNumber()));
        env.put("ROLLOUT_NAME", r.getName());
        return env;
    }

    private String commandTest(Ctx c, RolloutTest t, String target) {
        String image = t.getImage().trim();
        int timeout =
                t.getTimeoutSeconds() == null || t.getTimeoutSeconds() < 1
                        ? 600
                        : t.getTimeoutSeconds();
        c.log("Running a command in " + image);
        specService.ensureImage(c.api, image, false);
        List<String> env = new ArrayList<>();
        testEnv(c, target).forEach((k, v) -> env.add(k + "=" + v));
        String id =
                c.api.client()
                        .createContainerCmd(image)
                        .withCmd("/bin/sh", "-c", t.getScript())
                        .withEnv(env)
                        .withLabels(Map.of(TEST_LABEL, c.rollout.getId()))
                        .withHostConfig(
                                HostConfig.newHostConfig()
                                        .withNetworkMode(c.cfg.getIngressNetworkName()))
                        .exec()
                        .getId();
        try {
            c.api.startContainer(id);
            long deadline = System.currentTimeMillis() + timeout * 1000L;
            Long exit = null;
            while (exit == null) {
                if (c.control.abort) {
                    killQuietly(c, id);
                    c.checkAbort();
                }
                if (System.currentTimeMillis() > deadline) {
                    killQuietly(c, id);
                    appendLogs(c, id);
                    throw new CheckFailed("The command took longer than " + duration(timeout));
                }
                var state = c.api.inspectContainer(id).getState();
                if (!Boolean.TRUE.equals(state.getRunning())) {
                    exit = state.getExitCodeLong() == null ? -1L : state.getExitCodeLong();
                } else {
                    c.sleep(1000);
                }
            }
            appendLogs(c, id);
            if (exit != 0) throw new CheckFailed("The command failed with exit code " + exit);
            return "The command passed";
        } finally {
            try {
                c.api.forceRemoveContainer(id);
            } catch (Exception ignored) {
            }
        }
    }

    private void appendLogs(Ctx c, String id) {
        try {
            String out = c.api.getContainerLogs(id, 300, false);
            if (out != null) out.lines().forEach(l -> c.log("  | " + l));
        } catch (Exception ignored) {
        }
    }

    private void killQuietly(Ctx c, String id) {
        try {
            c.api.killContainer(id);
        } catch (Exception ignored) {
        }
    }

    private String pipelineTest(Ctx c, RolloutTest t, String target) {
        Pipeline pipeline =
                pipelineRepository
                        .findById(t.getPipelineId())
                        .orElseThrow(() -> new CheckFailed("The test pipeline was deleted"));
        int timeout =
                t.getTimeoutSeconds() == null || t.getTimeoutSeconds() < 1
                        ? 3600
                        : t.getTimeoutSeconds();
        String hostId = pipeline.getDockerHost() == null ? null : pipeline.getDockerHost().getId();
        if (!c.rollout.getDockerHost().getId().equals(hostId)) {
            throw new CheckFailed(
                    "The pipeline " + pipeline.getName() + " runs on another Docker host");
        }
        PipelineExecution exec =
                pipelineExecutions.triggerExecution(
                        pipeline.getId(),
                        PipelineExecution.TriggerType.ROLLOUT,
                        null,
                        null,
                        "rollout " + c.rollout.getName() + " #" + c.run.getNumber(),
                        testEnv(c, target),
                        c.cfg.getIngressNetworkName());
        c.log("Started pipeline " + pipeline.getName() + " #" + exec.getBuildNumber());
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        while (true) {
            if (c.control.abort) {
                pipelineExecutions.cancelExecution(exec.getId());
                c.checkAbort();
            }
            if (System.currentTimeMillis() > deadline) {
                pipelineExecutions.cancelExecution(exec.getId());
                throw new CheckFailed("The pipeline took longer than " + duration(timeout));
            }
            var status =
                    executionRepository
                            .findById(exec.getId())
                            .map(PipelineExecution::getStatus)
                            .orElse(PipelineExecution.ExecutionStatus.CANCELLED);
            switch (status) {
                case SUCCESS -> {
                    return "Pipeline "
                            + pipeline.getName()
                            + " #"
                            + exec.getBuildNumber()
                            + " passed";
                }
                case FAILED, CANCELLED ->
                        throw new CheckFailed(
                                "Pipeline "
                                        + pipeline.getName()
                                        + " #"
                                        + exec.getBuildNumber()
                                        + (status == PipelineExecution.ExecutionStatus.FAILED
                                                ? " failed"
                                                : " was cancelled"));
                default -> c.sleep(3000);
            }
        }
    }

    private String approval(Ctx c, RolloutTest t, Step step) {
        RolloutRun run = c.run;
        int timeout = t.getTimeoutSeconds() == null ? 0 : t.getTimeoutSeconds();
        run.setStatus(Status.WAITING_APPROVAL);
        run.setApprovalStep(step.name);
        step.status = "WAITING";
        String preview = c.rollout.getPreviewHostname();
        run.setPhase("Waiting for approval");
        c.log(
                "Waiting for someone to approve"
                        + (preview != null && !preview.isBlank()
                                ? ". The new version can be seen at " + preview
                                : ""));
        c.save();
        long deadline = timeout > 0 ? System.currentTimeMillis() + timeout * 1000L : Long.MAX_VALUE;
        try {
            while (c.control.approval == null) {
                if (System.currentTimeMillis() > deadline) {
                    throw new CheckFailed("No one approved within " + duration(timeout));
                }
                c.sleep(1000);
            }
        } finally {
            run.setStatus(Status.RUNNING);
            run.setApprovalStep(null);
            step.status = "RUNNING";
        }
        String who = c.control.decidedBy == null ? "someone" : c.control.decidedBy;
        boolean ok = c.control.approval;
        c.control.approval = null;
        run.setApprovedBy(who);
        if (!ok) throw new CheckFailed("Rejected by " + who);
        return "Approved by " + who;
    }

    // --- restarts ----------------------------------------------------------------------------

    /**
     * Runs that were going when Docker Manager stopped: send visitors back to the version that
     * served before, stop the new one, and mark them failed. Runs that never started are started.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        List<RolloutRun> interrupted =
                runs.findByStatusIn(List.of(Status.RUNNING, Status.WAITING_APPROVAL));
        for (RolloutRun run : interrupted) {
            String note = "Interrupted because Docker Manager restarted";
            try {
                Rollout r = rollouts.findById(run.getRolloutId()).orElse(null);
                if (r != null && r.getRouteId() != null && run.getStableContainer() != null) {
                    IngressRoute route = ingress.getRoute(r.getRouteId()).orElse(null);
                    if (route != null
                            && (route.splitsTraffic()
                                    || Boolean.TRUE.equals(route.getTrafficLog())
                                    || !run.getStableContainer()
                                            .equals(route.getTargetContainerName()))) {
                        ingress.setTraffic(
                                route.getId(),
                                run.getStableContainer(),
                                r.getContainerPort(),
                                null,
                                0,
                                r.isStickySessions(),
                                false);
                        note += "; visitors were sent back to " + run.getStableContainer();
                    }
                }
                if (r != null
                        && run.getTargetContainer() != null
                        && !run.getTargetContainer().equals(r.getActiveContainer())
                        && run.getKind() == RolloutRun.Kind.RELEASE) {
                    try {
                        IngressConfig cfg =
                                ingress.requireEnabledForRollout(r.getDockerHost().getId());
                        DockerAPI api = applier.api(cfg);
                        String id = NginxApplier.findContainer(api, run.getTargetContainer());
                        if (id != null) api.stopContainer(id, 10);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                note += " (could not restore traffic: " + e.getMessage() + ")";
                log.warn("Could not restore rollout run {}: {}", run.getId(), e.getMessage());
            }
            run.setStatus(run.getTrafficPercent() > 0 ? Status.ROLLED_BACK : Status.FAILED);
            run.setErrorMessage(note);
            run.setPhase("Interrupted");
            run.setApprovalStep(null);
            run.setFinishedAt(System.currentTimeMillis());
            run.setLogs((run.getLogs() == null ? "" : run.getLogs()) + note + "\n");
            runs.save(run);
        }
        for (RolloutRun run : runs.findByStatusIn(List.of(Status.PENDING))) {
            submit(run.getId());
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    static List<RolloutTest> tests(Rollout r) {
        if (r.getTestsJson() == null || r.getTestsJson().isBlank()) return List.of();
        try {
            return JSON.readValue(r.getTestsJson(), new TypeReference<List<RolloutTest>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    static String duration(int seconds) {
        if (seconds < 60) return seconds + "s";
        int m = seconds / 60;
        int s = seconds % 60;
        if (m < 60) return s == 0 ? m + " min" : m + " min " + s + "s";
        int h = m / 60;
        return (m % 60) == 0 ? h + " h" : h + " h " + (m % 60) + " min";
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
