package com.wannaverse.service;

import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.Container;
import com.wannaverse.dto.ContainerSpec;
import com.wannaverse.dto.GitRepositoryResponse;
import com.wannaverse.persistence.DeploymentJob;
import com.wannaverse.persistence.DeploymentJob.JobStatus;
import com.wannaverse.persistence.DeploymentJobRepository;
import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.GitRepository.DeploymentType;
import com.wannaverse.persistence.GitRepositoryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs git deployments.
 *
 * <p>What changed:
 *
 * <ul>
 *   <li>The whole deployment used to run inside one database transaction. With SQLite's single
 *       connection that froze every other request for as long as a build took. Now each status
 *       change is its own short write, and log lines are buffered and saved every couple of seconds
 *       instead of rewriting the full log for every line.
 *   <li>Dockerfile repositories only built an image and never ran it. Now the image is run as a
 *       container (or the existing container is recreated with the new image), using the container
 *       settings saved with the repository.
 *   <li>Compose repositories needed a `docker` binary that was not installed, and were not
 *       manageable afterwards. They now deploy as a stack you can see and control on the Stacks
 *       page.
 *   <li>Only one deployment per repository runs at a time; webhook bursts queue up.
 *   <li>Running deployments can be cancelled, and deployments interrupted by a restart are marked
 *       as failed instead of staying "running" forever.
 * </ul>
 */
@Service
public class BuildService {
    private static final Logger log = LoggerFactory.getLogger(BuildService.class);
    private static final long FLUSH_MS = 2_000;
    private static final int MAX_LOG_CHARS = 2_000_000;

    private final GitService gitService;
    private final DockerService dockerService;
    private final DeploymentJobRepository jobRepository;
    private final GitRepositoryRepository gitRepoRepository;
    private final NotificationService notificationService;
    private final StackService stackService;
    private final ContainerSpecService specService;
    private final SelfContainer selfContainer;

    private final Map<String, Set<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public BuildService(
            GitService gitService,
            DockerService dockerService,
            DeploymentJobRepository jobRepository,
            GitRepositoryRepository gitRepoRepository,
            NotificationService notificationService,
            StackService stackService,
            ContainerSpecService specService,
            SelfContainer selfContainer) {
        this.selfContainer = selfContainer;
        this.gitService = gitService;
        this.dockerService = dockerService;
        this.jobRepository = jobRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.notificationService = notificationService;
        this.stackService = stackService;
        this.specService = specService;
    }

