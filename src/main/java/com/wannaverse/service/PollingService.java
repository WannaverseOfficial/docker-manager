package com.wannaverse.service;

import com.wannaverse.persistence.DeploymentJob;
import com.wannaverse.persistence.DeploymentJob.JobStatus;
import com.wannaverse.persistence.DeploymentJob.TriggerType;
import com.wannaverse.persistence.DeploymentJobRepository;
import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.GitRepositoryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PollingService {
    private static final Logger log = LoggerFactory.getLogger(PollingService.class);

    private final GitRepositoryRepository gitRepoRepository;
    private final DeploymentJobRepository jobRepository;
    private final GitService gitService;
    private final BuildService buildService;
    private final Map<String, Long> lastPolledAt = new ConcurrentHashMap<>();

    public PollingService(
            GitRepositoryRepository gitRepoRepository,
            DeploymentJobRepository jobRepository,
            GitService gitService,
            BuildService buildService) {
        this.gitRepoRepository = gitRepoRepository;
        this.jobRepository = jobRepository;
        this.gitService = gitService;
        this.buildService = buildService;
    }

    @Scheduled(fixedRate = 60000)
    public void pollRepositories() {
        List<GitRepository> repositories = gitRepoRepository.findByPollingEnabledTrue();

        for (GitRepository repo : repositories) {
            try {
                if (!shouldPoll(repo)) {
                    continue;
                }

                checkAndDeploy(repo, TriggerType.POLLING);
            } catch (Exception e) {
                log.error("Error polling repository {}: {}", repo.getName(), e.getMessage());
            }
        }
    }

    public boolean checkAndDeploy(GitRepository repo, TriggerType triggerType) {
        try {
            String latestCommit = gitService.getLatestCommitSha(repo);
            if (latestCommit == null) {
                log.warn("Could not get latest commit for repository {}", repo.getName());
                return false;
            }

            if (latestCommit.equals(repo.getLastCommitSha())) {
                log.debug("No new commits for repository {}", repo.getName());
                return false;
            }

            log.info(
                    "New commit detected for {}: {} -> {}",
                    repo.getName(),
                    repo.getLastCommitSha(),
                    latestCommit);

            DeploymentJob job = new DeploymentJob();
            job.setGitRepository(repo);
            job.setStatus(JobStatus.PENDING);
            job.setTriggerType(triggerType);
            job.setCommitSha(latestCommit);
            jobRepository.save(job);

            buildService.executeDeployment(job);
            return true;
        } catch (Exception e) {
            log.error("Error checking repository {}: {}", repo.getName(), e.getMessage());
            return false;
        }
    }

    private boolean shouldPoll(GitRepository repo) {
        long now = System.currentTimeMillis();
        Long lastPoll = lastPolledAt.get(repo.getId());

        if (lastPoll == null) {
            lastPolledAt.put(repo.getId(), now);
            return true;
        }

        int intervalSeconds = repo.getPollingIntervalSeconds();
        if (intervalSeconds <= 0) {
            intervalSeconds = 300;
        }

        if (now - lastPoll >= intervalSeconds * 1000L) {
            lastPolledAt.put(repo.getId(), now);
            return true;
        }

        return false;
    }

    public void resetPollingTimer(String repositoryId) {
        lastPolledAt.remove(repositoryId);
    }
}
