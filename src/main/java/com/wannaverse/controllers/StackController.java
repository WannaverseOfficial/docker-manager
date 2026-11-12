package com.wannaverse.controllers;

import com.wannaverse.iac.ConfigLockInterceptor;
import com.wannaverse.iac.ConfigLocked;
import com.wannaverse.persistence.ManagedResourceRepository;
import com.wannaverse.persistence.Resource;
import com.wannaverse.persistence.Stack;
import com.wannaverse.security.AdminOnly;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.ComposeInspector;
import com.wannaverse.service.StackService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/docker/hosts/{hostId}/stacks")
/**
 * Compose files can do anything on the host (bind mounts, devices, privileged mode), so only admins
 * write them: create, edit, import, validate and roll back. Users with stack permissions run
 * actions on stacks an admin wrote.
 */
public class StackController {
    private final StackService stackService;
    private final ManagedResourceRepository managed;

    public StackController(StackService stackService, ManagedResourceRepository managed) {
        this.stackService = stackService;
        this.managed = managed;
    }

    @GetMapping
    @RequirePermission(resource = Resource.STACKS, action = "list", hostIdParam = "hostId")
    public List<StackService.StackSummary> list(@PathVariable String hostId) {
        return stackService.list(hostId);
    }

    @GetMapping("/{name}")
    @RequirePermission(
            resource = Resource.STACKS,
            action = "read",
            hostIdParam = "hostId",
            resourceIdParam = "name")
    public StackService.StackDetail get(@PathVariable String hostId, @PathVariable String name) {
        return stackService.get(hostId, name);
    }