    /** Deployments that were running when the server stopped will never finish. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedJobs() {
        for (DeploymentJob job :
                jobRepository.findByStatusIn(List.of(JobStatus.RUNNING, JobStatus.PENDING))) {
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Interrupted because Docker Manager restarted");
            job.setCompletedAt(System.currentTimeMillis());
            jobRepository.save(job);
        }
    }

    public void cancel(String jobId) {
        cancelled.add(jobId);
    }

    @Async("deploymentExecutor")
    public void executeDeployment(DeploymentJob submitted) {
        String jobId = submitted.getId();
        String repoId = submitted.getGitRepository().getId();
        ReentrantLock lock = repoLocks.computeIfAbsent(repoId, k -> new ReentrantLock());
        lock.lock();
        JobLog jobLog = null;
        DeploymentJob job = null;
        try {
            job = jobRepository.findById(jobId).orElse(null);
            if (job == null || job.getStatus() == JobStatus.CANCELLED || cancelled.remove(jobId)) {
                if (job != null && job.getStatus() != JobStatus.CANCELLED) {
                    job.setStatus(JobStatus.CANCELLED);
                    job.setCompletedAt(System.currentTimeMillis());
                    jobRepository.save(job);
                }
                return;
            }
            GitRepository repo =
                    gitRepoRepository
                            .findWithHostById(repoId)
                            .orElseThrow(() -> new IllegalStateException("Repository was deleted"));
            // Notifications read the repository's name; give them the loaded entity rather than a
            // lazy proxy that cannot be initialised outside a request.
            job.setGitRepository(repo);

            jobLog = new JobLog(job);
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(System.currentTimeMillis());
            jobLog.line("Starting deployment of " + repo.getName() + " (" + repo.getBranch() + ")");
            jobLog.flush();
            notificationService.notifyDeploymentStarted(job);

            jobLog.line("Fetching " + repo.getRepositoryUrl());
            Path repoPath = gitService.checkout(repo, blankToNull(job.getCommitSha()));
            String commitSha = gitService.getCurrentCommitSha(repoPath);
            job.setCommitSha(commitSha);
            String message = gitService.getCommitMessage(repoPath);
            jobLog.line(
                    "At commit "
                            + (commitSha == null ? "?" : commitSha.substring(0, 7))
                            + (message != null ? " - " + message : ""));
            checkCancelled(jobId);

            if (repo.getDeploymentType() == DeploymentType.DOCKERFILE) {
                String image = buildImage(jobLog, repo, repoPath, commitSha, jobId);
                checkCancelled(jobId);
                if (!Boolean.FALSE.equals(repo.getRunContainer())) {
                    runContainer(jobLog, repo, image);
                } else {
                    jobLog.line("Running a container is turned off for this repository");
                }
            } else {
                String composePath =
                        repo.getComposePath() == null || repo.getComposePath().isBlank()
                                ? findComposeFile(repoPath)
                                : repo.getComposePath();
                GitService.resolveInside(repoPath, composePath, "docker-compose.yml");
                var result =
                        stackService.deployFromGit(
                                repo, repoPath, composePath, commitSha, jobLog::line);
                if (!result.ok()) {
                    throw new IllegalStateException(
                            "docker compose failed (exit code " + result.exitCode() + ")");
                }
            }

            job.setStatus(JobStatus.SUCCESS);
            job.setCompletedAt(System.currentTimeMillis());
            jobLog.line("Deployment finished");

            repo.setLastCommitSha(commitSha);
            repo.setLatestRemoteCommitSha(commitSha);
            repo.setDriftStatus(GitRepository.DriftStatus.SYNCED);
            repo.setLastDeployedAt(System.currentTimeMillis());
            gitRepoRepository.save(repo);

            notificationService.notifyDeploymentCompleted(job);
        } catch (Cancelled c) {
            job.setStatus(JobStatus.CANCELLED);
            job.setCompletedAt(System.currentTimeMillis());
            if (jobLog != null) jobLog.line("Cancelled");
        } catch (Exception e) {
            log.error("Deployment {} failed: {}", jobId, e.getMessage(), e);
            if (job != null) {
                job.setStatus(JobStatus.FAILED);
                job.setErrorMessage(truncate(e.getMessage(), 1000));
                job.setCompletedAt(System.currentTimeMillis());
                if (jobLog != null) jobLog.line("Error: " + e.getMessage());
                try {
                    notificationService.notifyDeploymentFailed(job);
                } catch (Exception ignored) {
                }
            }
        } finally {
            cancelled.remove(jobId);
            if (jobLog != null) jobLog.flush();
            else if (job != null) jobRepository.save(job);
            completeEmitters(jobId);
            lock.unlock();
        }
    }

    private static String findComposeFile(Path repoPath) {
        for (String name :
                List.of(
                        "compose.yaml",
                        "compose.yml",
                        "docker-compose.yml",
                        "docker-compose.yaml")) {
            if (Files.isRegularFile(repoPath.resolve(name))) return name;
        }
        return "docker-compose.yml";
    }

    private String buildImage(
            JobLog jobLog, GitRepository repo, Path repoPath, String commitSha, String jobId) {
        Path dockerfile =
                GitService.resolveInside(repoPath, repo.getDockerfilePath(), "Dockerfile");
        if (!Files.isRegularFile(dockerfile)) {
            throw new IllegalStateException(
                    "Dockerfile not found: " + repoPath.relativize(dockerfile));
        }
        String image = imageName(repo);
        String shortSha = commitSha != null ? commitSha.substring(0, 7) : "latest";
        jobLog.line("Building " + image + ":" + shortSha);

        var client = dockerService.createClientCached(repo.getDockerHost().getDockerHostUrl());
        BuildImageResultCallback callback =
                new BuildImageResultCallback() {
                    @Override
                    public void onNext(BuildResponseItem item) {
                        super.onNext(item);
                        if (item.getStream() != null) {
                            String text = item.getStream().stripTrailing();
                            if (!text.isEmpty()) text.lines().forEach(jobLog::line);
                        }
                        if (item.getErrorDetail() != null) {
                            jobLog.line("ERROR: " + item.getErrorDetail().getMessage());
                        }
                        if (cancelled.contains(jobId)) {
                            try {
                                close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                };
        // The build context is the repository root, so Dockerfiles in subfolders can still COPY
        // files from anywhere in the repository (as `docker build -f sub/Dockerfile .` does).
        String imageId =
                client.buildImageCmd()
                        .withDockerfile(dockerfile.toFile())
                        .withBaseDirectory(repoPath.toFile())
                        .withPull(true)
                        .withTags(Set.of(image + ":latest", image + ":" + shortSha))
                        .exec(callback)
                        .awaitImageId();
        checkCancelled(jobId);
        jobLog.line("Built image " + imageId);
        return image + ":" + shortSha;
    }

    private void runContainer(JobLog jobLog, GitRepository repo, String image) {
        DockerAPI api =
                dockerService.dockerAPI(
                        dockerService.createClientCached(repo.getDockerHost().getDockerHostUrl()));
        ContainerSpec spec = GitRepositoryResponse.readSpec(repo.getContainerSpecJson());
        if (spec == null) spec = new ContainerSpec();
        if (spec.getName() == null || spec.getName().isBlank()) {
            spec.setName(StackService.projectNameFor(repo.getName()));
        }
        spec.setImage(image);
        spec.setStart(true);
        Map<String, String> labels =
                new LinkedHashMap<>(spec.getLabels() == null ? Map.of() : spec.getLabels());
        labels.put("com.wannaverse.git-repository", repo.getId());
        spec.setLabels(labels);

        String name = spec.getName();
        Optional<Container> existing =
                api.listAllContainers().stream()
                        .filter(
                                c ->
                                        c.getNames() != null
                                                && Arrays.asList(c.getNames()).contains("/" + name))
                        .findFirst();
        if (existing.isPresent()) {
            Container old = existing.get();
            selfContainer.protect(old.getId(), "replace");
            String owner =
                    old.getLabels() == null
                            ? null
                            : old.getLabels().get("com.wannaverse.git-repository");
            // Containers deployed by 1.x have no label, but run this repository's image.
            String oldImage = old.getImage() == null ? "" : old.getImage();
            boolean ourImage = owner == null && oldImage.startsWith(imageName(repo) + ":");
            if (!repo.getId().equals(owner) && !ourImage) {
                throw new IllegalStateException(
                        "A container named "
                                + name
                                + " already exists and wasn't created by this repository. Rename"
                                + " it, or set another container name for the repository.");
            }
            // Keep every volume the running container has, including anonymous ones the image
            // declares (a database's data folder), even when the saved settings don't list them.
            List<String> volumes =
                    new ArrayList<>(spec.getVolumes() == null ? List.of() : spec.getVolumes());
            Set<String> targets = new HashSet<>();
            for (String v : volumes) {
                String[] parts = v.split(":");
                if (parts.length >= 2) targets.add(parts[1]);
            }
            var inspect = api.inspectContainer(old.getId());
            if (inspect.getMounts() != null) {
                for (var m : inspect.getMounts()) {
                    String dest = m.getDestination() == null ? null : m.getDestination().getPath();
                    if (m.getName() != null && dest != null && !targets.contains(dest)) {
                        volumes.add(m.getName() + ":" + dest);
                        targets.add(dest);
                    }
                }
            }
            spec.setVolumes(volumes);
            jobLog.line("Replacing container " + name + " with the new image");
            String id = specService.recreate(api, old.getId(), spec, false, true);
            jobLog.line("Container " + name + " is running (" + id.substring(0, 12) + ")");
        } else {
            jobLog.line("Starting container " + name);
            String id = specService.create(api, spec);
            jobLog.line("Container " + name + " is running (" + id.substring(0, 12) + ")");
        }
    }

    private static String imageName(GitRepository repo) {
        String name = repo.getImageName();
        if (name == null || name.isBlank()) {
            name = StackService.projectNameFor(repo.getName());
        }
        name = name.trim().toLowerCase(Locale.ROOT);
        int colon = name.lastIndexOf(':');
        if (colon > name.lastIndexOf('/')) name = name.substring(0, colon);
        return name;
    }

    private void checkCancelled(String jobId) {
        if (cancelled.contains(jobId)) throw new Cancelled();
    }

    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("Cancelled", null, false, false);
        }
    }

    // --- logs --------------------------------------------------------------------------------

    /** Collects log lines, streams them live, and saves them in batches. */
    private final class JobLog {
        private final DeploymentJob job;
        private final StringBuilder buffer;
        private long lastFlush = System.currentTimeMillis();

