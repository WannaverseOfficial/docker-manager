package com.wannaverse.service;

import com.wannaverse.persistence.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PipelineExecutionService {
    private static final Logger log = LoggerFactory.getLogger(PipelineExecutionService.class);

    private final PipelineRepository pipelineRepository;
    private final PipelineExecutionRepository executionRepository;
    private final StageExecutionRepository stageExecutionRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final StepExecutorService stepExecutorService;
    private final NotificationService notificationService;
    private final GitService gitService;
    private final TransactionTemplate transactionTemplate;

    @Lazy @Autowired private PipelineExecutionService self;

    private final Map<String, Set<SseEmitter>> executionEmitters = new ConcurrentHashMap<>();

    public PipelineExecutionService(
            PipelineRepository pipelineRepository,
            PipelineExecutionRepository executionRepository,
            StageExecutionRepository stageExecutionRepository,
            StepExecutionRepository stepExecutionRepository,
            StepExecutorService stepExecutorService,
            NotificationService notificationService,
            GitService gitService,
            TransactionTemplate transactionTemplate) {
        this.pipelineRepository = pipelineRepository;
        this.executionRepository = executionRepository;
        this.stageExecutionRepository = stageExecutionRepository;
        this.stepExecutionRepository = stepExecutionRepository;
        this.stepExecutorService = stepExecutorService;
        this.notificationService = notificationService;
        this.gitService = gitService;
        this.transactionTemplate = transactionTemplate;
    }

    /** Extra environment variables and network for runs started by a rollout's test step. */
    private record RunExtras(Map<String, String> env, String network) {}

    private final Map<String, RunExtras> runExtras = new ConcurrentHashMap<>();

    @Transactional
    public PipelineExecution triggerExecution(
            String pipelineId,
            PipelineExecution.TriggerType triggerType,
            String commit,
            String branch,
            String triggeredBy) {
        return triggerExecution(pipelineId, triggerType, commit, branch, triggeredBy, null, null);
    }

    /**
     * Starts a run whose steps also get {@code extraEnv} and run attached to {@code network} (a
     * rollout's tests reach the new version this way).
     */
    @Transactional
    public PipelineExecution triggerExecution(
            String pipelineId,
            PipelineExecution.TriggerType triggerType,
            String commit,
            String branch,
            String triggeredBy,
            Map<String, String> extraEnv,
            String network) {

        Pipeline pipeline =
                pipelineRepository
                        .findById(pipelineId)
                        .orElseThrow(
                                () -> new RuntimeException("Pipeline not found: " + pipelineId));

        if (!pipeline.isEnabled()) {
            throw new RuntimeException("Pipeline is disabled");
        }

        int buildNumber =
                executionRepository.findMaxBuildNumberByPipelineId(pipelineId).orElse(0) + 1;

        PipelineExecution execution = new PipelineExecution();
        execution.setPipeline(pipeline);
        execution.setBuildNumber(buildNumber);
        execution.setStatus(PipelineExecution.ExecutionStatus.PENDING);
        execution.setTriggerType(triggerType);
        execution.setTriggerCommit(commit);
        execution.setTriggerBranch(branch != null ? branch : getBranchFromPipeline(pipeline));
        execution.setTriggeredBy(triggeredBy);
        execution = executionRepository.save(execution);

        for (PipelineStage stage : pipeline.getStages()) {
            StageExecution stageExecution = new StageExecution();
            stageExecution.setExecution(execution);
            stageExecution.setStage(stage);
            stageExecution.setOrderIndex(stage.getOrderIndex());
            stageExecution.setStatus(PipelineExecution.ExecutionStatus.PENDING);
            stageExecution = stageExecutionRepository.save(stageExecution);

            for (PipelineStep step : stage.getSteps()) {
                StepExecution stepExecution = new StepExecution();
                stepExecution.setStageExecution(stageExecution);
                stepExecution.setStep(step);
                stepExecution.setOrderIndex(step.getOrderIndex());
                stepExecution.setStatus(PipelineExecution.ExecutionStatus.PENDING);
                stepExecutionRepository.save(stepExecution);
            }
        }

        final String executionId = execution.getId();
        if ((extraEnv != null && !extraEnv.isEmpty()) || network != null) {
            runExtras.put(
                    executionId,
                    new RunExtras(extraEnv == null ? Map.of() : Map.copyOf(extraEnv), network));
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        self.executeAsync(executionId);
                    }
                });

        log.info(
                "Triggered pipeline execution: {} build #{} ({})",
                pipeline.getName(),
                buildNumber,
                triggerType);
        return execution;
    }

    @Async("deploymentExecutor")
    public void executeAsync(String executionId) {
        if (executionRepository
                .findById(executionId)
                .map(e -> e.getStatus() == PipelineExecution.ExecutionStatus.CANCELLED)
                .orElse(true)) {
            return;
        }
        ExecutionContext ctx =
                transactionTemplate.execute(
                        status -> {
                            PipelineExecution exec =
                                    executionRepository
                                            .findById(executionId)
                                            .orElseThrow(
                                                    () ->
                                                            new RuntimeException(
                                                                    "Execution not found: "
                                                                            + executionId));

                            Pipeline pipeline =
                                    pipelineRepository
                                            .findById(exec.getPipeline().getId())
                                            .orElseThrow(
                                                    () ->
                                                            new RuntimeException(
                                                                    "Pipeline not found"));

                            ExecutionContext context = new ExecutionContext();
                            context.executionId = executionId;
                            context.buildNumber = exec.getBuildNumber();
                            context.pipelineId = pipeline.getId();
                            context.pipelineName = pipeline.getName();
                            context.dockerHostUrl =
                                    pipeline.getDockerHost() != null
                                            ? pipeline.getDockerHost().getDockerHostUrl()
                                            : null;

                            if (pipeline.getGitRepository() != null) {
                                context.hasGitRepo = true;
                                context.gitRepo =
                                        (GitRepository)
                                                org.hibernate.Hibernate.unproxy(
                                                        pipeline.getGitRepository());
                                context.gitRepoUrl = context.gitRepo.getRepositoryUrl();
                                context.gitBranch = context.gitRepo.getBranch();
                            }
                            context.triggerCommit = exec.getTriggerCommit();

                            context.triggerBranch = exec.getTriggerBranch();
                            context.triggerType = exec.getTriggerType();
                            context.triggeredBy = exec.getTriggeredBy();

                            return context;
                        });

        if (ctx == null) {
            log.error("Failed to load execution context for: {}", executionId);
            return;
        }

        StepExecutorService.Workspace workspace = null;

        try {
            if (ctx.dockerHostUrl == null) {
                throw new IllegalStateException("The pipeline has no Docker host");
            }
            transactionTemplate.executeWithoutResult(
                    status -> {
                        PipelineExecution exec =
                                executionRepository.findById(executionId).orElseThrow();
                        exec.setStatus(PipelineExecution.ExecutionStatus.RUNNING);
                        exec.setStartedAt(System.currentTimeMillis());
                        executionRepository.save(exec);
                    });
            logAndBroadcast(executionId, "Starting pipeline execution #" + ctx.buildNumber);

            workspace =
                    stepExecutorService.prepareWorkspace(
                            ctx.dockerHostUrl, ctx.pipelineId, executionId);
            RunExtras extras = runExtras.remove(executionId);
            if (extras != null) workspace.network = extras.network();

            if (ctx.hasGitRepo) {
                String branch =
                        ctx.triggerBranch != null && !ctx.triggerBranch.isBlank()
                                ? ctx.triggerBranch
                                : ctx.gitBranch;
                logAndBroadcast(executionId, "Cloning " + ctx.gitRepoUrl + " (" + branch + ")");
                String commitSha =
                        stepExecutorService.cloneRepository(
                                ctx.gitRepo,
                                branch,
                                ctx.triggerCommit,
                                workspace,
                                line -> logAndBroadcast(executionId, line));
                ctx.triggerCommit = commitSha;
                transactionTemplate.executeWithoutResult(
                        status -> {
                            PipelineExecution exec =
                                    executionRepository.findById(executionId).orElseThrow();
                            exec.setTriggerCommit(commitSha);
                            executionRepository.save(exec);
                        });
            }

            Map<String, String> env = buildEnvironmentVariables(ctx);
            if (extras != null) env.putAll(extras.env());

            boolean allSuccess = executeDependencyGraph(executionId, ctx, workspace, env);
            boolean wasCancelled = isCancelled(executionId);

            final boolean success = allSuccess;
            logAndBroadcast(
                    executionId,
                    wasCancelled
                            ? "Pipeline cancelled"
                            : success
                                    ? "Pipeline completed successfully"
                                    : "Pipeline completed with failures");
            transactionTemplate.executeWithoutResult(
                    status -> {
                        PipelineExecution exec =
                                executionRepository.findById(executionId).orElseThrow();
                        exec.setStatus(
                                wasCancelled
                                        ? PipelineExecution.ExecutionStatus.CANCELLED
                                        : success
                                                ? PipelineExecution.ExecutionStatus.SUCCESS
                                                : PipelineExecution.ExecutionStatus.FAILED);
                        exec.setFinishedAt(System.currentTimeMillis());
                        exec.setLogs(logText(executionId));
                        executionRepository.save(exec);
                    });

        } catch (Exception e) {
            log.error("Pipeline execution failed: {}", executionId, e);
            logAndBroadcast(executionId, "ERROR: " + e.getMessage());
            transactionTemplate.executeWithoutResult(
                    status -> {
                        PipelineExecution exec =
                                executionRepository.findById(executionId).orElseThrow();
                        exec.setStatus(PipelineExecution.ExecutionStatus.FAILED);
                        exec.setErrorMessage(e.getMessage());
                        exec.setFinishedAt(System.currentTimeMillis());
                        exec.setLogs(logText(executionId));
                        executionRepository.save(exec);
                    });
        } finally {
            runExtras.remove(executionId);
            completeEmitters(executionId);
            cancelledExecutions.remove(executionId);
            logBuffers.remove(executionId);
            if (workspace != null) workspace.close();
        }
    }

    private static class ExecutionContext {
        String executionId;
        int buildNumber;
        String pipelineId;
        String pipelineName;
        String dockerHostUrl;
        boolean hasGitRepo;
        String gitRepoUrl;
        String gitBranch;
        String triggerBranch;
        PipelineExecution.TriggerType triggerType;
        String triggeredBy;
        String triggerCommit;
        GitRepository gitRepo;
    }

    private void appendLog(PipelineExecution execution, String message) {
        String timestamp = java.time.Instant.now().toString();
        String logLine = "[" + timestamp + "] " + message;
        String currentLogs = execution.getLogs() != null ? execution.getLogs() : "";
        execution.setLogs(currentLogs + logLine + "\n");
    }

    private void broadcastLog(String executionId, String message) {
        String timestamp = java.time.Instant.now().toString();
        String logLine = "[" + timestamp + "] " + message;

        Set<SseEmitter> emitters = executionEmitters.get(executionId);
        if (emitters != null) {
            List<SseEmitter> deadEmitters = new ArrayList<>();
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("log").data(logLine));
                } catch (IOException e) {
                    deadEmitters.add(emitter);
                }
            }
            emitters.removeAll(deadEmitters);
        }
    }

    private record StageInfo(
            String stageId,
            String stageExecId,
            String stageName,
            List<String> dependsOn,
            boolean stopOnFailure) {}

    private boolean executeDependencyGraph(
            String executionId,
            ExecutionContext ctx,
            StepExecutorService.Workspace workspacePath,
            Map<String, String> env) {

        List<StageInfo> stageInfoList =
                transactionTemplate.execute(
                        status -> {
                            List<StageExecution> stageExecutions =
                                    stageExecutionRepository.findByExecutionIdOrderByOrderIndexAsc(
                                            executionId);

                            List<StageInfo> infos = new ArrayList<>();
                            for (StageExecution se : stageExecutions) {
                                PipelineStage stage = se.getStage();
                                infos.add(
                                        new StageInfo(
                                                stage.getId(),
                                                se.getId(),
                                                stage.getName(),
                                                stage.getDependsOn() != null
                                                        ? new ArrayList<>(stage.getDependsOn())
                                                        : List.of(),
                                                stage.isStopOnFailure()));
                            }
                            return infos;
                        });

        if (stageInfoList == null || stageInfoList.isEmpty()) {
            logAndBroadcast(executionId, "No stages to execute");
            return true;
        }

        Map<String, String> stageExecIdByStageId = new HashMap<>();
        Map<String, String> stageNameById = new HashMap<>();
        Map<String, List<String>> stageDepsById = new HashMap<>();
        Map<String, Boolean> stageStopOnFailureById = new HashMap<>();

        for (StageInfo si : stageInfoList) {
            stageExecIdByStageId.put(si.stageId, si.stageExecId);
            stageNameById.put(si.stageId, si.stageName);
            stageDepsById.put(si.stageId, si.dependsOn);
            stageStopOnFailureById.put(si.stageId, si.stopOnFailure);
        }

        List<String> allStageIds = new ArrayList<>(stageExecIdByStageId.keySet());

        Map<String, Boolean> completedStages = new ConcurrentHashMap<>();
        Set<String> runningStages = ConcurrentHashMap.newKeySet();
        boolean[] stopExecution = {false};

        while (completedStages.size() < allStageIds.size()
                && !stopExecution[0]
                && !isCancelled(executionId)) {

            List<String> readyStageIds = new ArrayList<>();
            for (String stageId : allStageIds) {
                if (completedStages.containsKey(stageId) || runningStages.contains(stageId)) {
                    continue;
                }

                List<String> deps = stageDepsById.get(stageId);
                boolean depsReady = true;
                boolean depFailed = false;

                if (deps != null && !deps.isEmpty()) {
                    for (String depId : deps) {
                        if (!completedStages.containsKey(depId)) {
                            depsReady = false;
                            break;
                        }
                        if (!completedStages.get(depId)) {
                            depFailed = true;
                        }
                    }
                }

                if (depsReady) {
                    if (depFailed) {
                        String stageExecId = stageExecIdByStageId.get(stageId);
                        transactionTemplate.executeWithoutResult(
                                status -> {
                                    StageExecution se =
                                            stageExecutionRepository
                                                    .findById(stageExecId)
                                                    .orElseThrow();
                                    se.setStatus(PipelineExecution.ExecutionStatus.CANCELLED);
                                    stageExecutionRepository.save(se);
                                });
                        completedStages.put(stageId, false);
                        logAndBroadcast(
                                executionId,
                                "Skipping stage '"
                                        + stageNameById.get(stageId)
                                        + "' due to failed dependency");
                    } else {
                        readyStageIds.add(stageId);
                    }
                }
            }

            if (readyStageIds.isEmpty() && runningStages.isEmpty()) {
                break;
            }

            if (readyStageIds.isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            List<Thread> threads = new ArrayList<>();
            final StepExecutorService.Workspace workspace = workspacePath;

            for (String stageId : readyStageIds) {
                String stageExecId = stageExecIdByStageId.get(stageId);
                String stageName = stageNameById.get(stageId);
                boolean stopOnFailure = stageStopOnFailureById.get(stageId);
                runningStages.add(stageId);

                Thread thread =
                        new Thread(
                                () -> {
                                    try {
                                        boolean success =
                                                executeStage(
                                                        stageExecId,
                                                        executionId,
                                                        ctx,
                                                        workspace,
                                                        env);
                                        completedStages.put(stageId, success);

                                        if (!success && stopOnFailure) {
                                            stopExecution[0] = true;
                                            logAndBroadcast(
                                                    executionId,
                                                    "Stopping pipeline: stage '"
                                                            + stageName
                                                            + "' failed with stopOnFailure=true");
                                        }
                                    } finally {
                                        runningStages.remove(stageId);
                                    }
                                });
                threads.add(thread);
                thread.start();
            }

            for (Thread thread : threads) {
                try {
                    thread.join(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        while (!runningStages.isEmpty()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        for (String stageId : allStageIds) {
            if (!completedStages.containsKey(stageId)) {
                String stageExecId = stageExecIdByStageId.get(stageId);
                transactionTemplate.executeWithoutResult(
                        status -> {
                            StageExecution se =
                                    stageExecutionRepository.findById(stageExecId).orElseThrow();
                            se.setStatus(PipelineExecution.ExecutionStatus.CANCELLED);
                            stageExecutionRepository.save(se);
                        });
                completedStages.put(stageId, false);
            }
        }

        return completedStages.values().stream().allMatch(Boolean::booleanValue);
    }

    private record StepInfo2(String stepExecId, boolean continueOnFailure) {}

    private boolean executeStage(
            String stageExecId,
            String executionId,
            ExecutionContext ctx,
            StepExecutorService.Workspace workspacePath,
            Map<String, String> env) {

        record StageData(
                String stageName, PipelineStage.ExecutionMode execMode, List<StepInfo2> steps) {}

        StageData stageData =
                transactionTemplate.execute(
                        status -> {
                            StageExecution se =
                                    stageExecutionRepository.findById(stageExecId).orElseThrow();
                            PipelineStage stage = se.getStage();

                            List<StepExecution> stepExecs =
                                    stepExecutionRepository
                                            .findByStageExecutionIdOrderByOrderIndexAsc(
                                                    stageExecId);
                            List<StepInfo2> stepInfos = new ArrayList<>();
                            for (StepExecution stepExec : stepExecs) {
                                stepInfos.add(
                                        new StepInfo2(
                                                stepExec.getId(),
                                                stepExec.getStep().isContinueOnFailure()));
                            }

                            return new StageData(
                                    stage.getName(), stage.getExecutionMode(), stepInfos);
                        });

        if (stageData == null) {
            return false;
        }

        logAndBroadcast(executionId, "\n=== Stage: " + stageData.stageName + " ===");

        transactionTemplate.executeWithoutResult(
                status -> {
                    StageExecution se =
                            stageExecutionRepository.findById(stageExecId).orElseThrow();
                    se.setStatus(PipelineExecution.ExecutionStatus.RUNNING);
                    se.setStartedAt(System.currentTimeMillis());
                    stageExecutionRepository.save(se);
                });

        boolean allSuccess = true;

        if (stageData.execMode == PipelineStage.ExecutionMode.PARALLEL) {
            List<Thread> threads = new ArrayList<>();
            List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

            for (StepInfo2 stepInfo : stageData.steps) {
                Thread thread =
                        new Thread(
                                () -> {
                                    boolean success =
                                            executeStep(
                                                    stepInfo.stepExecId,
                                                    executionId,
                                                    ctx,
                                                    workspacePath,
                                                    env);
                                    results.add(success);
                                });
                threads.add(thread);
                thread.start();
            }

            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            allSuccess = results.stream().allMatch(Boolean::booleanValue);
        } else {
            for (StepInfo2 stepInfo : stageData.steps) {
                if (isCancelled(executionId)) {
                    allSuccess = false;
                    break;
                }
                boolean success =
                        executeStep(stepInfo.stepExecId, executionId, ctx, workspacePath, env);
                if (!success) {
                    allSuccess = false;
                    if (!stepInfo.continueOnFailure) {
                        break;
                    }
                }
            }
        }

        final boolean finalSuccess = allSuccess;
        transactionTemplate.executeWithoutResult(
                status -> {
                    StageExecution se =
                            stageExecutionRepository.findById(stageExecId).orElseThrow();
                    se.setStatus(
                            finalSuccess
                                    ? PipelineExecution.ExecutionStatus.SUCCESS
                                    : PipelineExecution.ExecutionStatus.FAILED);
                    se.setFinishedAt(System.currentTimeMillis());
                    stageExecutionRepository.save(se);
                });

        return allSuccess;
    }

    private boolean executeStep(
            String stepExecId,
            String executionId,
            ExecutionContext ctx,
            StepExecutorService.Workspace workspacePath,
            Map<String, String> env) {

        record StepInfo(
                String stepName,
                PipelineStep.StepType stepType,
                String configuration,
                String workingDirectory,
                int timeoutSeconds,
                String envVars) {}

        StepInfo stepInfo =
                transactionTemplate.execute(
                        status -> {
                            StepExecution se =
                                    stepExecutionRepository.findById(stepExecId).orElseThrow();
                            PipelineStep step = se.getStep();
                            return new StepInfo(
                                    step.getName(),
                                    step.getStepType(),
                                    step.getConfiguration(),
                                    step.getWorkingDirectory(),
                                    step.getTimeoutSeconds(),
                                    step.getEnvironmentVariables());
                        });

        logAndBroadcast(executionId, "  → Step: " + stepInfo.stepName);

        transactionTemplate.executeWithoutResult(
                status -> {
                    StepExecution se = stepExecutionRepository.findById(stepExecId).orElseThrow();
                    se.setStatus(PipelineExecution.ExecutionStatus.RUNNING);
                    se.setStartedAt(System.currentTimeMillis());
                    stepExecutionRepository.save(se);
                });

        try {
            StepExecutorService.StepResult result =
                    stepExecutorService.executeStep(
                            stepInfo.stepType,
                            stepInfo.stepName,
                            stepInfo.configuration,
                            stepInfo.workingDirectory,
                            stepInfo.timeoutSeconds,
                            stepInfo.envVars,
                            workspacePath,
                            env,
                            logLine -> logAndBroadcast(executionId, "    " + logLine),
                            () -> isCancelled(executionId));

            final boolean success = result.success();
            transactionTemplate.executeWithoutResult(
                    status -> {
                        StepExecution se =
                                stepExecutionRepository.findById(stepExecId).orElseThrow();
                        se.setExitCode(result.exitCode());
                        se.setLogs(result.logs());
                        se.setStatus(
                                success
                                        ? PipelineExecution.ExecutionStatus.SUCCESS
                                        : PipelineExecution.ExecutionStatus.FAILED);
                        if (!success) {
                            se.setErrorMessage(result.error());
                        }
                        se.setFinishedAt(System.currentTimeMillis());
                        stepExecutionRepository.save(se);
                    });

            if (success) {
                logAndBroadcast(executionId, "    ✓ Step completed successfully");
            } else {
                logAndBroadcast(
                        executionId, "    ✗ Step failed with exit code " + result.exitCode());
            }

            return success;

        } catch (Exception e) {
            log.error("Step execution failed: {}", stepInfo.stepName, e);

            transactionTemplate.executeWithoutResult(
                    status -> {
                        StepExecution se =
                                stepExecutionRepository.findById(stepExecId).orElseThrow();
                        se.setStatus(PipelineExecution.ExecutionStatus.FAILED);
                        se.setErrorMessage(e.getMessage());
                        se.setFinishedAt(System.currentTimeMillis());
                        stepExecutionRepository.save(se);
                    });

            logAndBroadcast(executionId, "    ✗ Step failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Logs are kept in memory and saved every couple of seconds. (Each line used to start a new
     * task that re-read the execution and rewrote the whole log; on a large build that queued
     * thousands of writes on SQLite's single connection and stalled the whole app.)
     */
    private final Map<String, StringBuilder> logBuffers = new ConcurrentHashMap<>();

    private final Map<String, Long> lastFlush = new ConcurrentHashMap<>();
    private final Set<String> cancelledExecutions = ConcurrentHashMap.newKeySet();

    private void logAndBroadcast(String executionId, String message) {
        broadcastLog(executionId, message);
        StringBuilder buffer = logBuffers.computeIfAbsent(executionId, k -> new StringBuilder());
        boolean flush;
        synchronized (buffer) {
            if (buffer.length() < 2_000_000) {
                buffer.append('[')
                        .append(java.time.Instant.now())
                        .append("] ")
                        .append(message)
                        .append('\n');
            }
            long now = System.currentTimeMillis();
            flush = now - lastFlush.getOrDefault(executionId, 0L) > 2000;
            if (flush) lastFlush.put(executionId, now);
        }
        if (flush) {
            String text = logText(executionId);
            try {
                transactionTemplate.executeWithoutResult(
                        status ->
                                executionRepository
                                        .findById(executionId)
                                        .ifPresent(
                                                exec -> {
                                                    exec.setLogs(text);
                                                    executionRepository.save(exec);
                                                }));
            } catch (Exception e) {
                log.warn("Could not save pipeline log: {}", e.getMessage());
            }
        }
    }

    private String logText(String executionId) {
        StringBuilder buffer = logBuffers.get(executionId);
        if (buffer == null) return null;
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    private boolean isCancelled(String executionId) {
        return cancelledExecutions.contains(executionId);
    }

    /** Runs that were in progress when the server stopped will never finish. */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void failInterruptedExecutions() {
        transactionTemplate.executeWithoutResult(
                status -> {
                    for (PipelineExecution exec : executionRepository.findAll()) {
                        if (exec.getStatus() == PipelineExecution.ExecutionStatus.RUNNING
                                || exec.getStatus() == PipelineExecution.ExecutionStatus.PENDING) {
                            exec.setStatus(PipelineExecution.ExecutionStatus.FAILED);
                            exec.setErrorMessage("Interrupted because Docker Manager restarted");
                            exec.setFinishedAt(System.currentTimeMillis());
                            executionRepository.save(exec);
                        }
                    }
                });
    }

    @Transactional
    public PipelineExecution cancelExecution(String executionId) {
        PipelineExecution execution =
                executionRepository
                        .findById(executionId)
                        .orElseThrow(
                                () -> new RuntimeException("Execution not found: " + executionId));

        if (execution.getStatus() == PipelineExecution.ExecutionStatus.PENDING) {
            execution.setStatus(PipelineExecution.ExecutionStatus.CANCELLED);
            execution.setFinishedAt(System.currentTimeMillis());
            appendLog(execution, "Execution cancelled");
            executionRepository.save(execution);
            broadcastLog(executionId, "Execution cancelled");
        } else if (execution.getStatus() == PipelineExecution.ExecutionStatus.RUNNING) {
            // The run stops its current step container and does not start new ones; it then
            // records itself as cancelled.
            cancelledExecutions.add(executionId);
            broadcastLog(executionId, "Cancelling...");
        }

        return execution;
    }

    private Map<String, String> buildEnvironmentVariables(ExecutionContext ctx) {
        Map<String, String> env = new HashMap<>();

        env.put("PIPELINE_ID", ctx.pipelineId);
        env.put("PIPELINE_NAME", ctx.pipelineName);
        env.put("BUILD_NUMBER", String.valueOf(ctx.buildNumber));
        env.put("EXECUTION_ID", ctx.executionId);

        if (ctx.hasGitRepo) {
            env.put("GIT_REPO_URL", ctx.gitRepoUrl);
            env.put("GIT_BRANCH", ctx.triggerBranch);
        }

        if (ctx.triggerCommit != null && ctx.triggerCommit.matches("^[0-9a-fA-F]{7,64}$")) {
            env.put("GIT_COMMIT", ctx.triggerCommit);
            env.put("GIT_COMMIT_SHORT", ctx.triggerCommit.substring(0, 7));
        }

        if (ctx.triggerType != null) {
            env.put("TRIGGER_TYPE", ctx.triggerType.name());
        }
        if (ctx.triggeredBy != null) {
            env.put("TRIGGERED_BY", ctx.triggeredBy);
        }

        return env;
    }

    private String getBranchFromPipeline(Pipeline pipeline) {
        if (pipeline.getGitRepository() != null) {
            return pipeline.getGitRepository().getBranch();
        }
        return "main";
    }

    public void registerEmitter(String executionId, SseEmitter emitter) {
        executionEmitters
                .computeIfAbsent(executionId, k -> ConcurrentHashMap.newKeySet())
                .add(emitter);
        emitter.onCompletion(() -> removeEmitter(executionId, emitter));
        emitter.onTimeout(() -> removeEmitter(executionId, emitter));
        emitter.onError(e -> removeEmitter(executionId, emitter));
        // The run may have finished between the caller's status check and registration.
        executionRepository
                .findById(executionId)
                .filter(
                        e ->
                                e.getStatus() != PipelineExecution.ExecutionStatus.RUNNING
                                        && e.getStatus()
                                                != PipelineExecution.ExecutionStatus.PENDING)
                .ifPresent(e -> completeEmitters(executionId));
    }

    private void removeEmitter(String executionId, SseEmitter emitter) {
        Set<SseEmitter> emitters = executionEmitters.get(executionId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }

    private void completeEmitters(String executionId) {
        Set<SseEmitter> emitters = executionEmitters.remove(executionId);
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
