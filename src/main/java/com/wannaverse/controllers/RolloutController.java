package com.wannaverse.controllers;

import com.wannaverse.dto.RolloutRequest;
import com.wannaverse.persistence.Resource;
import com.wannaverse.persistence.Rollout;
import com.wannaverse.persistence.RolloutRun;
import com.wannaverse.security.AdminOnly;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.RolloutRunner;
import com.wannaverse.service.RolloutService;
import com.wannaverse.service.RolloutService.RolloutView;
import com.wannaverse.service.RolloutService.RunView;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Blue/green and canary releases. Settings are admin-only, like Git repositories and pipelines. */
@RestController
@RequestMapping("/api/rollouts")
public class RolloutController {
    private final RolloutService service;
    private final RolloutRunner runner;

    public RolloutController(RolloutService service, RolloutRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    @GetMapping
    @RequirePermission(resource = Resource.ROLLOUTS, action = "list")
    public List<RolloutView> list(@RequestParam(required = false) String hostId) {
        return service.list(hostId);
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = Resource.ROLLOUTS, action = "read")
    public RolloutView get(@PathVariable String id) {
        return service.view(service.require(id), true);
    }

    @PostMapping
    @AdminOnly
    @RequirePermission(resource = Resource.ROLLOUTS, action = "create")
    @Auditable(resource = Resource.ROLLOUTS, action = "create")
    public ResponseEntity<RolloutView> create(@RequestBody RolloutRequest request) {
        Rollout r = service.create(request, SecurityContextHolder.getCurrentUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.view(r, true));
    }

    @PutMapping("/{id}")
    @AdminOnly
    @RequirePermission(resource = Resource.ROLLOUTS, action = "update")
    @Auditable(resource = Resource.ROLLOUTS, action = "update", resourceIdParam = "id")
    public RolloutView update(@PathVariable String id, @RequestBody RolloutRequest request) {
        return service.view(service.update(id, request), true);
    }

    @DeleteMapping("/{id}")
    @AdminOnly
    @RequirePermission(resource = Resource.ROLLOUTS, action = "delete")
    @Auditable(resource = Resource.ROLLOUTS, action = "delete", resourceIdParam = "id")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- releases ----------------------------------------------------------------------------

    public record ReleaseRequest(String image) {}

    @PostMapping("/{id}/release")
    @RequirePermission(resource = Resource.ROLLOUTS, action = "release")
    @Auditable(
            resource = Resource.ROLLOUTS,
            action = "release",
            resourceIdParam = "id",
            captureRequestBody = true)
    public ResponseEntity<RunView> release(
            @PathVariable String id, @RequestBody ReleaseRequest request) {
        RolloutRun run =
                runner.release(
                        service.require(id),
                        request == null ? null : request.image(),
                        RolloutRun.Trigger.MANUAL,
                        SecurityContextHolder.getCurrentUsername(),
                        null);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.runView(run, false));
    }

    @PostMapping("/{id}/rollback")
    @RequirePermission(resource = Resource.ROLLOUTS, action = "rollback")
    @Auditable(resource = Resource.ROLLOUTS, action = "rollback", resourceIdParam = "id")
    public ResponseEntity<RunView> rollback(@PathVariable String id) {
        RolloutRun run =
                runner.rollback(service.require(id), SecurityContextHolder.getCurrentUsername());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.runView(run, false));
    }

    @GetMapping("/{id}/runs")
    @RequirePermission(resource = Resource.ROLLOUTS, action = "read")
    public List<RunView> runs(@PathVariable String id) {
        return service.runs(id);
    }

    @GetMapping("/{id}/runs/{runId}")
    @RequirePermission(resource = Resource.ROLLOUTS, action = "read")
    public RunView run(@PathVariable String id, @PathVariable String runId) {
        return service.run(id, runId);
    }

    public record ApprovalRequest(boolean approved) {}

    @PostMapping("/{id}/runs/{runId}/approve")
    @RequirePermission(resource = Resource.ROLLOUTS, action = "approve")
    @Auditable(
            resource = Resource.ROLLOUTS,
            action = "approve",
            resourceIdParam = "runId",
            captureRequestBody = true)
    public ResponseEntity<Void> approve(
            @PathVariable String id,
            @PathVariable String runId,
            @RequestBody ApprovalRequest request) {
        service.requireRun(id, runId);
        runner.approve(
                runId,
                request != null && request.approved(),
                SecurityContextHolder.getCurrentUsername());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/runs/{runId}/promote")
    @RequirePermission(resource = Resource.ROLLOUTS, action = "promote")
    @Auditable(resource = Resource.ROLLOUTS, action = "promote", resourceIdParam = "runId")
    public ResponseEntity<Void> promote(@PathVariable String id, @PathVariable String runId) {
        service.requireRun(id, runId);
        runner.promote(runId);
        return ResponseEntity.noContent().build();
    }

    /** Stops a release; visitors go back to the version that served before it. */
    @PostMapping("/{id}/runs/{runId}/abort")
    @RequirePermission(resource = Resource.ROLLOUTS, action = "rollback")
    @Auditable(resource = Resource.ROLLOUTS, action = "abort", resourceIdParam = "runId")
    public ResponseEntity<Void> abort(@PathVariable String id, @PathVariable String runId) {
        service.requireRun(id, runId);
        runner.abort(runId, SecurityContextHolder.getCurrentUsername());
        return ResponseEntity.noContent().build();
    }
}
