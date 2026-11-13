package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.Pipeline;
import com.wannaverse.persistence.PipelineExecution;
import com.wannaverse.persistence.PipelineExecution.ExecutionStatus;
import com.wannaverse.persistence.PipelineExecution.TriggerType;
import com.wannaverse.persistence.PipelineExecutionRepository;
import com.wannaverse.persistence.PipelineRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class PipelinePollingServiceTest {
    private PipelineExecutionRepository executions;
    private PipelineExecutionService runner;
    private GitService git;
    private PipelinePollingService poller;
    private Pipeline pipeline;
    private final Map<String, String> heads = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        executions = mock(PipelineExecutionRepository.class);
        runner = mock(PipelineExecutionService.class);
        git = mock(GitService.class);
        poller =
                new PipelinePollingService(mock(PipelineRepository.class), executions, runner, git);

        GitRepository repo = new GitRepository();
        repo.setBranch("main");
        pipeline = new Pipeline();
        pipeline.setId("p1");
        pipeline.setName("ci");
        pipeline.setGitRepository(repo);
        heads.put("main", "aaa");
        heads.put("release/1.0", "bbb");
        heads.put("feature/x", "ccc");
        when(git.branchHeads(repo)).thenReturn(heads);
        when(executions
                        .findFirstByPipelineIdAndTriggerBranchAndTriggerCommitIsNotNullOrderByCreatedAtDesc(
                                anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(executions.findFirstByPipelineIdAndTriggerBranchOrderByCreatedAtDesc(
                        anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    private static PipelineExecution run(String commit, ExecutionStatus status) {
        PipelineExecution e = new PipelineExecution();
        e.setTriggerCommit(commit);
        e.setStatus(status);
        return e;
    }

    @Test
    void watchesTheRepositoryBranchOrWhatTheFilterFullyMatches() {
        GitRepository repo = pipeline.getGitRepository();
        assertEquals(List.of("main"), PipelinePollingService.branches(pipeline, repo, heads));

        pipeline.setBranchFilter("main|release/.*");
        assertEquals(
                List.of("main", "release/1.0"),
                PipelinePollingService.branches(pipeline, repo, heads));

        // A full match, like the webhook: "release" alone doesn't match release/1.0.
        pipeline.setBranchFilter("release");
        assertEquals(List.of(), PipelinePollingService.branches(pipeline, repo, heads));

        pipeline.setBranchFilter("([");
        assertEquals(List.of(), PipelinePollingService.branches(pipeline, repo, heads));

        pipeline.setBranchFilter(null);
        repo.setBranch("gone");
        assertEquals(List.of(), PipelinePollingService.branches(pipeline, repo, heads));
    }

    @Test
    void aBranchNeverRunIsOnlyNotedThenRunsOnItsNextCommit() throws Exception {
        poller.poll(pipeline);
        verifyNoInteractions(runner);

        heads.put("main", "aab");
        poller.poll(pipeline);
        verify(runner).triggerExecution("p1", TriggerType.POLLING, "aab", "main", "polling");

        poller.poll(pipeline);
        verify(runner, times(1)).triggerExecution(any(), any(), any(), any(), any());
    }

    @Test
    void runsWhenTheBranchMovedSinceItsLastRunEvenAfterARestart() throws Exception {
        when(executions
                        .findFirstByPipelineIdAndTriggerBranchAndTriggerCommitIsNotNullOrderByCreatedAtDesc(
                                "p1", "main"))
                .thenReturn(Optional.of(run("old", ExecutionStatus.SUCCESS)));
        when(executions.findFirstByPipelineIdAndTriggerBranchOrderByCreatedAtDesc("p1", "main"))
                .thenReturn(Optional.of(run("old", ExecutionStatus.SUCCESS)));

        poller.poll(pipeline);
        verify(runner).triggerExecution("p1", TriggerType.POLLING, "aaa", "main", "polling");
    }

    @Test
    void doesNotRunACommitAnotherRunCovers() throws Exception {
        when(executions
                        .findFirstByPipelineIdAndTriggerBranchAndTriggerCommitIsNotNullOrderByCreatedAtDesc(
                                "p1", "main"))
                .thenReturn(Optional.of(run("old", ExecutionStatus.SUCCESS)));

        // A webhook run that has not checked out yet builds the newest commit anyway.
        when(executions.findFirstByPipelineIdAndTriggerBranchOrderByCreatedAtDesc("p1", "main"))
                .thenReturn(Optional.of(run(null, ExecutionStatus.RUNNING)));
        poller.poll(pipeline);
        verifyNoInteractions(runner);

        // The same commit already ran.
        heads.put("main", "zzz");
        when(executions.findFirstByPipelineIdAndTriggerBranchOrderByCreatedAtDesc("p1", "main"))
                .thenReturn(Optional.of(run("zzz", ExecutionStatus.SUCCESS)));
        poller.poll(pipeline);
        verifyNoInteractions(runner);
    }

    @Test
    void eachMatchingBranchRunsOnItsOwnNewCommit() throws Exception {
        pipeline.setBranchFilter(".*");
        poller.poll(pipeline);
        verifyNoInteractions(runner);

        heads.put("feature/x", "ccd");
        poller.poll(pipeline);
        verify(runner)
                .triggerExecution(
                        eq("p1"),
                        eq(TriggerType.POLLING),
                        eq("ccd"),
                        eq("feature/x"),
                        eq("polling"));
        verify(runner, times(1)).triggerExecution(any(), any(), any(), any(), any());
    }
}
