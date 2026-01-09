package com.wannaverse.controllers;

import com.wannaverse.persistence.DockerRegistry;
import com.wannaverse.persistence.DockerRegistry.RegistryType;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.DockerRegistryService;
import com.wannaverse.service.DockerRegistryService.RegistryCreateRequest;
import com.wannaverse.service.DockerRegistryService.RegistryUpdateRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/registries")
public class DockerRegistryController {

    private final DockerRegistryService registryService;

    public DockerRegistryController(DockerRegistryService registryService) {
        this.registryService = registryService;
    }

    @GetMapping
    @RequirePermission(resource = Resource.REGISTRIES, action = "list")
    public ResponseEntity<List<RegistryResponse>> listRegistries() {
        List<DockerRegistry> registries = registryService.getAllRegistries();
        List<RegistryResponse> responses =
                registries.stream().map(RegistryResponse::fromEntity).toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = Resource.REGISTRIES, action = "read")
    public ResponseEntity<RegistryResponse> getRegistry(@PathVariable String id) {
        return registryService
                .getRegistry(id)
                .map(r -> ResponseEntity.ok(RegistryResponse.fromEntity(r)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @RequirePermission(resource = Resource.REGISTRIES, action = "create")
    public ResponseEntity<RegistryResponse> createRegistry(
            @RequestBody CreateRegistryRequest request) {
        RegistryCreateRequest createRequest =
                new RegistryCreateRequest(
                        request.name(),
                        request.url(),
                        request.registryType(),
                        request.username(),
                        request.password(),
                        request.awsRegion(),
                        request.awsAccessKeyId(),
                        request.awsSecretKey(),
                        request.gcpProjectId(),
                        request.gcpServiceAccountJson(),
                        request.azureClientId(),
                        request.azureClientSecret(),
                        request.azureTenantId(),
                        request.isDefault() != null && request.isDefault());

        DockerRegistry registry = registryService.createRegistry(createRequest);
        return ResponseEntity.ok(RegistryResponse.fromEntity(registry));
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = Resource.REGISTRIES, action = "update")
    public ResponseEntity<RegistryResponse> updateRegistry(
            @PathVariable String id, @RequestBody UpdateRegistryRequest request) {

        RegistryUpdateRequest updateRequest =
                new RegistryUpdateRequest(
                        request.name(),
                        request.url(),
                        request.enabled() != null ? request.enabled() : true,
                        request.username(),
                        request.password(),
                        request.awsRegion(),
                        request.awsAccessKeyId(),
                        request.awsSecretKey(),
                        request.gcpProjectId(),
                        request.gcpServiceAccountJson(),
                        request.azureClientId(),
                        request.azureClientSecret(),
                        request.azureTenantId(),
                        request.isDefault() != null && request.isDefault());

        DockerRegistry registry = registryService.updateRegistry(id, updateRequest);
        return ResponseEntity.ok(RegistryResponse.fromEntity(registry));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = Resource.REGISTRIES, action = "delete")
    public ResponseEntity<Void> deleteRegistry(@PathVariable String id) {
        registryService.deleteRegistry(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @RequirePermission(resource = Resource.REGISTRIES, action = "read")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable String id) {
        boolean success = registryService.testConnection(id);
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/{id}/set-default")
    @RequirePermission(resource = Resource.REGISTRIES, action = "update")
    public ResponseEntity<Void> setDefault(@PathVariable String id) {
        registryService.setDefaultRegistry(id);
        return ResponseEntity.ok().build();
    }

    // Request/Response DTOs

    public record CreateRegistryRequest(
            String name,
            String url,
            RegistryType registryType,
            String username,
            String password,
            String awsRegion,
            String awsAccessKeyId,
            String awsSecretKey,
            String gcpProjectId,
            String gcpServiceAccountJson,
            String azureClientId,
            String azureClientSecret,
            String azureTenantId,
            Boolean isDefault) {}

    public record UpdateRegistryRequest(
            String name,
            String url,
            Boolean enabled,
            String username,
            String password,
            String awsRegion,
            String awsAccessKeyId,
            String awsSecretKey,
            String gcpProjectId,
            String gcpServiceAccountJson,
            String azureClientId,
            String azureClientSecret,
            String azureTenantId,
            Boolean isDefault) {}

    public record RegistryResponse(
            String id,
            String name,
            String url,
            String registryType,
            boolean hasCredentials,
            String awsRegion,
            String gcpProjectId,
            String azureClientId,
            String azureTenantId,
            boolean isDefault,
            boolean enabled,
            long createdAt,
            long updatedAt) {

        public static RegistryResponse fromEntity(DockerRegistry registry) {
            boolean hasCredentials =
                    (registry.getUsername() != null && !registry.getUsername().isEmpty())
                            || (registry.getPassword() != null && !registry.getPassword().isEmpty())
                            || (registry.getAwsAccessKeyId() != null
                                    && !registry.getAwsAccessKeyId().isEmpty())
                            || (registry.getGcpServiceAccountJson() != null
                                    && !registry.getGcpServiceAccountJson().isEmpty())
                            || (registry.getAzureClientSecret() != null
                                    && !registry.getAzureClientSecret().isEmpty());

            return new RegistryResponse(
                    registry.getId(),
                    registry.getName(),
                    registry.getUrl(),
                    registry.getRegistryType().name(),
                    hasCredentials,
                    registry.getAwsRegion(),
                    registry.getGcpProjectId(),
                    registry.getAzureClientId(),
                    registry.getAzureTenantId(),
                    registry.isDefault(),
                    registry.isEnabled(),
                    registry.getCreatedAt(),
                    registry.getUpdatedAt());
        }
    }
}
