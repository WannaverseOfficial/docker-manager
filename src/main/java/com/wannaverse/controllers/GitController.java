package com.wannaverse.controllers;

import com.wannaverse.dto.*;
import com.wannaverse.persistence.*;
import com.wannaverse.persistence.DeploymentJob.JobStatus;
import com.wannaverse.persistence.DeploymentJob.TriggerType;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/git")
public class GitController {
    private final GitRepositoryRepository gitRepoRepository;
    private final DeploymentJobRepository jobRepository;
    private final DockerHostRepository hostRepository;
    private final EncryptionService encryptionService;
    private final GitService gitService;
    private final BuildService buildService;
    private final PollingService pollingService;
    private final DriftDetectionService driftDetectionService;
    private final String baseUrl;

    public GitController(
            GitRepositoryRepository gitRepoRepository,
            DeploymentJobRepository jobRepository,
            DockerHostRepository hostRepository,
            EncryptionService encryptionService,
            GitService gitService,
            BuildService buildService,
            PollingService pollingService,
            DriftDetectionService driftDetectionService,
            @Value("${app.base-url}") String baseUrl) {
        this.gitRepoRepository = gitRepoRepository;
        this.jobRepository = jobRepository;
        this.hostRepository = hostRepository;
        this.encryptionService = encryptionService;
        this.gitService = gitService;
        this.buildService = buildService;
        this.pollingService = pollingService;
        this.driftDetectionService = driftDetectionService;
        this.baseUrl = baseUrl;
    }

    // ==================== Repository Management ====================

