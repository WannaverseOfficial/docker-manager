package com.wannaverse.iac.handlers;

import com.wannaverse.controllers.GitController;
import com.wannaverse.dto.ContainerSpec;
import com.wannaverse.dto.CreateGitRepositoryRequest;
import com.wannaverse.dto.GitRepositoryResponse;
import com.wannaverse.dto.UpdateGitRepositoryRequest;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.GitRepositoryRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.Rollout;
import com.wannaverse.persistence.RolloutRepository;
import com.wannaverse.service.GitService;

import lombok.Data;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code gitRepositories}: repositories Docker Manager builds and deploys from. Applying the file
 * never deploys; deployments start from a push (webhook), polling, or the Deploy button.
 */
@Component
public class GitRepositoriesHandler
        implements ResourceHandler<GitRepositoriesHandler.GitRepositorySpec> {

    @Data
    @Doc("A Git repository to build and deploy. Applying the file never deploys.")
    public static class GitRepositorySpec {
        @Doc("The Docker host that builds and runs it (a key of hosts:). Default: the only host.")
        private String host;

        @Doc("Name shown in Docker Manager. Defaults to the entry's key.")
        private String name;

        @Doc(value = "Clone address: https://... or git@host:owner/repo.git", required = true)
        private String url;

        @Doc("Branch to deploy (default main)")
        private String branch;

        @Doc("How to sign in to the Git server: none (default), pat or ssh-key")
        private GitRepository.AuthType auth;

        @Doc("auth: pat. The access token.")
        private Secret token;

        @Doc("auth: pat. The user name sent with the token (default oauth2).")
        private String tokenUsername;

        @Doc("auth: ssh-key. The private key (without a passphrase).")
        private Secret sshKey;

        @Doc(value = "How to deploy: dockerfile or docker-compose", required = true)
        private GitRepository.DeploymentType type;

        @Doc("dockerfile: path of the Dockerfile in the repository (default Dockerfile)")
        private String dockerfile;

        @Doc("docker-compose: path of the compose file (default compose.yaml)")
        private String compose;

        @Doc("dockerfile: name of the built image (default: from the repository name)")
        private String image;

        @Doc("dockerfile: run the built image as a container (default true)")
        private Boolean runContainer;

        @Doc("dockerfile: how the container runs")
        private AppContainerSpec container;

        @Doc("dockerfile: release new images through this rollout (a key of rollouts:)")
        private String rollout;

        @Doc("Check the branch for new commits regularly (default false)")
        private Boolean polling;

        @Doc("Seconds between checks, at least 60 (default 300)")
        private Integer pollingInterval;

        @Doc("Deploy when the Git server calls the webhook (default false)")
        private Boolean webhook;
    }

    private final GitController controller;
    private final GitRepositoryRepository repos;
    private final DockerHostRepository hosts;
    private final RolloutRepository rollouts;

    public GitRepositoriesHandler(
            @Lazy GitController controller,
            GitRepositoryRepository repos,
            DockerHostRepository hosts,
            RolloutRepository rollouts) {
        this.controller = controller;
        this.repos = repos;
        this.hosts = hosts;
        this.rollouts = rollouts;
    }

    @Override
    public String section() {
        return "gitRepositories";
    }

    @Override
    public int order() {
        return 310;
    }

    @Override
    public Class<GitRepositorySpec> specType() {
        return GitRepositorySpec.class;
    }

    @Override
    public String description() {
        return "Git repositories to build and deploy. Applying the file never deploys.";
    }

    @Override
    public void validate(Entry<GitRepositorySpec> entry, ApplyContext ctx) {
        GitRepositorySpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        GitService.validateUrl(s.getUrl().trim());
        GitRepository.AuthType auth = auth(s);
        if (auth == GitRepository.AuthType.PAT && s.getToken() == null) {
            throw new IacException("token: auth pat needs a token");
        }
        if (auth == GitRepository.AuthType.SSH_KEY && s.getSshKey() == null) {
            throw new IacException("sshKey: auth ssh-key needs a private key");
        }
        if (s.getPollingInterval() != null && s.getPollingInterval() < 60) {
            throw new IacException("pollingInterval: at least 60 seconds");
        }
        if (s.getContainer() != null) {
            for (String p : s.getContainer().getPorts()) AppContainerSpec.parsePort(p);
        }
        if (s.getRollout() != null && !s.getRollout().isBlank()) {
            if (s.getType() != GitRepository.DeploymentType.DOCKERFILE) {
                throw new IacException("rollout: only Dockerfile deployments release through one");
            }
            String rolloutId = ctx.ref("rollouts", s.getRollout().trim());
            if (rolloutId != null && hostId != null) {
                rollouts.findById(rolloutId)
                        .filter(r -> !r.getDockerHost().getId().equals(hostId))
                        .ifPresent(
                                r -> {
                                    throw new IacException(
                                            "rollout: "
                                                    + r.getName()
                                                    + " runs on another Docker host");
                                });
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Target> findExisting(Entry<GitRepositorySpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.spec().getHost());
        if (hostId == null) return Optional.empty();
        String name = entry.nameOr(entry.spec().getName());
        return repos.findByDockerHostId(hostId).stream()
                .filter(r -> r.getName().equals(name))
                .findFirst()
                .map(r -> target(r, hostId));
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        if (repos.existsById(name)) return Optional.of(name);
        List<GitRepository> named =
                repos.findAll().stream().filter(r -> r.getName().equals(name)).toList();
        if (named.size() > 1) {
            throw new IacException(
                    "several Git repositories are called \""
                            + name
                            + "\"; add the one you mean under gitRepositories:");
        }
        return named.stream().findFirst().map(GitRepository::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return repos.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<GitRepositorySpec> entry, ApplyContext ctx) {
        GitRepositorySpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        CreateGitRepositoryRequest req = new CreateGitRepositoryRequest();
        req.setName(entry.nameOr(s.getName()));
        req.setRepositoryUrl(s.getUrl().trim());
        req.setBranch(s.getBranch());
        req.setAuthType(auth(s));
        req.setToken(Secret.valueOf(s.getToken()));
        req.setSshKey(Secret.valueOf(s.getSshKey()));
        req.setTokenUsername(s.getTokenUsername());
        req.setRunContainer(s.getRunContainer());
        req.setRolloutId(rolloutId(s, ctx));
        req.setContainerSpec(s.getContainer() == null ? null : s.getContainer().toContainerSpec());
        req.setDeploymentType(s.getType());
        req.setDockerfilePath(s.getDockerfile());
        req.setComposePath(s.getCompose());
        req.setImageName(s.getImage());
        req.setDockerHostId(hostId);
        req.setPollingEnabled(Boolean.TRUE.equals(s.getPolling()));
        req.setPollingIntervalSeconds(
                s.getPollingInterval() == null ? 300 : s.getPollingInterval());
        req.setWebhookEnabled(Boolean.TRUE.equals(s.getWebhook()));
        GitRepositoryResponse created = controller.createRepository(req).getBody();
        if (created == null) throw new IacException("the repository was not saved");
        if (Boolean.TRUE.equals(s.getWebhook())) {
            ctx.note(
                    "Git repository "
                            + created.getName()
                            + ": webhook address "
                            + created.getWebhookUrl());
        }
        return new Target(created.getId(), hostId, created.getName());
    }

    @Override
    @Transactional
    public Target update(
            Entry<GitRepositorySpec> entry,
            Changes changes,
            ManagedResource record,
            ApplyContext ctx) {
        GitRepositorySpec s = entry.spec();
        String hostId = ctx.hostId(s.getHost());
        GitRepository repo =
                repos.findWithHostById(record.getTargetId())
                        .orElseThrow(() -> new IacException("the repository no longer exists"));
        if (!repo.getDockerHost().getId().equals(hostId)) {
            DockerHost host =
                    hosts.findById(hostId).orElseThrow(() -> new IacException("host not found"));
            repo.setDockerHost(host);
            repos.save(repo);
        }
        UpdateGitRepositoryRequest req = new UpdateGitRepositoryRequest();
        req.setName(entry.nameOr(s.getName()));
        req.setRepositoryUrl(s.getUrl().trim());
        req.setBranch(s.getBranch() == null ? "main" : s.getBranch());
        req.setAuthType(auth(s));
        if (changes.has("token")) req.setToken(Secret.valueOf(s.getToken()));
        if (changes.has("sshKey")) req.setSshKey(Secret.valueOf(s.getSshKey()));
        req.setTokenUsername(s.getTokenUsername() == null ? "" : s.getTokenUsername());
        req.setRunContainer(s.getRunContainer() == null || s.getRunContainer());
        String rolloutId = rolloutId(s, ctx);
        req.setRolloutId(rolloutId == null ? "" : rolloutId);
        req.setContainerSpec(
                s.getContainer() == null
                        ? new ContainerSpec()
                        : s.getContainer().toContainerSpec());
        req.setDeploymentType(s.getType());
        req.setDockerfilePath(s.getDockerfile() == null ? "" : s.getDockerfile());
        req.setComposePath(s.getCompose() == null ? "" : s.getCompose());
        req.setImageName(s.getImage() == null ? "" : s.getImage());
        req.setPollingEnabled(Boolean.TRUE.equals(s.getPolling()));
        req.setPollingIntervalSeconds(
                s.getPollingInterval() == null ? 300 : s.getPollingInterval());
        req.setWebhookEnabled(Boolean.TRUE.equals(s.getWebhook()));
        GitRepositoryResponse saved = controller.updateRepository(repo.getId(), req).getBody();
        if (s.getContainer() == null) {
            // "No settings" is stored as none, as on create.
            repos.findById(repo.getId())
                    .ifPresent(
                            r -> {
                                r.setContainerSpecJson(null);
                                repos.save(r);
                            });
        }
        String name = saved == null ? req.getName() : saved.getName();
        return new Target(repo.getId(), hostId, name);
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        controller.deleteRepository(record.getTargetId());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, GitRepositorySpec> export(ExportContext ctx) {
        Map<String, GitRepositorySpec> out = new LinkedHashMap<>();
        for (GitRepository r : repos.findAll()) {
            GitRepositorySpec s = new GitRepositorySpec();
            String hostKey = ctx.hostKey(r.getDockerHost().getId());
            String key = ctx.key(section(), r.getId(), r.getName());
            if (out.containsKey(key)) key = key + "-" + hostKey;
            s.setHost(hostKey);
            if (!key.equals(r.getName())) s.setName(r.getName());
            s.setUrl(r.getRepositoryUrl());
            if (!"main".equals(r.getBranch())) s.setBranch(r.getBranch());
            if (r.getAuthType() != GitRepository.AuthType.NONE) s.setAuth(r.getAuthType());
            if (r.getAuthType() == GitRepository.AuthType.PAT) {
                s.setToken(
                        ctx.secret(
                                section(), key, "token", "access token of Git repository " + key));
                s.setTokenUsername(r.getTokenUsername());
            }
            if (r.getAuthType() == GitRepository.AuthType.SSH_KEY) {
                s.setSshKey(
                        ctx.secret(section(), key, "ssh_key", "SSH key of Git repository " + key));
            }
            s.setType(r.getDeploymentType());
            s.setDockerfile(r.getDockerfilePath());
            s.setCompose(r.getComposePath());
            s.setImage(r.getImageName());
            if (Boolean.FALSE.equals(r.getRunContainer())) s.setRunContainer(false);
            s.setContainer(
                    AppContainerSpec.from(
                            GitRepositoryResponse.readSpec(r.getContainerSpecJson())));
            if (r.getRolloutId() != null) {
                String name =
                        rollouts.findById(r.getRolloutId())
                                .map(Rollout::getName)
                                .orElse(r.getRolloutId());
                s.setRollout(ctx.key("rollouts", r.getRolloutId(), name));
            }
            if (r.isPollingEnabled()) s.setPolling(true);
            if (r.getPollingIntervalSeconds() != 300 && r.getPollingIntervalSeconds() > 0) {
                s.setPollingInterval(r.getPollingIntervalSeconds());
            }
            if (r.isWebhookEnabled()) s.setWebhook(true);
            out.put(key, s);
        }
        return out;
    }

    private static GitRepository.AuthType auth(GitRepositorySpec s) {
        return s.getAuth() == null ? GitRepository.AuthType.NONE : s.getAuth();
    }

    private static String rolloutId(GitRepositorySpec s, ApplyContext ctx) {
        if (s.getRollout() == null || s.getRollout().isBlank()) return null;
        return ctx.ref("rollouts", s.getRollout().trim());
    }

    private static Target target(GitRepository r, String hostId) {
        return new Target(r.getId(), hostId, r.getName());
    }
}
