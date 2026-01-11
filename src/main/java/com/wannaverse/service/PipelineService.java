package com.wannaverse.service;

import com.wannaverse.dto.PipelineRequest;
import com.wannaverse.dto.PipelineResponse;
import com.wannaverse.persistence.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PipelineService {
    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineRepository pipelineRepository;
    private final PipelineExecutionRepository executionRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final DockerHostRepository dockerHostRepository;

    public PipelineService(
            PipelineRepository pipelineRepository,
            PipelineExecutionRepository executionRepository,
            GitRepositoryRepository gitRepositoryRepository,
            DockerHostRepository dockerHostRepository) {
        this.pipelineRepository = pipelineRepository;
        this.executionRepository = executionRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.dockerHostRepository = dockerHostRepository;
    }

    public List<PipelineResponse> listPipelines() {
        return pipelineRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponseWithStats)
                .collect(Collectors.toList());
    }

    public List<PipelineResponse> listPipelinesByDockerHost(String dockerHostId) {
        return pipelineRepository.findByDockerHostIdOrderByCreatedAtDesc(dockerHostId).stream()
                .map(this::toResponseWithStats)
                .collect(Collectors.toList());
    }

    public PipelineResponse getPipeline(String id) {
        Pipeline pipeline =
                pipelineRepository
                        .findById(id)
                        .orElseThrow(() -> new RuntimeException("Pipeline not found: " + id));
        return toResponseWithStats(pipeline);
    }

    @Transactional
    public PipelineResponse createPipeline(PipelineRequest request, String createdBy) {
        log.info("Creating pipeline: {}", request.getName());

        DockerHost dockerHost =
                dockerHostRepository
                        .findById(request.getDockerHostId())
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                "Docker host not found: "
                                                        + request.getDockerHostId()));

        Pipeline pipeline = new Pipeline();
        pipeline.setName(request.getName());
        pipeline.setDescription(request.getDescription());
        pipeline.setDockerHost(dockerHost);
        pipeline.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        pipeline.setWebhookEnabled(
                request.getWebhookEnabled() != null ? request.getWebhookEnabled() : true);
        pipeline.setPollingEnabled(
                request.getPollingEnabled() != null ? request.getPollingEnabled() : false);
        pipeline.setPollingIntervalSeconds(
                request.getPollingIntervalSeconds() != null
                        ? request.getPollingIntervalSeconds()
                        : 300);
        pipeline.setBranchFilter(request.getBranchFilter());
        pipeline.setGraphLayout(request.getGraphLayout());
        pipeline.setCreatedBy(createdBy);

        // Generate webhook secret
        pipeline.setWebhookSecret(UUID.randomUUID().toString().replace("-", ""));

        // Set git repository if provided
        if (request.getGitRepositoryId() != null && !request.getGitRepositoryId().isEmpty()) {
            GitRepository gitRepo =
                    gitRepositoryRepository
                            .findById(request.getGitRepositoryId())
                            .orElseThrow(
                                    () ->
                                            new RuntimeException(
                                                    "Git repository not found: "
                                                            + request.getGitRepositoryId()));
            pipeline.setGitRepository(gitRepo);
        }

        // Create stages and steps
        if (request.getStages() != null) {
            List<PipelineStage> stages = new ArrayList<>();
            for (PipelineRequest.StageRequest stageReq : request.getStages()) {
                PipelineStage stage = createStageFromRequest(stageReq, pipeline);
                stages.add(stage);
            }
            pipeline.setStages(stages);
        }

        pipeline = pipelineRepository.save(pipeline);
        log.info("Created pipeline: {} with id: {}", pipeline.getName(), pipeline.getId());

        return PipelineResponse.fromEntity(pipeline);
    }

    @Transactional
    public PipelineResponse updatePipeline(String id, PipelineRequest request) {
        log.info("Updating pipeline: {}", id);

        Pipeline pipeline =
                pipelineRepository
                        .findById(id)
                        .orElseThrow(() -> new RuntimeException("Pipeline not found: " + id));

        pipeline.setName(request.getName());
        pipeline.setDescription(request.getDescription());
        pipeline.setEnabled(
                request.getEnabled() != null ? request.getEnabled() : pipeline.isEnabled());
        pipeline.setWebhookEnabled(
                request.getWebhookEnabled() != null
                        ? request.getWebhookEnabled()
                        : pipeline.isWebhookEnabled());
        pipeline.setPollingEnabled(
                request.getPollingEnabled() != null
                        ? request.getPollingEnabled()
                        : pipeline.isPollingEnabled());
        pipeline.setPollingIntervalSeconds(
                request.getPollingIntervalSeconds() != null
                        ? request.getPollingIntervalSeconds()
                        : pipeline.getPollingIntervalSeconds());
        pipeline.setBranchFilter(request.getBranchFilter());
        pipeline.setGraphLayout(request.getGraphLayout());

        // Update docker host if changed
        if (request.getDockerHostId() != null
                && !request.getDockerHostId().equals(pipeline.getDockerHost().getId())) {
            DockerHost dockerHost =
                    dockerHostRepository
                            .findById(request.getDockerHostId())
                            .orElseThrow(
                                    () ->
                                            new RuntimeException(
                                                    "Docker host not found: "
                                                            + request.getDockerHostId()));
            pipeline.setDockerHost(dockerHost);
        }

        // Update git repository if changed
        if (request.getGitRepositoryId() != null) {
            if (pipeline.getGitRepository() == null
                    || !request.getGitRepositoryId().equals(pipeline.getGitRepository().getId())) {
                GitRepository gitRepo =
                        gitRepositoryRepository
                                .findById(request.getGitRepositoryId())
                                .orElseThrow(
                                        () ->
                                                new RuntimeException(
                                                        "Git repository not found: "
                                                                + request.getGitRepositoryId()));
                pipeline.setGitRepository(gitRepo);
            }
        } else {
            pipeline.setGitRepository(null);
        }

        // Update stages - clear and recreate for simplicity
        if (request.getStages() != null) {
            pipeline.getStages().clear();
            for (PipelineRequest.StageRequest stageReq : request.getStages()) {
                PipelineStage stage = createStageFromRequest(stageReq, pipeline);
                pipeline.getStages().add(stage);
            }
        }

        pipeline = pipelineRepository.save(pipeline);
        log.info("Updated pipeline: {}", pipeline.getName());

        return PipelineResponse.fromEntity(pipeline);
    }

    @Transactional
    public void deletePipeline(String id) {
        log.info("Deleting pipeline: {}", id);
        Pipeline pipeline =
                pipelineRepository
                        .findById(id)
                        .orElseThrow(() -> new RuntimeException("Pipeline not found: " + id));
        pipelineRepository.delete(pipeline);
        log.info("Deleted pipeline: {}", id);
    }

    @Transactional
    public PipelineResponse duplicatePipeline(String id, String newName, String createdBy) {
        log.info("Duplicating pipeline: {}", id);

        Pipeline original =
                pipelineRepository
                        .findById(id)
                        .orElseThrow(() -> new RuntimeException("Pipeline not found: " + id));

        Pipeline copy = new Pipeline();
        copy.setName(newName != null ? newName : original.getName() + " (copy)");
        copy.setDescription(original.getDescription());
        copy.setDockerHost(original.getDockerHost());
        copy.setGitRepository(original.getGitRepository());
        copy.setEnabled(false); // Start disabled
        copy.setWebhookEnabled(original.isWebhookEnabled());
        copy.setPollingEnabled(original.isPollingEnabled());
        copy.setPollingIntervalSeconds(original.getPollingIntervalSeconds());
        copy.setBranchFilter(original.getBranchFilter());
        copy.setGraphLayout(original.getGraphLayout());
        copy.setCreatedBy(createdBy);
        copy.setWebhookSecret(UUID.randomUUID().toString().replace("-", ""));

        // Copy stages and steps - first pass: create stages without dependencies
        List<PipelineStage> stages = new ArrayList<>();
        java.util.Map<String, PipelineStage> oldIdToNewStage = new java.util.HashMap<>();

        for (PipelineStage originalStage : original.getStages()) {
            PipelineStage stageCopy = new PipelineStage();
            stageCopy.setPipeline(copy);
            stageCopy.setName(originalStage.getName());
            stageCopy.setOrderIndex(originalStage.getOrderIndex());
            stageCopy.setExecutionMode(originalStage.getExecutionMode());
            stageCopy.setPositionX(originalStage.getPositionX());
            stageCopy.setPositionY(originalStage.getPositionY());
            stageCopy.setStopOnFailure(originalStage.isStopOnFailure());
            // Dependencies will be mapped after all stages are created

            List<PipelineStep> steps = new ArrayList<>();
            for (PipelineStep originalStep : originalStage.getSteps()) {
                PipelineStep stepCopy = new PipelineStep();
                stepCopy.setStage(stageCopy);
                stepCopy.setName(originalStep.getName());
                stepCopy.setOrderIndex(originalStep.getOrderIndex());
                stepCopy.setStepType(originalStep.getStepType());
                stepCopy.setConfiguration(originalStep.getConfiguration());
                stepCopy.setWorkingDirectory(originalStep.getWorkingDirectory());
                stepCopy.setTimeoutSeconds(originalStep.getTimeoutSeconds());
                stepCopy.setContinueOnFailure(originalStep.isContinueOnFailure());
                stepCopy.setEnvironmentVariables(originalStep.getEnvironmentVariables());
                stepCopy.setArtifactInputPattern(originalStep.getArtifactInputPattern());
                stepCopy.setArtifactOutputPattern(originalStep.getArtifactOutputPattern());
                stepCopy.setPositionX(originalStep.getPositionX());
                stepCopy.setPositionY(originalStep.getPositionY());
                steps.add(stepCopy);
            }
            stageCopy.setSteps(steps);
            stages.add(stageCopy);
            oldIdToNewStage.put(originalStage.getId(), stageCopy);
        }

        // Second pass: remap dependencies to new stage IDs
        // Since new stages don't have IDs yet, we'll use orderIndex as a reference
        java.util.Map<String, Integer> oldIdToOrderIndex = new java.util.HashMap<>();
        for (PipelineStage originalStage : original.getStages()) {
            oldIdToOrderIndex.put(originalStage.getId(), originalStage.getOrderIndex());
        }

        for (int i = 0; i < original.getStages().size(); i++) {
            PipelineStage originalStage = original.getStages().get(i);
            PipelineStage stageCopy = stages.get(i);

            if (originalStage.getDependsOn() != null && !originalStage.getDependsOn().isEmpty()) {
                // For now, we'll clear dependencies since IDs will change after save
                // The visual editor will need to re-establish dependencies
                stageCopy.setDependsOn(new ArrayList<>());
            }
        }

        copy.setStages(stages);

        copy = pipelineRepository.save(copy);
        log.info("Duplicated pipeline {} to {}", id, copy.getId());

        return PipelineResponse.fromEntity(copy);
    }

    @Transactional
    public PipelineResponse toggleEnabled(String id, boolean enabled) {
        Pipeline pipeline =
                pipelineRepository
                        .findById(id)
                        .orElseThrow(() -> new RuntimeException("Pipeline not found: " + id));
        pipeline.setEnabled(enabled);
        pipeline = pipelineRepository.save(pipeline);
        return PipelineResponse.fromEntity(pipeline);
    }

    public Pipeline findByWebhookSecret(String secret) {
        return pipelineRepository
                .findByWebhookSecret(secret)
                .orElseThrow(() -> new RuntimeException("Pipeline not found for webhook"));
    }

    public List<Pipeline> findEnabledPollingPipelines() {
        return pipelineRepository.findEnabledPollingPipelines();
    }

    private PipelineStage createStageFromRequest(
            PipelineRequest.StageRequest stageReq, Pipeline pipeline) {
        PipelineStage stage = new PipelineStage();
        stage.setPipeline(pipeline);
        stage.setName(stageReq.getName());
        stage.setOrderIndex(stageReq.getOrderIndex() != null ? stageReq.getOrderIndex() : 0);
        stage.setExecutionMode(
                stageReq.getExecutionMode() != null
                        ? stageReq.getExecutionMode()
                        : PipelineStage.ExecutionMode.SEQUENTIAL);
        stage.setPositionX(stageReq.getPositionX() != null ? stageReq.getPositionX() : 0);
        stage.setPositionY(stageReq.getPositionY() != null ? stageReq.getPositionY() : 0);
        stage.setDependsOn(
                stageReq.getDependsOn() != null ? stageReq.getDependsOn() : new ArrayList<>());
        stage.setStopOnFailure(
                stageReq.getStopOnFailure() != null ? stageReq.getStopOnFailure() : true);

        if (stageReq.getSteps() != null) {
            List<PipelineStep> steps = new ArrayList<>();
            for (PipelineRequest.StepRequest stepReq : stageReq.getSteps()) {
                PipelineStep step = createStepFromRequest(stepReq, stage);
                steps.add(step);
            }
            stage.setSteps(steps);
        }

        return stage;
    }

    private PipelineStep createStepFromRequest(
            PipelineRequest.StepRequest stepReq, PipelineStage stage) {
        PipelineStep step = new PipelineStep();
        step.setStage(stage);
        step.setName(stepReq.getName());
        step.setOrderIndex(stepReq.getOrderIndex() != null ? stepReq.getOrderIndex() : 0);
        step.setStepType(
                stepReq.getStepType() != null
                        ? stepReq.getStepType()
                        : PipelineStep.StepType.SHELL);
        step.setConfiguration(
                stepReq.getConfiguration() != null ? stepReq.getConfiguration() : "{}");
        step.setWorkingDirectory(stepReq.getWorkingDirectory());
        step.setTimeoutSeconds(
                stepReq.getTimeoutSeconds() != null && stepReq.getTimeoutSeconds() > 0
                        ? stepReq.getTimeoutSeconds()
                        : 3600);
        step.setContinueOnFailure(
                stepReq.getContinueOnFailure() != null ? stepReq.getContinueOnFailure() : false);
        step.setEnvironmentVariables(stepReq.getEnvironmentVariables());
        step.setArtifactInputPattern(stepReq.getArtifactInputPattern());
        step.setArtifactOutputPattern(stepReq.getArtifactOutputPattern());
        step.setPositionX(stepReq.getPositionX() != null ? stepReq.getPositionX() : 0);
        step.setPositionY(stepReq.getPositionY() != null ? stepReq.getPositionY() : 0);
        return step;
    }

    private PipelineResponse toResponseWithStats(Pipeline pipeline) {
        PipelineResponse response = PipelineResponse.fromEntity(pipeline);

        // Add execution stats
        List<PipelineExecution> recentExecutions =
                executionRepository.findTop10ByPipelineIdOrderByCreatedAtDesc(pipeline.getId());

        if (!recentExecutions.isEmpty()) {
            PipelineExecution latest = recentExecutions.get(0);
            response.setLastExecutionAt(latest.getCreatedAt());
            response.setLastExecutionStatus(latest.getStatus());
        }

        long successCount =
                executionRepository.countByPipelineIdAndStatus(
                        pipeline.getId(), PipelineExecution.ExecutionStatus.SUCCESS);
        long failedCount =
                executionRepository.countByPipelineIdAndStatus(
                        pipeline.getId(), PipelineExecution.ExecutionStatus.FAILED);

        response.setSuccessfulExecutions((int) successCount);
        response.setFailedExecutions((int) failedCount);
        response.setTotalExecutions((int) (successCount + failedCount));

        return response;
    }
}
