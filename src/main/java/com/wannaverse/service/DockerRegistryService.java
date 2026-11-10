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

    private final com.wannaverse.persistence.DockerHostRepository dockerHostRepository;
    private final DockerService dockerService;

    public DockerRegistryService(
            DockerRegistryRepository registryRepository,
            EncryptionService encryptionService,
            com.wannaverse.persistence.DockerHostRepository dockerHostRepository,
            DockerService dockerService) {
        this.registryRepository = registryRepository;
        this.encryptionService = encryptionService;
        this.dockerHostRepository = dockerHostRepository;
        this.dockerService = dockerService;
    }

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

    /**
     * Credentials for pulling {@code imageName}, or null. A stored registry is used only when its
     * host (and port) is exactly the image's registry host. Docker Hub images only ever get Docker
     * Hub credentials. (Previously a substring match was used, so credentials for
     * registry.gitlab.com could be sent to any registry whose name contained part of it, and Docker
     * Hub pulls received whichever registry was marked default.)
     */
    public AuthConfig getAuthConfig(String imageName) {
        String imageHost = extractRegistryUrl(imageName);
        boolean dockerHub =
                imageHost == null
                        || imageHost.equalsIgnoreCase("docker.io")
                        || imageHost.equalsIgnoreCase("index.docker.io")
                        || imageHost.equalsIgnoreCase("registry-1.docker.io");

        for (DockerRegistry registry : registryRepository.findByEnabledTrue()) {
            if (dockerHub) {
                if (registry.getRegistryType() == DockerRegistry.RegistryType.DOCKER_HUB) {
                    return buildAuthConfig(registry);
                }
            } else if (imageHost.equalsIgnoreCase(registryHost(registry.getUrl()))) {
                return buildAuthConfig(registry);
            }
        }
        return null;
    }

    /** "https://registry.example.com:5000/v2/" -> "registry.example.com:5000". */
    static String registryHost(String url) {
        if (url == null) return "";
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    public AuthConfig getAuthConfigForRegistry(String registryId) {
        return registryRepository.findById(registryId).map(this::buildAuthConfig).orElse(null);
    }

    private AuthConfig buildAuthConfig(DockerRegistry registry) {
        AuthConfig authConfig = new AuthConfig();
        authConfig.withRegistryAddress(
                registry.getRegistryType() == RegistryType.DOCKER_HUB
                        ? "https://index.docker.io/v1/"
                        : registryHost(registry.getUrl()));

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
                authConfig.withUsername("AWS");
                if (registry.getPassword() != null) {
                    authConfig.withPassword(encryptionService.decrypt(registry.getPassword()));
                }
                break;

            case GCR:
                authConfig.withUsername("_json_key");
                if (registry.getGcpServiceAccountJson() != null) {
                    authConfig.withPassword(
                            encryptionService.decrypt(registry.getGcpServiceAccountJson()));
                }
                break;

            case ACR:
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

    public record TestResult(boolean success, String message) {}

    /**
     * Asks a Docker daemon to sign in to the registry with the stored credentials (what `docker
     * login` does), so the result reflects whether pulls will actually be authorised. Previously
     * this only checked that the credentials could be decrypted.
     */
    public TestResult testConnection(String id) {
        DockerRegistry registry =
                registryRepository
                        .findById(id)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Registry not found: " + id));

        var hosts = dockerHostRepository.findAll();
        if (hosts.isEmpty()) {
            return new TestResult(
                    false, "Add a Docker host first; it is used to test the sign-in.");
        }

        try {
            AuthConfig authConfig = buildAuthConfig(registry);
            var client = dockerService.createClientCached(hosts.get(0).getDockerHostUrl());
            var response = client.authCmd().withAuthConfig(authConfig).exec();
            String status = response != null ? response.getStatus() : null;
            return new TestResult(true, status != null ? status : "Signed in");
        } catch (Exception e) {
            log.info("Registry sign-in test failed for {}: {}", registry.getName(), e.getMessage());
            String message = e.getMessage() != null ? e.getMessage() : "Sign-in failed";
            return new TestResult(false, message);
        }
    }

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
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    private String extractRegistryUrl(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            return null;
        }

        int firstSlash = imageName.indexOf('/');
        if (firstSlash == -1) {
            return null;
        }

        String possibleRegistry = imageName.substring(0, firstSlash);
        if (possibleRegistry.contains(".") || possibleRegistry.contains(":")) {
            return possibleRegistry;
        }

        return null;
    }

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