        JobLog(DeploymentJob job) {
            this.job = job;
            this.buffer = new StringBuilder(job.getLogs() == null ? "" : job.getLogs());
        }

        synchronized void line(String line) {
            if (buffer.length() < MAX_LOG_CHARS) {
                buffer.append(line).append('\n');
            }
            broadcastLog(job.getId(), line);
            if (System.currentTimeMillis() - lastFlush > FLUSH_MS) flush();
        }

        synchronized void flush() {
            job.setLogs(buffer.toString());
            try {
                jobRepository.save(job);
            } catch (Exception e) {
                log.warn("Could not save deployment log: {}", e.getMessage());
            }
            lastFlush = System.currentTimeMillis();
        }
    }

    public void registerEmitter(String jobId, SseEmitter emitter) {
        jobEmitters.computeIfAbsent(jobId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));
        // The job may have finished between the caller's status check and registration.
        jobRepository
                .findById(jobId)
                .filter(
                        j ->
                                j.getStatus() == JobStatus.SUCCESS
                                        || j.getStatus() == JobStatus.FAILED
                                        || j.getStatus() == JobStatus.CANCELLED)
                .ifPresent(j -> completeEmitters(jobId));
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        Set<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) emitters.remove(emitter);
    }

    private void broadcastLog(String jobId, String logLine) {
        Set<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("log").data(logLine));
            } catch (Exception e) {
                removeEmitter(jobId, emitter);
            }
        }
    }

    private void completeEmitters(String jobId) {
        Set<SseEmitter> emitters = jobEmitters.remove(jobId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("complete").data("done"));
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
