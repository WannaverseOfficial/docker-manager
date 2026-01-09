package com.wannaverse.service;

import com.github.dockerjava.api.model.AuthConfig;
import com.wannaverse.persistence.DockerRegistry;
import com.wannaverse.persistence.DockerRegistry.RegistryType;
import com.wannaverse.persistence.DockerRegistryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class DockerRegistryService {
    private static final Logger log = LoggerFactory.getLogger(DockerRegistryService.class);

    private final DockerRegistryRepository registryRepository;
    private final EncryptionService encryptionService;

    public DockerRegistryService(
            DockerRegistryRepository registryRepository, EncryptionService encryptionService) {
        this.registryRepository = registryRepository;
        this.encryptionService = encryptionService;
    }

    // CRUD Operations

    public List<DockerRegistry> getAllRegistries() {
        return registryRepository.findAllByOrderByNameAsc();
    }

    public Optional<DockerRegistry> getRegistry(String id) {
        return registryRepository.findById(id);
    }

    @Transactional
    public DockerRegistry createRegistry(RegistryCreateRequest request) {
        if (registryRepository.existsByName(request.name())) {
            throw new IllegalArgumentException(
                    "Registry with name '" + request.name() + "' already exists");
        }

        DockerRegistry registry = new DockerRegistry();
        registry.setName(request.name());
        registry.setUrl(normalizeUrl(request.url()));
        registry.setRegistryType(request.registryType());
        registry.setEnabled(true);

        // Encrypt and set credentials based on type
        setCredentials(registry, request);

        if (request.isDefault()) {
            registryRepository.clearDefaultRegistry();
            registry.setDefault(true);
        }

        return registryRepository.save(registry);
    }

    @Transactional
    public DockerRegistry updateRegistry(String id, RegistryUpdateRequest request) {
        DockerRegistry registry =
                registryRepository
                        .findById(id)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Registry not found: " + id));

        if (!registry.getName().equals(request.name())
                && registryRepository.existsByName(request.name())) {
            throw new IllegalArgumentException(
                    "Registry with name '" + request.name() + "' already exists");
        }

        registry.setName(request.name());
        if (request.url() != null) {
            registry.setUrl(normalizeUrl(request.url()));
        }
        registry.setEnabled(request.enabled());

        // Update credentials if provided (null means keep existing)
        updateCredentials(registry, request);

        if (request.isDefault() && !registry.isDefault()) {
            registryRepository.clearDefaultRegistry();
            registry.setDefault(true);
        } else if (!request.isDefault() && registry.isDefault()) {
            registry.setDefault(false);
        }

        return registryRepository.save(registry);
    }

    @Transactional
    public void deleteRegistry(String id) {
        if (!registryRepository.existsById(id)) {
            throw new IllegalArgumentException("Registry not found: " + id);
        }
        registryRepository.deleteById(id);
    }

    @Transactional
    public void setDefaultRegistry(String id) {
        DockerRegistry registry =
                registryRepository
                        .findById(id)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Registry not found: " + id));

        registryRepository.clearDefaultRegistry();
        registry.setDefault(true);
        registryRepository.save(registry);
    }

    // Authentication

    public AuthConfig getAuthConfig(String imageName) {
        String registryUrl = extractRegistryUrl(imageName);
        if (registryUrl == null) {
            // Try default registry
            return getDefaultAuthConfig();
        }

        return registryRepository.findByUrlContaining(registryUrl).stream()
                .filter(DockerRegistry::isEnabled)
                .findFirst()
                .map(this::buildAuthConfig)
                .orElse(null);
    }

    public AuthConfig getAuthConfigForRegistry(String registryId) {
        return registryRepository.findById(registryId).map(this::buildAuthConfig).orElse(null);
    }

    private AuthConfig getDefaultAuthConfig() {
        return registryRepository
                .findByIsDefaultTrue()
                .filter(DockerRegistry::isEnabled)
                .map(this::buildAuthConfig)
                .orElse(null);
    }

    private AuthConfig buildAuthConfig(DockerRegistry registry) {
        AuthConfig authConfig = new AuthConfig();
        authConfig.withRegistryAddress(registry.getUrl());

        switch (registry.getRegistryType()) {
            case DOCKER_HUB:
            case PRIVATE:
                if (registry.getUsername() != null) {
                    authConfig.withUsername(encryptionService.decrypt(registry.getUsername()));
                }
                if (registry.getPassword() != null) {
                    authConfig.withPassword(encryptionService.decrypt(registry.getPassword()));
                }
                break;

            case AWS_ECR:
                // For ECR, the username is always "AWS" and password is the token
                // In production, you'd want to refresh the token periodically
                authConfig.withUsername("AWS");
                if (registry.getPassword() != null) {
                    authConfig.withPassword(encryptionService.decrypt(registry.getPassword()));
                }
                break;

            case GCR:
                // For GCR, username is "_json_key" and password is the service account JSON
                authConfig.withUsername("_json_key");
                if (registry.getGcpServiceAccountJson() != null) {
                    authConfig.withPassword(
                            encryptionService.decrypt(registry.getGcpServiceAccountJson()));
                }
                break;

            case ACR:
                // For ACR, use the client ID and secret
                if (registry.getAzureClientId() != null) {
                    authConfig.withUsername(registry.getAzureClientId());
                }
                if (registry.getAzureClientSecret() != null) {
                    authConfig.withPassword(
                            encryptionService.decrypt(registry.getAzureClientSecret()));
                }
                break;
        }

        return authConfig;
    }

    // Test connection
    public boolean testConnection(String id) {
        DockerRegistry registry =
                registryRepository
                        .findById(id)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Registry not found: " + id));

        try {
            // For now, just verify we can build auth config
            // In production, you'd make an actual API call to the registry
            AuthConfig authConfig = buildAuthConfig(registry);
            return authConfig != null;
        } catch (Exception e) {
            log.warn(
                    "Failed to test connection to registry {}: {}",
                    registry.getName(),
                    e.getMessage());
            return false;
        }
    }

    // Helper Methods

    private void setCredentials(DockerRegistry registry, RegistryCreateRequest request) {
        switch (request.registryType()) {
            case DOCKER_HUB:
            case PRIVATE:
                if (request.username() != null) {
                    registry.setUsername(encryptionService.encrypt(request.username()));
                }
                if (request.password() != null) {
                    registry.setPassword(encryptionService.encrypt(request.password()));
                }
                break;

            case AWS_ECR:
                registry.setAwsRegion(request.awsRegion());
                if (request.awsAccessKeyId() != null) {
                    registry.setAwsAccessKeyId(encryptionService.encrypt(request.awsAccessKeyId()));
                }
                if (request.awsSecretKey() != null) {
                    registry.setAwsSecretKey(encryptionService.encrypt(request.awsSecretKey()));
                }
                // Store the token as password (would be refreshed in production)
                if (request.password() != null) {
                    registry.setPassword(encryptionService.encrypt(request.password()));
                }
                break;

            case GCR:
                registry.setGcpProjectId(request.gcpProjectId());
                if (request.gcpServiceAccountJson() != null) {
                    registry.setGcpServiceAccountJson(
                            encryptionService.encrypt(request.gcpServiceAccountJson()));
                }
                break;

            case ACR:
                registry.setAzureClientId(request.azureClientId());
                registry.setAzureTenantId(request.azureTenantId());
                if (request.azureClientSecret() != null) {
                    registry.setAzureClientSecret(
                            encryptionService.encrypt(request.azureClientSecret()));
                }
                break;
        }
    }

    private void updateCredentials(DockerRegistry registry, RegistryUpdateRequest request) {
        // Only update credentials if they are provided (non-null)
        if (request.username() != null) {
            registry.setUsername(encryptionService.encrypt(request.username()));
        }
        if (request.password() != null) {
            registry.setPassword(encryptionService.encrypt(request.password()));
        }
        if (request.awsRegion() != null) {
            registry.setAwsRegion(request.awsRegion());
        }
        if (request.awsAccessKeyId() != null) {
            registry.setAwsAccessKeyId(encryptionService.encrypt(request.awsAccessKeyId()));
        }
        if (request.awsSecretKey() != null) {
            registry.setAwsSecretKey(encryptionService.encrypt(request.awsSecretKey()));
        }
        if (request.gcpProjectId() != null) {
            registry.setGcpProjectId(request.gcpProjectId());
        }
        if (request.gcpServiceAccountJson() != null) {
            registry.setGcpServiceAccountJson(
                    encryptionService.encrypt(request.gcpServiceAccountJson()));
        }
        if (request.azureClientId() != null) {
            registry.setAzureClientId(request.azureClientId());
        }
        if (request.azureTenantId() != null) {
            registry.setAzureTenantId(request.azureTenantId());
        }
        if (request.azureClientSecret() != null) {
            registry.setAzureClientSecret(encryptionService.encrypt(request.azureClientSecret()));
        }
    }

    private String normalizeUrl(String url) {
        if (url == null) return null;
        url = url.trim();
        // Remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // Add https:// if no protocol specified
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    private String extractRegistryUrl(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            return null;
        }

        // Check if image has a registry prefix
        int firstSlash = imageName.indexOf('/');
        if (firstSlash == -1) {
            return null; // Official Docker Hub image
        }

        String possibleRegistry = imageName.substring(0, firstSlash);
        // Check if it looks like a registry (has . or :)
        if (possibleRegistry.contains(".") || possibleRegistry.contains(":")) {
            return possibleRegistry;
        }

        return null; // Docker Hub namespace
    }

    // Request DTOs

    public record RegistryCreateRequest(
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
            boolean isDefault) {}

    public record RegistryUpdateRequest(
            String name,
            String url,
            boolean enabled,
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
            boolean isDefault) {}
}
