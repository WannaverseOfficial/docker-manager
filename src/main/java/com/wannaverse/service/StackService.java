package com.wannaverse.service;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.wannaverse.persistence.*;
import com.wannaverse.persistence.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Compose stacks: create, edit, deploy, stop, remove and roll back.
 *
 * <p>Before, a compose deployment wrote the file to a temporary folder that was deleted right after
 * `up`, so the stack could never be updated, stopped or removed from the UI afterwards. Now each
 * stack's compose file and .env are stored in the database and in {@code
 * STACKS_DIR/<host>/<name>/}, and every deploy is recorded as a version.
 *
 * <p>Compose projects started outside Docker Manager are listed too (from their container labels)
 * and can be imported to manage them here.
 */
@Service
public class StackService {
    private static final Logger log = LoggerFactory.getLogger(StackService.class);

    public static final String LABEL_PROJECT = "com.docker.compose.project";
    public static final String LABEL_SERVICE = "com.docker.compose.service";
    public static final String LABEL_CONFIG_FILES = "com.docker.compose.project.config_files";
    public static final String LABEL_WORKING_DIR = "com.docker.compose.project.working_dir";

    /** Compose's own rule for project names. */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,62}$");

    private static final String COMPOSE_FILE = "compose.yaml";
    private static final String ENV_FILE = ".env";

    private final StackRepository stackRepository;
    private final DockerHostRepository hostRepository;
    private final ComposeDeploymentRepository composeRepository;
    private final DockerService dockerService;
    private final ComposeRunner runner;
    private final ComposeInspector inspector;
    private final ImagePolicyService imagePolicyService;
    private final Executor executor;
    private final Path stacksDir;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final StackImportDetector detector;

    public StackService(
            StackRepository stackRepository,
            DockerHostRepository hostRepository,
            ComposeDeploymentRepository composeRepository,
            DockerService dockerService,
            ComposeRunner runner,
            ComposeInspector inspector,
            ImagePolicyService imagePolicyService,
            StackImportDetector detector,
            @Qualifier("deploymentExecutor") Executor executor,
            @Value("${app.stacks-dir:stacks}") String stacksDir) {
        this.stackRepository = stackRepository;
        this.hostRepository = hostRepository;
        this.composeRepository = composeRepository;
        this.dockerService = dockerService;
        this.runner = runner;
        this.inspector = inspector;
        this.imagePolicyService = imagePolicyService;
        this.detector = detector;
        this.executor = executor;
        this.stacksDir = Path.of(stacksDir).toAbsolutePath();
    }

    // --- views -------------------------------------------------------------------------------

    public record StackContainer(
            String id,
            String name,
            String service,
            String image,
            String state,
            String status,
            List<String> ports) {}

    public record StackSummary(
            String name,
            boolean managed,
            String source,
            String gitRepositoryId,
            String templateName,
            int running,
            int total,
            List<String> services,
            Long lastDeployedAt,
            long updatedAt,
            String lastError,
            String configFiles,
            String workingDir) {}

    public record StackDetail(
            StackSummary summary,
            String composeContent,
            String envContent,
            String path,
            List<StackContainer> containers,
            List<Version> versions,
            String runningJobId) {}

    public record Version(
            int version, String status, long createdAt, String username, String commitSha) {}

    public record SaveRequest(
            String name,
            String composeContent,
            String envContent,
            String templateName,
            boolean deploy) {}

    public List<StackSummary> list(String hostId) {
        List<Container> containers = containers(hostId);
        Map<String, List<Container>> byProject = groupByProject(containers);

        List<StackSummary> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Stack stack : stackRepository.findByDockerHostIdOrderByNameAsc(hostId)) {
            seen.add(stack.getName());
            result.add(summary(stack, byProject.getOrDefault(stack.getName(), List.of())));
        }
        for (var entry : byProject.entrySet()) {
            if (seen.contains(entry.getKey())) continue;
            result.add(external(entry.getKey(), entry.getValue()));
        }
        result.sort(Comparator.comparing(StackSummary::name));
        return result;
    }

    public StackDetail get(String hostId, String name) {
        List<Container> containers =
                groupByProject(containers(hostId)).getOrDefault(name, List.of());
        Optional<Stack> stack = stackRepository.findByDockerHostIdAndName(hostId, name);
        if (stack.isEmpty() && containers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stack not found: " + name);
        }

        List<StackContainer> views =
                containers.stream()
                        .map(
                                c ->
                                        new StackContainer(
                                                c.getId(),
                                                containerName(c),
                                                label(c, LABEL_SERVICE),
                                                c.getImage(),
                                                c.getState(),
                                                c.getStatus(),
                                                ports(c)))
                        .sorted(
                                Comparator.comparing(
                                        StackContainer::service,
                                        Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();

        List<Version> versions =
                composeRepository
                        .findByDockerHostIdAndProjectNameOrderByVersionDesc(hostId, name)
                        .stream()
                        .limit(50)
                        .map(
                                d ->
                                        new Version(
                                                d.getVersion(),
                                                d.getStatus().name(),
                                                d.getCreatedAt(),
                                                d.getUsername(),
                                                d.getCommitSha()))
                        .toList();

        String running =
                jobs.values().stream()
                        .filter(j -> j.key.equals(key(hostId, name)) && j.status.equals("running"))
                        .map(j -> j.id)
                        .findFirst()
                        .orElse(null);

        if (stack.isPresent()) {
            Stack s = stack.get();
            return new StackDetail(
                    summary(s, containers),
                    s.getComposeContent(),
                    s.getEnvContent(),
                    s.getSource() == Stack.Source.GIT ? null : dir(hostId, name).toString(),
                    views,
                    versions,
                    running);
        }

        // Not managed here: show what is running. The compose file is not read from the path in
        // the containers' labels; anyone who can set labels could point it at any file.
        StackSummary summary = external(name, containers);
        return new StackDetail(summary, null, null, null, views, versions, running);
    }

    // --- create / edit -----------------------------------------------------------------------

    public Stack create(String hostId, SaveRequest req, String username, boolean isAdmin) {
        requireHost(hostId);
        String name = req.name() == null ? "" : req.name().trim();
        if (!NAME.matcher(name).matches()) {
            throw bad(
                    "Stack names use lowercase letters, digits, '-' and '_', and start with a"
                            + " letter or digit");
        }
        if (stackRepository.findByDockerHostIdAndName(hostId, name).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A stack named '" + name + "' already exists");
        }
        // Deploying would take over (and with --remove-orphans, remove) another project's
        // containers.
        if (groupByProject(containers(hostId)).containsKey(name)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A compose project named '"
                            + name
                            + "' is already running on this host. Import it instead, or pick"
                            + " another name.");
        }
        check(req.composeContent(), isAdmin);

        Stack stack = new Stack();
        stack.setDockerHostId(hostId);
        stack.setName(name);
        stack.setComposeContent(req.composeContent());
        stack.setEnvContent(blankToNull(req.envContent()));
        stack.setTemplateName(blankToNull(req.templateName()));
        stack.setSource(req.templateName() != null ? Stack.Source.TEMPLATE : Stack.Source.UI);
        stack.setCreatedBy(username);
        stack = stackRepository.save(stack);
        writeFiles(stack);
        return stack;
    }

    /** Takes over a compose project that was started outside Docker Manager. */
    public Stack importExternal(
            String hostId,
            String name,
            String compose,
            String env,
            String projectDir,
            String username,
            boolean isAdmin) {
        requireHost(hostId);
        if (stackRepository.findByDockerHostIdAndName(hostId, name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already managed here");
        }
        if (!NAME.matcher(name).matches()) throw bad("Invalid stack name");
        check(compose, isAdmin);
        Stack stack = new Stack();
        stack.setDockerHostId(hostId);
        stack.setName(name);
        stack.setComposeContent(compose);
        stack.setEnvContent(blankToNull(env));
        stack.setSource(Stack.Source.IMPORTED);
        stack.setProjectDir(blankToNull(projectDir));
        stack.setCreatedBy(username);
        stack = stackRepository.save(stack);
        writeFiles(stack);
        return stack;
    }

    /** Finds the compose file (and .env) of a project started outside Docker Manager. */
    public StackImportDetector.Detected detectExternal(String hostId, String name) {
        DockerHost host = requireHost(hostId);
        List<Container> containers = groupByProject(containers(hostId)).get(name);
        if (containers == null || containers.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No containers belong to a project named " + name);
        }
        var api =
                dockerService.dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()));
        return detector.detect(api, name, containers);
    }

    public record ImportResult(
            String name, boolean imported, String message, List<String> warnings) {}

    /**
     * Imports every project on the host that isn't managed here yet and whose compose file can be
     * found. Nothing is redeployed: the containers keep running as they are.
     */
    public List<ImportResult> importAll(String hostId, String username) {
        List<ImportResult> results = new ArrayList<>();
        for (StackSummary summary : list(hostId)) {
            if (summary.managed()) continue;
            String name = summary.name();
            try {
                if (!NAME.matcher(name).matches()) {
                    results.add(
                            new ImportResult(
                                    name, false, "Its name can't be used for a stack", List.of()));
                    continue;
                }
                var found = detectExternal(hostId, name);
                importExternal(
                        hostId,
                        name,
                        found.composeContent(),
                        found.envContent(),
                        found.projectDir(),
                        username,
                        true);
                String message =
                        found.missingVariables().isEmpty()
                                ? "Imported"
                                : "Imported. Set "
                                        + String.join(", ", found.missingVariables())
                                        + " in its .env before deploying";
                results.add(new ImportResult(name, true, message, found.warnings()));
            } catch (ResponseStatusException e) {
                results.add(new ImportResult(name, false, e.getReason(), List.of()));
            } catch (Exception e) {
                log.warn("Importing {} failed", name, e);
                results.add(new ImportResult(name, false, e.getMessage(), List.of()));
            }
        }
        return results;
    }

    public Stack update(String hostId, String name, SaveRequest req, boolean isAdmin) {
        Stack stack = require(hostId, name);
        if (stack.getSource() == Stack.Source.GIT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This stack is deployed from a git repository. Change the file in the"
                            + " repository and redeploy it.");
        }
        check(req.composeContent(), isAdmin);
        String key = claim(hostId, name);
        try {
            stack.setComposeContent(req.composeContent());
            stack.setEnvContent(blankToNull(req.envContent()));
            stack = stackRepository.save(stack);
            writeFiles(stack);
            return stack;
        } finally {
            busy.remove(key);
        }
    }

    /** Validates a compose file without saving it (used by the editor's "Check" button). */
    public ComposeInspector.Summary validate(String compose, boolean isAdmin) {
        check(compose, isAdmin);
        return inspector.summarize(compose);
    }

    private void check(String compose, boolean isAdmin) {
        ComposeInspector.Summary summary = inspector.summarize(compose);
        if (!isAdmin) inspector.checkAllowedForNonAdmin(compose);
        for (String image : summary.images()) {
            if (!image.contains("${")) imagePolicyService.validateImageForContainerCreation(image);
        }
    }

    // --- actions -----------------------------------------------------------------------------

    public enum Action {
        DEPLOY,
        PULL_AND_DEPLOY,
        START,
        STOP,
        RESTART,
        DOWN,
        REMOVE,
        REMOVE_WITH_VOLUMES
    }

    /** Starts an action in the background; follow it with {@link #job(String)}. */
    public Job start(String hostId, String name, Action action, String username) {
        String hostUrl = requireHost(hostId).getDockerHostUrl();
        Optional<Stack> managed = stackRepository.findByDockerHostIdAndName(hostId, name);

        if (managed.isEmpty()
                && action != Action.STOP
                && action != Action.START
                && action != Action.RESTART
                && action != Action.DOWN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This stack is not managed by Docker Manager. Import it first.");
        }
        if (managed.isPresent()
                && managed.get().getSource() == Stack.Source.GIT
                && (action == Action.DEPLOY || action == Action.PULL_AND_DEPLOY)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This stack is deployed from a git repository. Deploy the repository instead.");
        }

        String key = claim(hostId, name);
        return launch(key, hostId, hostUrl, name, action, username);
    }

    /** One action per stack at a time: claimed here, released when the action ends. */
    private String claim(String hostId, String name) {
        String key = key(hostId, name);
        if (!busy.add(key)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Another action is already running for this stack");
        }
        return key;
    }

    private Job launch(
            String key,
            String hostId,
            String hostUrl,
            String name,
            Action action,
            String username) {
        Job job = new Job(UUID.randomUUID().toString(), key, name, action.name());
        cleanupJobs();
        jobs.put(job.id, job);

        try {
            executor.execute(
                    () -> {
                        try {
                            // Reload: the stack may have been edited since the request.
                            Stack current =
                                    stackRepository
                                            .findByDockerHostIdAndName(hostId, name)
                                            .orElse(null);
                            run(job, hostId, hostUrl, name, current, action, username);
                        } catch (Exception e) {
                            log.error("Stack action {} on {} failed", action, name, e);
                            job.line("Error: " + e.getMessage());
                            job.finish(false);
                        } finally {
                            busy.remove(key);
                        }
                    });
        } catch (RuntimeException e) {
            busy.remove(key);
            throw e;
        }
        return job;
    }

    private void run(
            Job job,
            String hostId,
            String hostUrl,
            String name,
            Stack stack,
            Action action,
            String username) {
        Path dir;
        String composeFile;
        String envFile = null;
        String projectDir = stack != null ? stack.getProjectDir() : null;
        if (stack != null) {
            if (stack.getSource() != Stack.Source.GIT) writeFiles(stack);
            dir = workDir(stack);
            composeFile = composeFileName(stack);
            if (stack.getSource() != Stack.Source.GIT
                    && stack.getEnvContent() != null
                    && !stack.getEnvContent().isBlank()) {
                envFile = ENV_FILE;
            }
        } else {
            // External project: compose can act on a project by name for start/stop/down.
            dir = null;
            composeFile = null;
        }

        Consumer<String> out = job::line;
        ComposeRunner.Result result;
        switch (action) {
            case DEPLOY, PULL_AND_DEPLOY -> {
                if (action == Action.PULL_AND_DEPLOY) {
                    job.line("Pulling images...");
                    var pull =
                            runner.run(
                                    hostUrl,
                                    dir,
                                    composeFile,
                                    envFile,
                                    name,
                                    projectDir,
                                    List.of("pull", "--ignore-buildable"),
                                    out);
                    if (!pull.ok()) {
                        finishWithError(job, stack, pull);
                        return;
                    }
                }
                job.line("Starting services...");
                result =
                        runner.run(
                                hostUrl,
                                dir,
                                composeFile,
                                envFile,
                                name,
                                projectDir,
                                List.of("up", "-d", "--remove-orphans", "--build"),
                                out);
                recordVersion(hostId, stack, result, username, null);
            }
            case START ->
                    result =
                            runner.run(
                                    hostUrl,
                                    dir,
                                    composeFile,
                                    envFile,
                                    name,
                                    projectDir,
                                    List.of("start"),
                                    out);
            case STOP ->
                    result =
                            runner.run(
                                    hostUrl,
                                    dir,
                                    composeFile,
                                    envFile,
                                    name,
                                    projectDir,
                                    List.of("stop"),
                                    out);
            case RESTART ->
                    result =
                            runner.run(
                                    hostUrl,
                                    dir,
                                    composeFile,
                                    envFile,
                                    name,
                                    projectDir,
                                    List.of("restart"),
                                    out);
            case DOWN ->
                    result =
                            runner.run(
                                    hostUrl,
                                    dir,
                                    composeFile,
                                    envFile,
                                    name,
                                    projectDir,
                                    List.of("down", "--remove-orphans"),
                                    out);
            case REMOVE, REMOVE_WITH_VOLUMES -> {
                List<String> args = new ArrayList<>(List.of("down", "--remove-orphans"));
                if (action == Action.REMOVE_WITH_VOLUMES) args.add("--volumes");
                result =
                        runner.run(hostUrl, dir, composeFile, envFile, name, projectDir, args, out);
                if (result.ok() && stack != null) {
                    stackRepository.delete(stack);
                    if (stack.getSource() != Stack.Source.GIT) deleteDir(dir(hostId, name));
                    job.line("Stack removed");
                }
            }
            default -> throw new IllegalStateException();
        }

        if (!result.ok()) {
            finishWithError(job, stack, result);
            return;
        }
        if (stack != null && action != Action.REMOVE && action != Action.REMOVE_WITH_VOLUMES) {
            stack.setLastError(null);
            if (action == Action.DEPLOY || action == Action.PULL_AND_DEPLOY) {
                stack.setLastDeployedAt(System.currentTimeMillis());
            }
            stackRepository.save(stack);
        }
        job.line("Done");
        job.finish(true);
    }

    private void finishWithError(Job job, Stack stack, ComposeRunner.Result result) {
        if (stack != null) {
            stack.setLastError(result.tail(3));
            stackRepository.save(stack);
        }
        job.line("Failed (exit code " + result.exitCode() + ")");
        job.finish(false);
    }

    /** Redeploys an earlier version's compose file and .env. */
    public Job rollback(String hostId, String name, int version, String username, boolean isAdmin) {
        Stack stack = require(hostId, name);
        ComposeDeployment old =
                composeRepository
                        .findByDockerHostIdAndProjectNameAndVersion(hostId, name, version)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Version not found"));
        if (stack.getSource() == Stack.Source.GIT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Roll back git stacks by deploying an earlier commit");
        }
        check(old.getComposeContent(), isAdmin);
        String hostUrl = requireHost(hostId).getDockerHostUrl();
        String key = claim(hostId, name);
        try {
            stack.setComposeContent(old.getComposeContent());
            stack.setEnvContent(old.getEnvFileContent());
            stackRepository.save(stack);
        } catch (RuntimeException e) {
            busy.remove(key);
            throw e;
        }
        return launch(key, hostId, hostUrl, name, Action.DEPLOY, username);
    }

    /**
     * Deploys a compose file from a checked-out git repository, as part of a git deployment. Runs
     * in the caller's thread and streams output to {@code out}.
     */
    public ComposeRunner.Result deployFromGit(
            GitRepository repo,
            Path repoDir,
            String composePath,
            String commitSha,
            Consumer<String> out) {
        String hostId = repo.getDockerHost().getId();
        String name = projectNameFor(repo.getName());
        Stack stack =
                stackRepository
                        .findByDockerHostIdAndName(hostId, name)
                        .orElseGet(
                                () -> {
                                    Stack s = new Stack();
                                    s.setDockerHostId(hostId);
                                    s.setName(name);
                                    s.setSource(Stack.Source.GIT);
                                    return s;
                                });
        if (stack.getSource() != Stack.Source.GIT
                || (stack.getGitRepositoryId() != null
                        && !stack.getGitRepositoryId().equals(repo.getId()))) {
            throw new IllegalStateException(
                    "A different stack named '" + name + "' already exists on this host");
        }
        if (stack.getGitRepositoryId() == null
                && groupByProject(containers(hostId)).containsKey(name)) {
            throw new IllegalStateException(
                    "A compose project named '"
                            + name
                            + "' is already running on this host. Rename the repository or remove"
                            + " that project first.");
        }
        String key = key(hostId, name);
        if (!busy.add(key)) {
            throw new IllegalStateException("Another action is already running for this stack");
        }
        try {
            return deployFromGit(repo, repoDir, composePath, commitSha, out, hostId, name, stack);
        } finally {
            busy.remove(key);
        }
    }

    private ComposeRunner.Result deployFromGit(
            GitRepository repo,
            Path repoDir,
            String composePath,
            String commitSha,
            Consumer<String> out,
            String hostId,
            String name,
            Stack stack) {

        Path file = repoDir.resolve(composePath).normalize();
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read " + composePath + ": " + e.getMessage());
        }
        ComposeInspector.Summary summary = inspector.summarize(content);
        for (String image : summary.images()) {
            if (!image.contains("${")) imagePolicyService.validateImageForContainerCreation(image);
        }

        stack.setGitRepositoryId(repo.getId());
        stack.setComposeContent(content);
        stack = stackRepository.save(stack);

        Path workDir = file.getParent();
        String hostUrl = repo.getDockerHost().getDockerHostUrl();
        out.accept("Compose project: " + name);
        ComposeRunner.Result result =
                runner.run(
                        hostUrl,
                        workDir,
                        file.getFileName().toString(),
                        null,
                        name,
                        List.of("up", "-d", "--remove-orphans", "--build"),
                        out);
        recordVersion(hostId, stack, result, "git", commitSha);
        stack.setLastError(result.ok() ? null : result.tail(3));
        if (result.ok()) stack.setLastDeployedAt(System.currentTimeMillis());
        stackRepository.save(stack);
        return result;
    }

    public static String projectNameFor(String repoName) {
        String n = repoName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        n = n.replaceAll("-+", "-").replaceAll("^[-_]+", "").replaceAll("[-_]+$", "");
        if (n.isEmpty()) n = "repo";
        return n.length() > 63 ? n.substring(0, 63) : n;
    }

    public String logs(String hostId, String name, int tail) {
        String hostUrl = requireHost(hostId).getDockerHostUrl();
        Stack stack = stackRepository.findByDockerHostIdAndName(hostId, name).orElse(null);
        Path dir = stack != null ? workDir(stack) : null;
        String file = stack != null ? composeFileName(stack) : null;
        var result =
                runner.run(
                        hostUrl,
                        dir,
                        file,
                        null,
                        name,
                        stack != null ? stack.getProjectDir() : null,
                        List.of(
                                "logs",
                                "--no-color",
                                "--timestamps",
                                "--tail",
                                String.valueOf(Math.max(1, Math.min(tail, 5000)))),
                        null);
        return String.join("\n", result.output());
    }

    // --- jobs --------------------------------------------------------------------------------

    public static final class Job {
        public final String id;
        final String key;
        public final String stack;
        public final String action;
        public volatile String status = "running";
        public final long startedAt = System.currentTimeMillis();
        public volatile Long finishedAt;
        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        Job(String id, String key, String stack, String action) {
            this.id = id;
            this.key = key;
            this.stack = stack;
            this.action = action;
        }

        void line(String l) {
            if (lines.size() < 20_000) lines.add(l);
        }

        void finish(boolean ok) {
            status = ok ? "success" : "failed";
            finishedAt = System.currentTimeMillis();
        }

        public Map<String, Object> view(int from) {
            List<String> copy;
            synchronized (lines) {
                int start = Math.max(0, Math.min(from, lines.size()));
                copy = new ArrayList<>(lines.subList(start, lines.size()));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("stack", stack);
            m.put("action", action);
            m.put("status", status);
            m.put("startedAt", startedAt);
            m.put("finishedAt", finishedAt);
            m.put("from", from);
            m.put("lines", copy);
            m.put("next", from + copy.size());
            return m;
        }
    }

    public Job job(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        return job;
    }

    private void cleanupJobs() {
        long cutoff = System.currentTimeMillis() - 3_600_000;
        jobs.values().removeIf(j -> j.finishedAt != null && j.finishedAt < cutoff);
    }

    // --- history -----------------------------------------------------------------------------

    private void recordVersion(
            String hostId, Stack stack, ComposeRunner.Result result, String username, String sha) {
        if (stack == null) return;
        try {
            List<ComposeDeployment> history =
                    composeRepository.findByDockerHostIdAndProjectNameOrderByVersionDesc(
                            hostId, stack.getName());
            ComposeDeployment latest = history.isEmpty() ? null : history.get(0);
            boolean same =
                    latest != null
                            && Objects.equals(latest.getComposeContent(), stack.getComposeContent())
                            && Objects.equals(latest.getEnvFileContent(), stack.getEnvContent());

            if (result.ok()) {
                for (ComposeDeployment d : history) {
                    if (d.getStatus() == ComposeDeployment.DeploymentStatus.ACTIVE
                            && (!same || d != latest)) {
                        d.setStatus(ComposeDeployment.DeploymentStatus.STOPPED);
                        composeRepository.save(d);
                    }
                }
            }

            ComposeDeployment record;
            if (same) {
                record = latest;
            } else {
                record = new ComposeDeployment();
                record.setDockerHost(hostRepository.getReferenceById(hostId));
                record.setProjectName(stack.getName());
                record.setComposeContent(stack.getComposeContent());
                record.setEnvFileContent(stack.getEnvContent());
                record.setVersion(latest == null ? 1 : latest.getVersion() + 1);
                record.setPreviousDeployment(latest);
                record.setUsername(username);
            }
            record.setCommitSha(sha);
            record.setStatus(
                    result.ok()
                            ? ComposeDeployment.DeploymentStatus.ACTIVE
                            : ComposeDeployment.DeploymentStatus.FAILED);
            record.setLogs(result.tail(200));
            record.setCompletedAt(System.currentTimeMillis());
            composeRepository.save(record);
        } catch (Exception e) {
            log.warn("Could not record stack version for {}: {}", stack.getName(), e.getMessage());
        }
    }

    // --- files -------------------------------------------------------------------------------

    private Path dir(String hostId, String name) {
        Path base = stacksDir.resolve(hostId).normalize();
        Path dir = base.resolve(name).normalize();
        if (!dir.startsWith(base)) throw bad("Invalid stack name");
        return dir;
    }

    private Path workDir(Stack stack) {
        if (stack.getSource() == Stack.Source.GIT) {
            return null; // git stacks are acted on by project name; their files live in the clone
        }
        return dir(stack.getDockerHostId(), stack.getName());
    }

    private String composeFileName(Stack stack) {
        return stack.getSource() == Stack.Source.GIT ? null : COMPOSE_FILE;
    }

    private void writeFiles(Stack stack) {
        if (stack.getSource() == Stack.Source.GIT) return;
        Path dir = dir(stack.getDockerHostId(), stack.getName());
        try {
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve(COMPOSE_FILE), stack.getComposeContent(), StandardCharsets.UTF_8);
            Path env = dir.resolve(ENV_FILE);
            if (stack.getEnvContent() != null && !stack.getEnvContent().isBlank()) {
                Files.writeString(env, stack.getEnvContent(), StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(env);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not write the stack files to " + dir + ": " + e.getMessage());
        }
    }

    private static void deleteDir(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", dir, e.getMessage());
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private StackSummary summary(Stack s, List<Container> containers) {
        List<String> services;
        try {
            services = inspector.summarize(s.getComposeContent()).services();
        } catch (Exception e) {
            services = List.of();
        }
        int running = (int) containers.stream().filter(c -> "running".equals(c.getState())).count();
        return new StackSummary(
                s.getName(),
                true,
                s.getSource().name(),
                s.getGitRepositoryId(),
                s.getTemplateName(),
                running,
                containers.size(),
                services,
                s.getLastDeployedAt(),
                s.getUpdatedAt(),
                s.getLastError(),
                null,
                null);
    }

    private static StackSummary external(String name, List<Container> containers) {
        int running = (int) containers.stream().filter(c -> "running".equals(c.getState())).count();
        List<String> services =
                containers.stream()
                        .map(c -> label(c, LABEL_SERVICE))
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();
        String configFiles =
                containers.stream()
                        .map(c -> label(c, LABEL_CONFIG_FILES))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
        String workingDir =
                containers.stream()
                        .map(c -> label(c, LABEL_WORKING_DIR))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
        return new StackSummary(
                name,
                false,
                "EXTERNAL",
                null,
                null,
                running,
                containers.size(),
                services,
                null,
                0,
                null,
                configFiles,
                workingDir);
    }

    private List<Container> containers(String hostId) {
        DockerHost host = requireHost(hostId);
        try {
            return dockerService
                    .dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()))
                    .listAllContainers();
        } catch (Exception e) {
            log.warn(
                    "Could not list containers on {}: {}", host.getDockerHostUrl(), e.getMessage());
            return List.of();
        }
    }

    private static Map<String, List<Container>> groupByProject(List<Container> containers) {
        Map<String, List<Container>> map = new TreeMap<>();
        for (Container c : containers) {
            String project = label(c, LABEL_PROJECT);
            if (project != null) map.computeIfAbsent(project, k -> new ArrayList<>()).add(c);
        }
        return map;
    }

    private static String label(Container c, String key) {
        return c.getLabels() == null ? null : c.getLabels().get(key);
    }

    private static String containerName(Container c) {
        return c.getNames() != null && c.getNames().length > 0
                ? c.getNames()[0].replaceFirst("^/", "")
                : c.getId().substring(0, 12);
    }

    private static List<String> ports(Container c) {
        if (c.getPorts() == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (ContainerPort p : c.getPorts()) {
            if (p.getPublicPort() != null) {
                out.add(p.getPublicPort() + ":" + p.getPrivatePort() + "/" + p.getType());
            }
        }
        return new ArrayList<>(out);
    }

    private DockerHost requireHost(String hostId) {
        return hostRepository
                .findById(hostId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Docker host not found"));
    }

    private Stack require(String hostId, String name) {
        return stackRepository
                .findByDockerHostIdAndName(hostId, name)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Stack '" + name + "' is not managed here"));
    }

    private static String key(String hostId, String name) {
        return hostId + "/" + name;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
