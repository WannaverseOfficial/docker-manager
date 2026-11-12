package com.wannaverse.controllers;

import com.wannaverse.iac.ConfigLocked;
import com.wannaverse.persistence.HostedRegistry;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.AdminOnly;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.RegistryHostingService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The private Docker registry Docker Manager can run on each host. */
@RestController
@AdminOnly
@RequestMapping("/api/hosted-registry/hosts/{hostId}")
public class HostedRegistryController {
    private final RegistryHostingService service;

    public HostedRegistryController(RegistryHostingService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable String hostId) {
        Map<String, Object> body = new LinkedHashMap<>();
        HostedRegistry r = service.get(hostId).orElse(null);
        body.put("registry", r);
        if (r != null) {
            body.put("address", RegistryHostingService.address(r));
            body.put("users", service.users(hostId));
            body.put("certificate", service.certificate(r));
        }
        return body;
    }

    public record CreateRequest(
            String mode, String hostname, Integer port, String username, String password) {}

    @ConfigLocked(section = "hostedRegistries", id = "hostId", host = "hostId")
    @PostMapping
    @Auditable(resource = Resource.REGISTRIES, action = "create_hosted_registry")
    public HostedRegistry create(@PathVariable String hostId, @RequestBody CreateRequest request) {
        return service.create(
                hostId,
                request.mode(),
                request.hostname(),
                request.port(),
                request.username(),
                request.password(),
                SecurityContextHolder.getCurrentUserId(),
                SecurityContextHolder.getCurrentUsername());
    }

    @ConfigLocked(section = "hostedRegistries", id = "hostId", host = "hostId")
    @DeleteMapping
    @Auditable(resource = Resource.REGISTRIES, action = "remove_hosted_registry")
    public ResponseEntity<Void> remove(
            @PathVariable String hostId,
            @RequestParam(defaultValue = "false") boolean deleteImages) {
        service.remove(
                hostId,
                deleteImages,
                SecurityContextHolder.getCurrentUserId(),
                SecurityContextHolder.getCurrentUsername());
        return ResponseEntity.noContent().build();
    }

    public record UserRequest(String username, String password) {}

    @ConfigLocked(section = "hostedRegistries", id = "hostId", host = "hostId")
    @PutMapping("/users")
    @Auditable(resource = Resource.REGISTRIES, action = "set_hosted_registry_user")
    public ResponseEntity<Void> setUser(
            @PathVariable String hostId, @RequestBody UserRequest request) {
        service.setUser(hostId, request.username(), request.password());
        return ResponseEntity.noContent().build();
    }

    @ConfigLocked(section = "hostedRegistries", id = "hostId", host = "hostId")
    @DeleteMapping("/users/{username}")
    @Auditable(resource = Resource.REGISTRIES, action = "remove_hosted_registry_user")
    public ResponseEntity<Void> removeUser(
            @PathVariable String hostId, @PathVariable String username) {
        service.removeUser(hostId, username);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/repositories")
    public List<RegistryHostingService.Repository> repositories(@PathVariable String hostId) {
        return service.repositories(hostId);
    }

    @GetMapping("/tag")
    public RegistryHostingService.Tag tag(
            @PathVariable String hostId, @RequestParam String repo, @RequestParam String tag) {
        return service.tag(hostId, repo, tag);
    }

    @DeleteMapping("/tag")
    @Auditable(resource = Resource.REGISTRIES, action = "delete_hosted_registry_tag")
    public ResponseEntity<Void> deleteTag(
            @PathVariable String hostId, @RequestParam String repo, @RequestParam String tag) {
        service.deleteTag(hostId, repo, tag);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reclaim")
    @Auditable(resource = Resource.REGISTRIES, action = "reclaim_hosted_registry_space")
    public Map<String, String> reclaim(@PathVariable String hostId) {
        return Map.of("result", service.reclaimSpace(hostId));
    }
}