    @AdminOnly
    @PostMapping
    @RequirePermission(resource = Resource.STACKS, action = "create", hostIdParam = "hostId")
    @Auditable(resource = Resource.STACKS, action = "create", captureRequestBody = true)
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String hostId, @RequestBody StackService.SaveRequest request) {
        Stack stack = stackService.create(hostId, request, username(), isAdmin());
        String jobId = null;
        if (request.deploy()) {
            jobId =
                    stackService.start(
                                    hostId, stack.getName(), StackService.Action.DEPLOY, username())
                            .id;
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result(stack.getName(), jobId));
    }

    @AdminOnly
    @PostMapping("/validate")
    @RequirePermission(resource = Resource.STACKS, action = "list", hostIdParam = "hostId")
    public ComposeInspector.Summary validate(
            @PathVariable String hostId, @RequestBody StackService.SaveRequest request) {
        return stackService.validate(request.composeContent(), isAdmin());
    }

    @AdminOnly
    @PutMapping("/{name}")
    @RequirePermission(
            resource = Resource.STACKS,
            action = "update",
            hostIdParam = "hostId",
            resourceIdParam = "name")
    @ConfigLocked(section = "stacks", id = "name", host = "hostId")
    @Auditable(
            resource = Resource.STACKS,
            action = "update",
            resourceIdParam = "name",
            captureRequestBody = true)
    public Map<String, Object> update(
            @PathVariable String hostId,
            @PathVariable String name,
            @RequestBody StackService.SaveRequest request) {
        stackService.update(hostId, name, request, isAdmin());
        String jobId = null;
        if (request.deploy()) {
            jobId = stackService.start(hostId, name, StackService.Action.DEPLOY, username()).id;
        }
        return result(name, jobId);
    }

    public record ImportRequest(String composeContent, String envContent, String projectDir) {}

    /** Finds a project's compose file and .env on the Docker host, ready to import. */
    @AdminOnly
    @GetMapping("/{name}/detect")
    public com.wannaverse.service.StackImportDetector.Detected detect(
            @PathVariable String hostId, @PathVariable String name) {
        return stackService.detectExternal(hostId, name);
    }

    /** Imports every project started outside Docker Manager whose compose file can be found. */
    @AdminOnly
    @PostMapping("/import-all")
    @Auditable(resource = Resource.STACKS, action = "import")
    public List<StackService.ImportResult> importAll(@PathVariable String hostId) {
        return stackService.importAll(hostId, username());
    }

    @AdminOnly
    @PostMapping("/{name}/import")
    @RequirePermission(resource = Resource.STACKS, action = "create", hostIdParam = "hostId")
    @Auditable(resource = Resource.STACKS, action = "import", resourceIdParam = "name")
    public Map<String, Object> importStack(
            @PathVariable String hostId,
            @PathVariable String name,
            @RequestBody ImportRequest request) {
        stackService.importExternal(
                hostId,
                name,
                request.composeContent(),
                request.envContent(),
                request.projectDir(),
                username(),
                isAdmin());
        return result(name, null);
    }

    /**
     * Runs an action: deploy, pull_and_deploy, start, stop, restart, down, remove or
     * remove_with_volumes. Returns a job to follow.
     */
    @PostMapping("/{name}/actions/{action}")
    @RequirePermission(
            resource = Resource.STACKS,
            action = "deploy",
            hostIdParam = "hostId",
            resourceIdParam = "name")
    @Auditable(resource = Resource.STACKS, action = "action", resourceIdParam = "name")
    public Map<String, Object> action(
            @PathVariable String hostId, @PathVariable String name, @PathVariable String action) {
        StackService.Action a;
        try {
            a = StackService.Action.valueOf(action.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action: " + action);
        }
        requireDeleteForRemove(a, hostId, name);
        if ((a == StackService.Action.REMOVE || a == StackService.Action.REMOVE_WITH_VOLUMES)
                && managed.existsBySectionAndTargetIdAndHostId("stacks", name, hostId)) {
            // Only the removal is locked: deploy, start and stop stay available.
            throw new ResponseStatusException(HttpStatus.CONFLICT, ConfigLockInterceptor.MESSAGE);
        }
        return result(name, stackService.start(hostId, name, a, username()).id);
    }

    @AdminOnly
    @PostMapping("/{name}/rollback/{version}")
    @RequirePermission(
            resource = Resource.STACKS,
            action = "deploy",
            hostIdParam = "hostId",
            resourceIdParam = "name")
    @ConfigLocked(section = "stacks", id = "name", host = "hostId")
    @Auditable(resource = Resource.STACKS, action = "rollback", resourceIdParam = "name")
    public Map<String, Object> rollback(
            @PathVariable String hostId, @PathVariable String name, @PathVariable int version) {
        return result(name, stackService.rollback(hostId, name, version, username(), isAdmin()).id);
    }

    @GetMapping("/{name}/logs")
    @RequirePermission(
            resource = Resource.STACKS,
            action = "logs",
            hostIdParam = "hostId",
            resourceIdParam = "name")
    public Map<String, String> logs(
            @PathVariable String hostId,
            @PathVariable String name,
            @RequestParam(defaultValue = "300") int tail) {
        return Map.of("logs", stackService.logs(hostId, name, tail));
    }

    @GetMapping("/jobs/{jobId}")
    @RequirePermission(resource = Resource.STACKS, action = "list", hostIdParam = "hostId")
    public Map<String, Object> job(
            @PathVariable String hostId,
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int from) {
        return stackService.job(jobId).view(from);
    }

    private void requireDeleteForRemove(StackService.Action a, String hostId, String name) {
        if ((a == StackService.Action.REMOVE || a == StackService.Action.REMOVE_WITH_VOLUMES)
                && !isAdmin()) {
            // Removing needs the delete permission too; checked here because the endpoint is
            // shared by all actions.
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Only administrators can remove stacks");
        }
    }

    private static Map<String, Object> result(String name, String jobId) {
        return jobId == null ? Map.of("name", name) : Map.of("name", name, "jobId", jobId);
    }

    private static boolean isAdmin() {
        var ctx = SecurityContextHolder.getContext();
        return ctx != null && ctx.isAdmin();
    }

    private static String username() {
        return SecurityContextHolder.getCurrentUsername();
    }
}
