package com.wannaverse.service;

import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.Pipeline;
import com.wannaverse.persistence.PipelineExecution;
import com.wannaverse.persistence.PipelineExecutionRepository;
import com.wannaverse.persistence.PipelineRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Runs pipelines with polling turned on when their repository gets new commits, for servers that
 * can't receive webhooks.
 *
 * <p>Each check lists the repository's branches. Without a branch filter only the repository's
 * branch is watched; with one, every branch it fully matches. A branch runs when its newest commit
 * differs from the commit of its last run. A branch the pipeline has never run is only noted the
 * first time it is seen, so turning polling on (or a filter like {@code .*}) doesn't start a run
 * for every existing branch.
 */
@Service
public class PipelinePollingService {
    private static final Logger log = LoggerFactory.getLogger(PipelinePollingService.class);
    static final int DEFAULT_INTERVAL_SECONDS = 300;
    static final int MIN_INTERVAL_SECONDS = 60;

    private final PipelineRepository pipelineRepository;
    private final PipelineExecutionRepository executionRepository;
    private final PipelineExecutionService executionService;
    private final GitService gitService;

    private final Map<String, Long> lastPolledAt = new ConcurrentHashMap<>();

    /** pipeline id + "\n" + branch -> the newest commit seen or run. */
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    public PipelinePollingService(
            PipelineRepository pipelineRepository,
            PipelineExecutionRepository executionRepository,
            PipelineExecutionService executionService,
            GitService gitService) {
        this.pipelineRepository = pipelineRepository;
        this.executionRepository = executionRepository;
        this.executionService = executionService;
        this.gitService = gitService;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void pollPipelines() {
        long now = System.currentTimeMillis();
        for (Pipeline pipeline : pipelineRepository.findEnabledPollingPipelines()) {
            if (!due(pipeline, now)) continue;
            try {
                poll(pipeline);
            } catch (Exception e) {
                log.warn("Polling pipeline {} failed: {}", pipeline.getName(), e.getMessage());
            }
        }
    }

    private boolean due(Pipeline pipeline, long now) {
        int interval = pipeline.getPollingIntervalSeconds();
        if (interval <= 0) interval = DEFAULT_INTERVAL_SECONDS;
        interval = Math.max(interval, MIN_INTERVAL_SECONDS);
        Long last = lastPolledAt.get(pipeline.getId());
        if (last != null && now - last < interval * 1000L) return false;
        lastPolledAt.put(pipeline.getId(), now);
        return true;
    }

    /** Checks one pipeline's branches and starts a run for each one with a new commit. */
    void poll(Pipeline pipeline) throws Exception {
        GitRepository repo = pipeline.getGitRepository();
        if (repo == null) return;
        Map<String, String> heads = gitService.branchHeads(repo);
        for (String branch : branches(pipeline, repo, heads)) {
            String head = heads.get(branch);
            String key = pipeline.getId() + "\n" + branch;
            String known = seen.get(key);
            if (known == null) {
                known =
                        executionRepository
                                .findFirstByPipelineIdAndTriggerBranchAndTriggerCommitIsNotNullOrderByCreatedAtDesc(
                                        pipeline.getId(), branch)
                                .map(PipelineExecution::getTriggerCommit)
                                .orElse(null);
                if (known == null) {
                    // Never run on this branch: start from here.
                    seen.put(key, head);
                    continue;
                }
            }
            if (head.equals(known) || alreadyRunning(pipeline.getId(), branch, head)) {
                seen.put(key, head);
                continue;
            }
            log.info(
                    "Pipeline {}: new commit on {} ({}), starting a run",
                    pipeline.getName(),
                    branch,
                    head.substring(0, Math.min(7, head.length())));
            seen.put(key, head);
            executionService.triggerExecution(
                    pipeline.getId(),
                    PipelineExecution.TriggerType.POLLING,
                    head,
                    branch,
                    "polling");
        }
    }

    /**
     * Whether the newest run of the branch already covers this commit: it ran it, or it is still
     * starting without a pinned commit (a webhook that wasn't signed), so it builds the newest one.
     */
    private boolean alreadyRunning(String pipelineId, String branch, String head) {
        return executionRepository
                .findFirstByPipelineIdAndTriggerBranchOrderByCreatedAtDesc(pipelineId, branch)
                .map(
                        e ->
                                head.equals(e.getTriggerCommit())
                                        || (e.getTriggerCommit() == null
                                                && (e.getStatus()
                                                                == PipelineExecution.ExecutionStatus
                                                                        .PENDING
                                                        || e.getStatus()
                                                                == PipelineExecution.ExecutionStatus
                                                                        .RUNNING)))
                .orElse(false);
    }

    /** The branches to watch: those the filter fully matches, or else the repository's branch. */
    static List<String> branches(Pipeline pipeline, GitRepository repo, Map<String, String> heads) {
        String filter = pipeline.getBranchFilter();
        if (filter == null || filter.isBlank()) {
            return heads.containsKey(repo.getBranch()) ? List.of(repo.getBranch()) : List.of();
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(filter);
        } catch (PatternSyntaxException e) {
            log.warn(
                    "Pipeline {}: the branch filter \"{}\" is not a valid regular expression",
                    pipeline.getName(),
                    filter);
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String branch : heads.keySet()) {
            if (pattern.matcher(branch).matches()) out.add(branch);
        }
        return out;
    }
}
