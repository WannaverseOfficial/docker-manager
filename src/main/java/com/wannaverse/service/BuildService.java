package com.wannaverse.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.wannaverse.persistence.DeploymentJob;
import com.wannaverse.persistence.DeploymentJob.JobStatus;
import com.wannaverse.persistence.DeploymentJobRepository;
import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.GitRepository.DeploymentType;
import com.wannaverse.persistence.GitRepositoryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BuildService {
    private static final Logger log = LoggerFactory.getLogger(BuildService.class);

    private final GitService gitService;
    private final DockerService dockerService;
    private final DeploymentJobRepository jobRepository;
    private final GitRepositoryRepository gitRepoRepository;
    private final NotificationService notificationService;
    private final Map<String, Set<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();

    public BuildService(
            GitService gitService,
            DockerService dockerService,
            DeploymentJobRepository jobRepository,
            GitRepositoryRepository gitRepoRepository,
            NotificationService notificationService) {
        this.gitService = gitService;
        this.dockerService = dockerService;
        this.jobRepository = jobRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.notificationService = notificationService;
    }

    @Async("deploymentExecutor")
    @Transactional
    public void executeDeployment(DeploymentJob job) {
        GitRepository gitRepo =
                gitRepoRepository
                        .findById(job.getGitRepository().getId())
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                "GitRepository not found: "
                                                        + job.getGitRepository().getId()));
        try {
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(System.currentTimeMillis());
            appendAndBroadcastLog(job, "Starting deployment...");
            jobRepository.save(job);

            notificationService.notifyDeploymentStarted(job);

            appendAndBroadcastLog(job, "Cloning/pulling repository: " + gitRepo.getRepositoryUrl());
            Path repoPath = gitService.cloneOrPullRepository(gitRepo);
            String commitSha = gitService.getCurrentCommitSha(repoPath);
            job.setCommitSha(commitSha);
            appendAndBroadcastLog(job, "Repository ready at commit: " + commitSha);

            if (gitRepo.getDeploymentType() == DeploymentType.DOCKERFILE) {
                buildDockerImage(job, gitRepo, repoPath);
            } else {
                runDockerCompose(job, gitRepo, repoPath);
            }

            job.setStatus(JobStatus.SUCCESS);
            job.setCompletedAt(System.currentTimeMillis());
            appendAndBroadcastLog(job, "Deployment completed successfully!");

            gitRepo.setLastCommitSha(commitSha);
            gitRepo.setLastDeployedAt(System.currentTimeMillis());
            gitRepoRepository.save(gitRepo);

            notificationService.notifyDeploymentCompleted(job);

        } catch (Exception e) {
            log.error("Deployment failed for job {}", job.getId(), e);
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(System.currentTimeMillis());
            appendAndBroadcastLog(job, "ERROR: " + e.getMessage());

            notificationService.notifyDeploymentFailed(job);
        } finally {
            jobRepository.save(job);
            completeEmitters(job.getId());
        }
    }

    private void buildDockerImage(DeploymentJob job, GitRepository gitRepo, Path repoPath)
            throws Exception {
        appendAndBroadcastLog(job, "Building Docker image from Dockerfile...");

        String dockerfilePath = gitRepo.getDockerfilePath();
        if (dockerfilePath == null || dockerfilePath.isEmpty()) {
            dockerfilePath = "Dockerfile";
        }

        File dockerfile = repoPath.resolve(dockerfilePath).toFile();
        if (!dockerfile.exists()) {
            throw new RuntimeException("Dockerfile not found: " + dockerfilePath);
        }

        String imageName = gitRepo.getImageName();
        if (imageName == null || imageName.isEmpty()) {
            imageName = gitRepo.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        }

        DockerClient dockerClient =
                dockerService.createClientCached(gitRepo.getDockerHost().getDockerHostUrl());

        String imageId =
                dockerClient
                        .buildImageCmd()
                        .withDockerfile(dockerfile)
                        .withBaseDirectory(repoPath.toFile())
                        .withTags(
                                Set.of(
                                        imageName + ":latest",
                                        imageName + ":" + job.getCommitSha().substring(0, 7)))
                        .exec(
                                new BuildImageResultCallback() {
                                    @Override
                                    public void onNext(BuildResponseItem item) {
                                        super.onNext(item);
                                        if (item.getStream() != null) {
                                            String logLine = item.getStream().trim();
                                            if (!logLine.isEmpty()) {
                                                appendAndBroadcastLog(job, logLine);
                                            }
                                        }
                                        if (item.getErrorDetail() != null) {
                                            appendAndBroadcastLog(
                                                    job,
                                                    "ERROR: " + item.getErrorDetail().getMessage());
                                        }
                                    }
                                })
                        .awaitImageId();

        appendAndBroadcastLog(job, "Image built successfully: " + imageId);
    }

    private void runDockerCompose(DeploymentJob job, GitRepository gitRepo, Path repoPath)
            throws Exception {
        appendAndBroadcastLog(job, "Running docker-compose...");

        String composePath = gitRepo.getComposePath();
        if (composePath == null || composePath.isEmpty()) {
            composePath = "docker-compose.yml";
        }

        File composeFile = repoPath.resolve(composePath).toFile();
        if (!composeFile.exists()) {
            throw new RuntimeException("docker-compose.yml not found: " + composePath);
        }

        // Get Docker host URL with null checks
        var dockerHostEntity = gitRepo.getDockerHost();
        if (dockerHostEntity == null) {
            throw new RuntimeException("No Docker host configured for this repository");
        }

        String dockerHost = dockerHostEntity.getDockerHostUrl();
        if (dockerHost == null || dockerHost.isEmpty()) {
            throw new RuntimeException("Docker host URL is not configured");
        }

        if (dockerHost.startsWith("unix://") && !dockerHost.startsWith("unix:///")) {
            dockerHost = "unix:///" + dockerHost.substring(7);
            log.warn("Fixed Docker host URL format: {}", dockerHost);
        }

        appendAndBroadcastLog(job, "Using Docker host: " + dockerHost);
        log.info("Running docker-compose with DOCKER_HOST={}", dockerHost);

        ProcessBuilder pb =
                new ProcessBuilder(
                        "docker",
                        "-H",
                        dockerHost,
                        "compose",
                        "-f",
                        composeFile.getAbsolutePath(),
                        "up",
                        "-d",
                        "--build");
        pb.directory(repoPath.toFile());
        pb.environment().put("DOCKER_HOST", dockerHost);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendAndBroadcastLog(job, line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("docker-compose failed with exit code: " + exitCode);
        }

        appendAndBroadcastLog(job, "docker-compose completed successfully");
    }

    private void appendAndBroadcastLog(DeploymentJob job, String logLine) {
        job.appendLog(logLine);
        jobRepository.save(job);
        broadcastLog(job.getId(), logLine);
    }

    public void registerEmitter(String jobId, SseEmitter emitter) {
        jobEmitters.computeIfAbsent(jobId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        Set<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }

    private void broadcastLog(String jobId, String logLine) {
        Set<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            emitters.forEach(
                    emitter -> {
                        try {
                            emitter.send(SseEmitter.event().name("log").data(logLine));
                        } catch (IOException e) {
                            removeEmitter(jobId, emitter);
                        }
                    });
        }
    }

    private void completeEmitters(String jobId) {
        Set<SseEmitter> emitters = jobEmitters.remove(jobId);
        if (emitters != null) {
            emitters.forEach(
                    emitter -> {
                        try {
                            emitter.send(SseEmitter.event().name("complete").data("done"));
                            emitter.complete();
                        } catch (IOException e) {
                        }
                    });
        }
    }
}
