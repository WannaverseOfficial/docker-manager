package com.wannaverse.controllers;

import com.wannaverse.dto.*;
import com.wannaverse.iac.ConfigLocked;
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
    private final ContainerSpecService specService;
    private final RolloutRepository rolloutRepository;
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
            ContainerSpecService specService,
            RolloutRepository rolloutRepository,
            @Value("${app.base-url}") String baseUrl) {
        this.rolloutRepository = rolloutRepository;
        this.specService = specService;
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

    @GetMapping("/repositories")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "list")
    public ResponseEntity<List<GitRepositoryResponse>> getAllRepositories() {
        List<GitRepositoryResponse> responses =
                gitRepoRepository.findAll().stream().map(repo -> view(repo)).toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/repositories/{id}")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "read")
    public ResponseEntity<GitRepositoryResponse> getRepository(@PathVariable String id) {
        return gitRepoRepository
                .findById(id)
                .map(repo -> ResponseEntity.ok(view(repo)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/hosts/{hostId}/repositories")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<GitRepositoryResponse>> getRepositoriesByHost(
            @PathVariable String hostId) {
        List<GitRepositoryResponse> responses =
                gitRepoRepository.findByDockerHostId(hostId).stream()
                        .map(repo -> view(repo))
                        .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/repositories")
    @com.wannaverse.security.AdminOnly
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

        if (request.getName() == null || request.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a name");
        }
        GitService.validateUrl(request.getRepositoryUrl());
        validateBranch(request.getBranch());
        validatePaths(request.getDockerfilePath(), request.getComposePath());
        if (request.getDeploymentType() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Choose how to deploy: Dockerfile or Docker Compose");
        }
        GitRepository.AuthType authType =
                request.getAuthType() == null ? GitRepository.AuthType.NONE : request.getAuthType();
        if (authType == GitRepository.AuthType.PAT && isBlank(request.getToken())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the access token");
        }
        if (authType == GitRepository.AuthType.SSH_KEY && isBlank(request.getSshKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste the private SSH key");
        }
        if (request.getContainerSpec() != null) {
            validateSpec(request.getContainerSpec());
        }

        GitRepository repo = new GitRepository();
        repo.setName(request.getName().trim());
        repo.setRepositoryUrl(request.getRepositoryUrl().trim());
        repo.setBranch(isBlank(request.getBranch()) ? "main" : request.getBranch().trim());
        repo.setAuthType(authType);
        repo.setTokenUsername(blankToNull(request.getTokenUsername()));
        repo.setDeploymentType(request.getDeploymentType());
        repo.setDockerfilePath(blankToNull(request.getDockerfilePath()));
        repo.setComposePath(blankToNull(request.getComposePath()));
        repo.setImageName(blankToNull(request.getImageName()));
        repo.setRunContainer(request.getRunContainer() == null || request.getRunContainer());
        repo.setContainerSpecJson(GitRepositoryResponse.writeSpec(request.getContainerSpec()));
        repo.setDockerHost(host);
        repo.setRolloutId(rolloutFor(request.getRolloutId(), host.getId()));
        repo.setPollingEnabled(request.isPollingEnabled());
        repo.setPollingIntervalSeconds(
                request.getPollingIntervalSeconds() >= 60
                        ? request.getPollingIntervalSeconds()
                        : 300);
        repo.setWebhookEnabled(request.isWebhookEnabled());
        repo.setWebhookSecret(encryptionService.generateWebhookSecret());

        if (authType == GitRepository.AuthType.PAT) {
            repo.setEncryptedToken(encryptionService.encrypt(request.getToken().trim()));
        }
        if (authType == GitRepository.AuthType.SSH_KEY) {
            repo.setEncryptedSshKey(encryptionService.encrypt(request.getSshKey()));
        }

        repo = gitRepoRepository.save(repo);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(repo));
    }

    @PutMapping("/repositories/{id}")
    @com.wannaverse.security.AdminOnly
    @ConfigLocked(section = "gitRepositories", id = "id")
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

        if (!isBlank(request.getName())) repo.setName(request.getName().trim());
        if (request.getRepositoryUrl() != null) {
            GitService.validateUrl(request.getRepositoryUrl());
            repo.setRepositoryUrl(request.getRepositoryUrl().trim());
        }
        if (!isBlank(request.getBranch())) {
            validateBranch(request.getBranch());
            repo.setBranch(request.getBranch().trim());
        }
        validatePaths(request.getDockerfilePath(), request.getComposePath());
        if (request.getAuthType() != null) repo.setAuthType(request.getAuthType());
        if (request.getTokenUsername() != null) {
            repo.setTokenUsername(blankToNull(request.getTokenUsername()));
        }
        if (request.getDeploymentType() != null)
            repo.setDeploymentType(request.getDeploymentType());
        if (request.getDockerfilePath() != null)
            repo.setDockerfilePath(blankToNull(request.getDockerfilePath()));
        if (request.getComposePath() != null)
            repo.setComposePath(blankToNull(request.getComposePath()));
        if (request.getImageName() != null) repo.setImageName(blankToNull(request.getImageName()));
        if (request.getRunContainer() != null) repo.setRunContainer(request.getRunContainer());
        if (request.getRolloutId() != null) {
            repo.setRolloutId(rolloutFor(request.getRolloutId(), repo.getDockerHost().getId()));
        }
        if (request.getContainerSpec() != null) {
            validateSpec(request.getContainerSpec());
            repo.setContainerSpecJson(GitRepositoryResponse.writeSpec(request.getContainerSpec()));
        }
        if (request.getPollingEnabled() != null)
            repo.setPollingEnabled(request.getPollingEnabled());
        if (request.getPollingIntervalSeconds() != null
                && request.getPollingIntervalSeconds() >= 60)
            repo.setPollingIntervalSeconds(request.getPollingIntervalSeconds());
        if (request.getWebhookEnabled() != null)
            repo.setWebhookEnabled(request.getWebhookEnabled());

        if (!isBlank(request.getToken())) {
            repo.setEncryptedToken(encryptionService.encrypt(request.getToken().trim()));
        }
        if (!isBlank(request.getSshKey())) {
            repo.setEncryptedSshKey(encryptionService.encrypt(request.getSshKey()));
        }
        if (repo.getAuthType() == GitRepository.AuthType.PAT && repo.getEncryptedToken() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the access token");
        }
        if (repo.getAuthType() == GitRepository.AuthType.SSH_KEY
                && repo.getEncryptedSshKey() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste the private SSH key");
        }

        repo = gitRepoRepository.save(repo);
        return ResponseEntity.ok(view(repo));
    }

    @DeleteMapping("/repositories/{id}")
    @com.wannaverse.security.AdminOnly
    @ConfigLocked(section = "gitRepositories", id = "id")
    @RequirePermission(resource = Resource.GIT_REPOS, action = "delete")
    public ResponseEntity<Void> deleteRepository(@PathVariable String id) {
        gitRepoRepository
                .findById(id)
                .ifPresent(
                        repo -> {
                            try {
                                gitService.deleteRepository(id);
                            } catch (IOException e) {
                            }
                            gitRepoRepository.deleteById(id);
                        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/repositories/{id}/regenerate-webhook")
    @ConfigLocked(section = "gitRepositories", id = "id")
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
        return ResponseEntity.ok(view(repo));
    }

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
        if (request != null
                && request.getCommitSha() != null
                && !request.getCommitSha().matches("^[0-9a-fA-F]{7,64}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid commit SHA");
        }
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
                jobRepository.findTop200ByOrderByCreatedAtDesc().stream()
                        .map(DeploymentJobResponse::fromEntity)
                        .peek(j -> j.setLogs(null))
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
        } else if (job.getStatus() == JobStatus.RUNNING) {
            // Stops at the next step boundary (or immediately while an image is building).
            buildService.cancel(jobId);
        }

        return ResponseEntity.ok(DeploymentJobResponse.fromEntity(job));
    }

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
        return ResponseEntity.ok(view(repo));
    }

    /**
     * Push webhook for GitHub, GitLab, Gitea/Forgejo and other services that send a JSON body with
     * "ref" and "after". The secret in the URL identifies the repository. When the sender also
     * signs the request (GitHub's X-Hub-Signature-256, Gitea's X-Gitea-Signature or GitLab's
     * X-Gitlab-Token), the signature must match the same secret.
     */
    @PostMapping("/webhook/{secret}")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @PathVariable String secret,
            @RequestBody(required = false) String body,
            @RequestHeader Map<String, String> headers) {
        GitRepository repo =
                gitRepoRepository
                        .findByWebhookSecret(secret)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Unknown webhook"));

        String payloadText = body == null ? "" : body;
        if (!signatureValid(repo.getWebhookSecret(), payloadText, headers)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }

        String event =
                first(headers, "x-github-event", "x-gitea-event", "x-gogs-event", "x-gitlab-event");
        if (event != null && (event.equals("ping") || event.equals("System Hook"))) {
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
        if (event != null && !event.equals("push") && !event.equals("Push Hook")) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "not a push event"));
        }

        if (!repo.isWebhookEnabled()) {
            return ResponseEntity.ok(
                    Map.of("status", "ignored", "reason", "webhook disabled for this repository"));
        }

        GitHubWebhookPayload payload;
        try {
            payload = WEBHOOK_JSON.readValue(payloadText, GitHubWebhookPayload.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON payload");
        }

        String branch = payload.getBranch();
        if (branch == null || !repo.getBranch().equals(branch)) {
            return ResponseEntity.ok(
                    Map.of(
                            "status",
                            "ignored",
                            "reason",
                            "branch mismatch: expected " + repo.getBranch() + ", got " + branch));
        }
        String after = payload.getAfter();
        if (after != null && after.matches("^0+$")) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "branch deleted"));
        }

        DeploymentJob job = new DeploymentJob();
        job.setGitRepository(repo);
        job.setStatus(JobStatus.PENDING);
        job.setTriggerType(TriggerType.WEBHOOK);
        // Only trust the commit from a signed body. Anyone who only knows the URL could otherwise
        // pin an old commit; without a signature, deploy the branch's current head instead.
        boolean signed = bodyIsSigned(headers);
        job.setCommitSha(
                signed && after != null && after.matches("^[0-9a-f]{7,64}$") ? after : null);
        job = jobRepository.save(job);

        buildService.executeDeployment(job);

        return ResponseEntity.ok(Map.of("status", "triggered", "jobId", job.getId()));
    }

    /** The webhook URL contains its secret, so only admins (who manage repositories) see it. */
    private GitRepositoryResponse view(GitRepository repo) {
        GitRepositoryResponse response = GitRepositoryResponse.fromEntity(repo, baseUrl);
        var ctx = com.wannaverse.security.SecurityContextHolder.getContext();
        if (ctx == null || !ctx.isAdmin()) response.setWebhookUrl(null);
        return response;
    }

    /** True when the request carries an HMAC of the body (GitHub, Gitea, Gogs). */
    static boolean bodyIsSigned(Map<String, String> headers) {
        return first(headers, "x-hub-signature-256", "x-gitea-signature", "x-gogs-signature")
                != null;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper WEBHOOK_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    static boolean signatureValid(String secret, String body, Map<String, String> headers) {
        String github = first(headers, "x-hub-signature-256");
        String gitea = first(headers, "x-gitea-signature", "x-gogs-signature");
        String gitlab = first(headers, "x-gitlab-token");
        if (github != null) {
            return constantTimeEquals(github, "sha256=" + hmacHex(secret, body));
        }
        if (gitea != null) {
            return constantTimeEquals(gitea, hmacHex(secret, body));
        }
        if (gitlab != null) {
            return constantTimeEquals(gitlab, secret);
        }
        return true; // Unsigned: the secret URL is the credential.
    }

    private static String hmacHex(String secret, String body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(
                    new javax.crypto.spec.SecretKeySpec(
                            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "HmacSHA256"));
            return java.util.HexFormat.of()
                    .formatHex(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.trim()
                        .toLowerCase(java.util.Locale.ROOT)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.toLowerCase(java.util.Locale.ROOT)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String first(Map<String, String> headers, String... names) {
        for (String name : names) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern BRANCH =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._/-]{1,200}$");

    private static void validateBranch(String branch) {
        if (branch == null || branch.isBlank()) return;
        String b = branch.trim();
        if (!BRANCH.matcher(b).matches() || b.contains("..") || b.startsWith("-")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid branch name");
        }
    }

    private static void validatePaths(String... paths) {
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            String t = p.trim().replace('\\', '/');
            if (t.startsWith("/") || t.equals("..") || t.startsWith("../") || t.contains("/../")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "File paths must be relative to the repository root");
            }
        }
    }

    private void validateSpec(com.wannaverse.dto.ContainerSpec spec) {
        // The image is supplied by the build; validate the rest with a placeholder.
        String image = spec.getImage();
        spec.setImage("placeholder:latest");
        try {
            var ctx = com.wannaverse.security.SecurityContextHolder.getContext();
            specService.validateSettings(spec, ctx != null && ctx.isAdmin());
        } finally {
            spec.setImage(image);
        }
    }

    /** Checks that the rollout exists and runs on the repository's host. */
    private String rolloutFor(String rolloutId, String hostId) {
        if (isBlank(rolloutId)) return null;
        Rollout rollout =
                rolloutRepository
                        .findById(rolloutId.trim())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST, "Rollout not found"));
        if (!rollout.getDockerHost().getId().equals(hostId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The rollout runs on another Docker host; images are built on the"
                            + " repository's host");
        }
        return rollout.getId();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