    @GetMapping("/repositories")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "list")
    public ResponseEntity<List<GitRepositoryResponse>> getAllRepositories() {
        List<GitRepositoryResponse> responses =
                gitRepoRepository.findAll().stream()
                        .map(repo -> GitRepositoryResponse.fromEntity(repo, baseUrl))
                        .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/repositories/{id}")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "read")
    public ResponseEntity<GitRepositoryResponse> getRepository(@PathVariable String id) {
        return gitRepoRepository
                .findById(id)
                .map(repo -> ResponseEntity.ok(GitRepositoryResponse.fromEntity(repo, baseUrl)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/hosts/{hostId}/repositories")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<GitRepositoryResponse>> getRepositoriesByHost(
            @PathVariable String hostId) {
        List<GitRepositoryResponse> responses =
                gitRepoRepository.findByDockerHostId(hostId).stream()
                        .map(repo -> GitRepositoryResponse.fromEntity(repo, baseUrl))
                        .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/repositories")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "create")
    public ResponseEntity<GitRepositoryResponse> createRepository(
            @RequestBody CreateGitRepositoryRequest request) {
        DockerHost host =
                hostRepository
                        .findById(request.getDockerHostId())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Docker host not found"));

        GitRepository repo = new GitRepository();
        repo.setName(request.getName());
        repo.setRepositoryUrl(request.getRepositoryUrl());
        repo.setBranch(request.getBranch() != null ? request.getBranch() : "main");
        repo.setAuthType(request.getAuthType());
        repo.setDeploymentType(request.getDeploymentType());
        repo.setDockerfilePath(request.getDockerfilePath());
        repo.setComposePath(request.getComposePath());
        repo.setImageName(request.getImageName());
        repo.setDockerHost(host);
        repo.setPollingEnabled(request.isPollingEnabled());
        repo.setPollingIntervalSeconds(
                request.getPollingIntervalSeconds() > 0
                        ? request.getPollingIntervalSeconds()
                        : 300);
        repo.setWebhookEnabled(request.isWebhookEnabled());
        repo.setWebhookSecret(encryptionService.generateWebhookSecret());

        if (request.getToken() != null && !request.getToken().isEmpty()) {
            repo.setEncryptedToken(encryptionService.encrypt(request.getToken()));
        }
        if (request.getSshKey() != null && !request.getSshKey().isEmpty()) {
            repo.setEncryptedSshKey(encryptionService.encrypt(request.getSshKey()));
        }

        repo = gitRepoRepository.save(repo);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GitRepositoryResponse.fromEntity(repo, baseUrl));
    }

    @PutMapping("/repositories/{id}")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "update")
    public ResponseEntity<GitRepositoryResponse> updateRepository(
            @PathVariable String id, @RequestBody UpdateGitRepositoryRequest request) {
        GitRepository repo =
                gitRepoRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Repository not found"));

        if (request.getName() != null) repo.setName(request.getName());
        if (request.getRepositoryUrl() != null) repo.setRepositoryUrl(request.getRepositoryUrl());
        if (request.getBranch() != null) repo.setBranch(request.getBranch());
        if (request.getAuthType() != null) repo.setAuthType(request.getAuthType());
        if (request.getDeploymentType() != null)
            repo.setDeploymentType(request.getDeploymentType());
        if (request.getDockerfilePath() != null)
            repo.setDockerfilePath(request.getDockerfilePath());
        if (request.getComposePath() != null) repo.setComposePath(request.getComposePath());
        if (request.getImageName() != null) repo.setImageName(request.getImageName());
        repo.setPollingEnabled(request.isPollingEnabled());
        if (request.getPollingIntervalSeconds() > 0)
            repo.setPollingIntervalSeconds(request.getPollingIntervalSeconds());
        repo.setWebhookEnabled(request.isWebhookEnabled());

        if (request.getToken() != null && !request.getToken().isEmpty()) {
            repo.setEncryptedToken(encryptionService.encrypt(request.getToken()));
        }
        if (request.getSshKey() != null && !request.getSshKey().isEmpty()) {
            repo.setEncryptedSshKey(encryptionService.encrypt(request.getSshKey()));
        }

        repo = gitRepoRepository.save(repo);
        return ResponseEntity.ok(GitRepositoryResponse.fromEntity(repo, baseUrl));
    }

    @DeleteMapping("/repositories/{id}")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "delete")
    public ResponseEntity<Void> deleteRepository(@PathVariable String id) {
        gitRepoRepository
                .findById(id)
                .ifPresent(
                        repo -> {
                            try {
                                gitService.deleteRepository(id);
                            } catch (IOException e) {
                                // Ignore
                            }
                            gitRepoRepository.deleteById(id);
                        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/repositories/{id}/regenerate-webhook")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "regenerate_webhook")
    public ResponseEntity<GitRepositoryResponse> regenerateWebhook(@PathVariable String id) {
        GitRepository repo =
                gitRepoRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Repository not found"));

        repo.setWebhookSecret(encryptionService.generateWebhookSecret());
        repo = gitRepoRepository.save(repo);
        return ResponseEntity.ok(GitRepositoryResponse.fromEntity(repo, baseUrl));
    }

    // ==================== Deployment Operations ====================

    @PostMapping("/repositories/{id}/deploy")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "deploy")
    public ResponseEntity<DeploymentJobResponse> triggerDeployment(
            @PathVariable String id,
            @RequestBody(required = false) TriggerDeploymentRequest request) {
        GitRepository repo =
                gitRepoRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Repository not found"));

        DeploymentJob job = new DeploymentJob();
        job.setGitRepository(repo);
        job.setStatus(JobStatus.PENDING);
        job.setTriggerType(TriggerType.MANUAL);
        if (request != null && request.getCommitSha() != null) {
            job.setCommitSha(request.getCommitSha());
        }
        job = jobRepository.save(job);

        buildService.executeDeployment(job);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(DeploymentJobResponse.fromEntity(job));
    }

    @PostMapping("/repositories/{id}/poll")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "poll")
    public ResponseEntity<Map<String, Object>> manualPoll(@PathVariable String id) {
        GitRepository repo =
                gitRepoRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Repository not found"));

        boolean triggered = pollingService.checkAndDeploy(repo, TriggerType.POLLING);
        return ResponseEntity.ok(Map.of("triggered", triggered));
    }

    @GetMapping("/jobs")
    @RequirePermission(resource = Resource.DEPLOYMENTS, action = "list")
    public ResponseEntity<List<DeploymentJobResponse>> getAllJobs() {
        List<DeploymentJobResponse> responses =
                jobRepository.findAllByOrderByCreatedAtDesc().stream()
                        .map(DeploymentJobResponse::fromEntity)
                        .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/jobs/{jobId}")
    @RequirePermission(resource = Resource.DEPLOYMENTS, action = "read")
    public ResponseEntity<DeploymentJobResponse> getJob(@PathVariable String jobId) {
        return jobRepository
                .findById(jobId)
                .map(job -> ResponseEntity.ok(DeploymentJobResponse.fromEntity(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs/{jobId}/logs")
    @RequirePermission(resource = Resource.DEPLOYMENTS, action = "logs")
    public ResponseEntity<Map<String, String>> getJobLogs(@PathVariable String jobId) {
        return jobRepository
                .findById(jobId)
                .map(
                        job ->
                                ResponseEntity.ok(
                                        Map.of("logs", job.getLogs() != null ? job.getLogs() : "")))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/jobs/{jobId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission(resource = Resource.DEPLOYMENTS, action = "stream_logs")
    public SseEmitter streamJobLogs(@PathVariable String jobId) {
        DeploymentJob job =
                jobRepository
                        .findById(jobId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Job not found"));

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        if (job.getLogs() != null && !job.getLogs().isEmpty()) {
            try {
                for (String line : job.getLogs().split("\n")) {
                    emitter.send(SseEmitter.event().name("log").data(line));
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
                return emitter;
            }
        }

        if (job.getStatus() == JobStatus.SUCCESS
                || job.getStatus() == JobStatus.FAILED
                || job.getStatus() == JobStatus.CANCELLED) {
            try {
                emitter.send(SseEmitter.event().name("complete").data("done"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        } else {
            buildService.registerEmitter(jobId, emitter);
        }

        return emitter;
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @RequirePermission(resource = Resource.DEPLOYMENTS, action = "cancel")
    public ResponseEntity<DeploymentJobResponse> cancelJob(@PathVariable String jobId) {
        DeploymentJob job =
                jobRepository
                        .findById(jobId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Job not found"));

        if (job.getStatus() == JobStatus.PENDING) {
            job.setStatus(JobStatus.CANCELLED);
            job.setCompletedAt(System.currentTimeMillis());
            job = jobRepository.save(job);
        }

        return ResponseEntity.ok(DeploymentJobResponse.fromEntity(job));
    }

    // ==================== Drift Detection ====================

    @GetMapping("/drift")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "list")
    public ResponseEntity<List<DriftDetectionService.GitDriftInfo>> getAllDriftStatus() {
        return ResponseEntity.ok(driftDetectionService.getAllGitDriftStatus());
    }

    @GetMapping("/repositories/{id}/drift")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "read")
    public ResponseEntity<DriftDetectionService.GitDriftInfo> getRepositoryDrift(
            @PathVariable String id) {
        GitRepository repo =
                gitRepoRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Repository not found"));

        return ResponseEntity.ok(
                new DriftDetectionService.GitDriftInfo(
                        repo.getId(),
                        repo.getName(),
                        repo.getDriftStatus(),
                        repo.getLastCommitSha(),
                        repo.getLatestRemoteCommitSha(),
                        repo.getDriftDetectedAt(),
                        repo.getLastDriftCheckAt()));
    }

    @PostMapping("/repositories/{id}/check-drift")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "read")
    public ResponseEntity<GitRepositoryResponse> checkDrift(@PathVariable String id) {
        GitRepository repo = driftDetectionService.checkGitDriftById(id);
        return ResponseEntity.ok(GitRepositoryResponse.fromEntity(repo, baseUrl));
    }

    // ==================== Webhook ====================

    @PostMapping("/webhook/{secret}")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @PathVariable String secret,
            @RequestBody GitHubWebhookPayload payload,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event) {

        if (!"push".equals(event)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "not a push event"));
        }

        GitRepository repo =
                gitRepoRepository
                        .findByWebhookSecret(secret)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Invalid webhook secret"));

        if (!repo.isWebhookEnabled()) {
            return ResponseEntity.ok(
                    Map.of("status", "ignored", "reason", "webhook disabled for this repository"));
        }

        String branch = payload.getBranch();
        if (!repo.getBranch().equals(branch)) {
            return ResponseEntity.ok(
                    Map.of(
                            "status",
                            "ignored",
                            "reason",
                            "branch mismatch: expected " + repo.getBranch() + ", got " + branch));
        }

        DeploymentJob job = new DeploymentJob();
        job.setGitRepository(repo);
        job.setStatus(JobStatus.PENDING);
        job.setTriggerType(TriggerType.WEBHOOK);
        job.setCommitSha(payload.getAfter());
        job = jobRepository.save(job);

        buildService.executeDeployment(job);

        return ResponseEntity.ok(Map.of("status", "triggered", "jobId", job.getId()));
    }
}
