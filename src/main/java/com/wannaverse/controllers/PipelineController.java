package com.wannaverse.controllers;

import com.wannaverse.dto.PipelineExecutionResponse;
import com.wannaverse.dto.PipelineRequest;
import com.wannaverse.dto.PipelineResponse;
import com.wannaverse.persistence.Pipeline;
import com.wannaverse.persistence.PipelineExecution;
import com.wannaverse.persistence.PipelineExecutionRepository;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.PipelineExecutionService;
import com.wannaverse.service.PipelineService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;
    private final PipelineExecutionService executionService;
    private final PipelineExecutionRepository executionRepository;

    public PipelineController(
            PipelineService pipelineService,
            PipelineExecutionService executionService,
            PipelineExecutionRepository executionRepository) {
        this.pipelineService = pipelineService;
        this.executionService = executionService;
        this.executionRepository = executionRepository;
    }

    @GetMapping
    @RequirePermission(resource = Resource.PIPELINES, action = "list")
    public ResponseEntity<List<PipelineResponse>> listPipelines() {
        return ResponseEntity.ok(pipelineService.listPipelines());
    }

    @GetMapping("/hosts/{hostId}")
    @RequirePermission(resource = Resource.PIPELINES, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<PipelineResponse>> listPipelinesByHost(@PathVariable String hostId) {
        return ResponseEntity.ok(pipelineService.listPipelinesByDockerHost(hostId));
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = Resource.PIPELINES, action = "read")
    public ResponseEntity<PipelineResponse> getPipeline(@PathVariable String id) {
        return ResponseEntity.ok(pipelineService.getPipeline(id));
    }

    @PostMapping
    @RequirePermission(resource = Resource.PIPELINES, action = "create")
    public ResponseEntity<PipelineResponse> createPipeline(
            @RequestBody PipelineRequest request, Authentication authentication) {
        String createdBy = authentication != null ? authentication.getName() : "system";
        PipelineResponse response = pipelineService.createPipeline(request, createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = Resource.PIPELINES, action = "update")
    public ResponseEntity<PipelineResponse> updatePipeline(
            @PathVariable String id, @RequestBody PipelineRequest request) {
        return ResponseEntity.ok(pipelineService.updatePipeline(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = Resource.PIPELINES, action = "delete")
    public ResponseEntity<Void> deletePipeline(@PathVariable String id) {
        pipelineService.deletePipeline(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/duplicate")
    @RequirePermission(resource = Resource.PIPELINES, action = "create")
    public ResponseEntity<PipelineResponse> duplicatePipeline(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        String newName = body != null ? body.get("name") : null;
        String createdBy = authentication != null ? authentication.getName() : "system";
        PipelineResponse response = pipelineService.duplicatePipeline(id, newName, createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/enabled")
    @RequirePermission(resource = Resource.PIPELINES, action = "update")
    public ResponseEntity<PipelineResponse> togglePipelineEnabled(
            @PathVariable String id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        return ResponseEntity.ok(pipelineService.toggleEnabled(id, enabled));
    }

    @PostMapping("/{id}/trigger")
    @RequirePermission(resource = Resource.PIPELINE_EXECUTIONS, action = "trigger")
    public ResponseEntity<PipelineExecutionResponse> triggerExecution(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        String commit = body != null ? body.get("commit") : null;
        String branch = body != null ? body.get("branch") : null;
        String triggeredBy = authentication != null ? authentication.getName() : "system";

        PipelineExecution execution =
                executionService.triggerExecution(
                        id, PipelineExecution.TriggerType.MANUAL, commit, branch, triggeredBy);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(PipelineExecutionResponse.fromEntity(execution));
    }

    @GetMapping("/{id}/executions")
    @RequirePermission(resource = Resource.PIPELINE_EXECUTIONS, action = "list")
    @Transactional(readOnly = true)
    public ResponseEntity<List<PipelineExecutionResponse>> listExecutions(@PathVariable String id) {
        List<PipelineExecutionResponse> executions =
                executionRepository.findByPipelineIdOrderByCreatedAtDesc(id).stream()
                        .map(PipelineExecutionResponse::fromEntity)
                        .collect(Collectors.toList());
        return ResponseEntity.ok(executions);
    }

    @GetMapping("/executions/{executionId}")
    @RequirePermission(resource = Resource.PIPELINE_EXECUTIONS, action = "read")
    @Transactional(readOnly = true)
    public ResponseEntity<PipelineExecutionResponse> getExecution(
            @PathVariable String executionId) {
        PipelineExecution execution =
                executionRepository
                        .findById(executionId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Execution not found"));
        return ResponseEntity.ok(PipelineExecutionResponse.fromEntity(execution));
    }

    @PostMapping("/executions/{executionId}/cancel")
    @RequirePermission(resource = Resource.PIPELINE_EXECUTIONS, action = "cancel")
    public ResponseEntity<PipelineExecutionResponse> cancelExecution(
            @PathVariable String executionId) {
        PipelineExecution execution = executionService.cancelExecution(executionId);
        return ResponseEntity.ok(PipelineExecutionResponse.fromEntity(execution));
    }

    @GetMapping("/executions/{executionId}/logs")
    @RequirePermission(resource = Resource.PIPELINE_EXECUTIONS, action = "logs")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, String>> getExecutionLogs(@PathVariable String executionId) {
        PipelineExecution execution =
                executionRepository
                        .findById(executionId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Execution not found"));
        return ResponseEntity.ok(
                Map.of("logs", execution.getLogs() != null ? execution.getLogs() : ""));
    }

    @GetMapping(
            value = "/executions/{executionId}/logs/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission(resource = Resource.PIPELINE_EXECUTIONS, action = "stream_logs")
    public SseEmitter streamExecutionLogs(@PathVariable String executionId) {
        PipelineExecution execution =
                executionRepository
                        .findById(executionId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Execution not found"));

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        if (execution.getLogs() != null && !execution.getLogs().isEmpty()) {
            try {
                for (String line : execution.getLogs().split("\n")) {
                    emitter.send(SseEmitter.event().name("log").data(line));
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
                return emitter;
            }
        }

        if (execution.getStatus() == PipelineExecution.ExecutionStatus.SUCCESS
                || execution.getStatus() == PipelineExecution.ExecutionStatus.FAILED
                || execution.getStatus() == PipelineExecution.ExecutionStatus.CANCELLED) {
            try {
                emitter.send(SseEmitter.event().name("complete").data("done"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        } else {
            executionService.registerEmitter(executionId, emitter);
        }

        return emitter;
    }

    @PostMapping("/webhook/{secret}")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @PathVariable String secret,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event) {

        if (!"push".equals(event)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "not a push event"));
        }

        Pipeline pipeline;
        try {
            pipeline = pipelineService.findByWebhookSecret(secret);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid webhook secret");
        }

        if (!pipeline.isEnabled() || !pipeline.isWebhookEnabled()) {
            return ResponseEntity.ok(
                    Map.of("status", "ignored", "reason", "pipeline or webhook disabled"));
        }

        String commit = payload.get("after") != null ? payload.get("after").toString() : null;
        String ref = payload.get("ref") != null ? payload.get("ref").toString() : null;
        String branch = ref != null ? ref.replace("refs/heads/", "") : null;

        if (pipeline.getBranchFilter() != null && !pipeline.getBranchFilter().isEmpty()) {
            if (branch == null || !branch.matches(pipeline.getBranchFilter())) {
                return ResponseEntity.ok(
                        Map.of("status", "ignored", "reason", "branch does not match filter"));
            }
        }

        PipelineExecution execution =
                executionService.triggerExecution(
                        pipeline.getId(),
                        PipelineExecution.TriggerType.WEBHOOK,
                        commit,
                        branch,
                        "webhook");

        return ResponseEntity.ok(Map.of("status", "triggered", "executionId", execution.getId()));
    }
}
