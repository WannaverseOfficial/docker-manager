package com.wannaverse.controllers;

import com.wannaverse.persistence.ObservabilityStack;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.AdminOnly;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.ObservabilityService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The observability stack of a host (Grafana, Prometheus, Mimir, Loki, Tempo and collectors).
 * Administrators only: Grafana shows every container's logs and metrics.
 */
@RestController
@AdminOnly
@RequestMapping("/api/observability/hosts/{hostId}")
public class ObservabilityController {
    private final ObservabilityService observability;

    public ObservabilityController(ObservabilityService observability) {
        this.observability = observability;
    }

    /** {@code view} is null when the host has no stack yet. */
    public record Status(ObservabilityService.StackView view) {}

    @GetMapping
    public Status get(@PathVariable String hostId) {
        return new Status(observability.get(hostId).orElse(null));
    }

    @PostMapping
    @Auditable(
            resource = Resource.OBSERVABILITY,
            action = "create_observability_stack",
            captureRequestBody = true)
    public ObservabilityStack create(
            @PathVariable String hostId, @RequestBody ObservabilityService.Settings settings) {
        return observability.create(hostId, settings);
    }

    @PutMapping
    @Auditable(
            resource = Resource.OBSERVABILITY,
            action = "change_observability_stack",
            captureRequestBody = true)
    public ObservabilityStack update(
            @PathVariable String hostId, @RequestBody ObservabilityService.Settings settings) {
        return observability.update(hostId, settings);
    }

    @PostMapping("/repair")
    @Auditable(
            resource = Resource.OBSERVABILITY,
            action = "repair_observability_stack",
            resourceIdParam = "hostId")
    public ResponseEntity<Void> repair(@PathVariable String hostId) {
        observability.repair(hostId);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping
    @Auditable(
            resource = Resource.OBSERVABILITY,
            action = "remove_observability_stack",
            resourceIdParam = "hostId")
    public ResponseEntity<Void> remove(
            @PathVariable String hostId, @RequestParam(defaultValue = "false") boolean deleteData) {
        observability.remove(hostId, deleteData);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/password")
    @Auditable(
            resource = Resource.OBSERVABILITY,
            action = "reveal_grafana_password",
            resourceIdParam = "hostId")
    public Map<String, String> password(@PathVariable String hostId) {
        return Map.of("password", observability.adminPassword(hostId));
    }

    /** A signed token for Grafana, and where the browser posts it. */
    @PostMapping("/sign-in")
    @Auditable(
            resource = Resource.OBSERVABILITY,
            action = "open_grafana",
            resourceIdParam = "hostId")
    public ObservabilityService.SignIn signIn(@PathVariable String hostId) {
        return observability.signIn(hostId, SecurityContextHolder.getCurrentUserId());
    }

    // --- apps sending OpenTelemetry ----------------------------------------------------------

    @GetMapping("/members")
    public List<ObservabilityService.Member> members(@PathVariable String hostId) {
        return observability.members(hostId);
    }

    public record MemberRequest(String container) {}

    @PostMapping("/members")
    @Auditable(
            resource = Resource.OBSERVABILITY,
            action = "connect_to_otlp",
            captureRequestBody = true)
    public ResponseEntity<Void> connect(
            @PathVariable String hostId, @RequestBody MemberRequest request) {
        observability.connect(hostId, request.container());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/members/{container}")
    @Auditable(
            resource = Resource.OBSERVABILITY,
            action = "disconnect_from_otlp",
            resourceIdParam = "container")
    public ResponseEntity<Void> disconnect(
            @PathVariable String hostId, @PathVariable String container) {
        observability.disconnect(hostId, container);
        return ResponseEntity.noContent().build();
    }
}
