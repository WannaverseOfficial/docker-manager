package com.wannaverse.iac.handlers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.DockerRegistry;
import com.wannaverse.persistence.DockerRegistry.RegistryType;
import com.wannaverse.persistence.DockerRegistryRepository;
import com.wannaverse.persistence.HostedRegistry;
import com.wannaverse.persistence.HostedRegistryRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.service.DockerRegistryService;
import com.wannaverse.service.EncryptionService;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code registries}: sign-ins for registries images are pulled from. The entries a hosted registry
 * makes for itself are left alone (see {@code hostedRegistries}).
 */
@Component
public class RegistriesHandler implements ResourceHandler<RegistriesHandler.RegistrySpec> {

    @Data
    @Doc("A registry Docker Manager signs in to when pulling images")
    public static class RegistrySpec {
        @Doc("The registry's name in Docker Manager. Defaults to the entry's key.")
        private String name;

        @Doc(
                "docker-hub, private (any registry with a user name and password), aws-ecr, gcr"
                        + " or acr. Default: private.")
        private RegistryType type;

        @Doc(
                "The registry's address, such as registry.example.com:5000 (default for"
                        + " docker-hub: https://index.docker.io)")
        private String url;

        @Doc("User name (docker-hub, private)")
        private String username;

        @Doc("Password or access token (docker-hub, private); for aws-ecr, a login token")
        private Secret password;

        @Doc("AWS region (aws-ecr)")
        private String awsRegion;

        @Doc("AWS access key id (aws-ecr)")
        private Secret awsAccessKeyId;

        @Doc("AWS secret access key (aws-ecr)")
        private Secret awsSecretKey;

        @Doc("Google Cloud project id (gcr)")
        private String gcpProjectId;

        @Doc("Service account key, as JSON (gcr)")
        private Secret gcpServiceAccountJson;

        @Doc("Azure client (application) id (acr)")
        private String azureClientId;

        @Doc("Azure client secret (acr)")
        private Secret azureClientSecret;

        @Doc("Azure tenant id (acr)")
        private String azureTenantId;

        @Doc("Mark it as the default registry (default false)")
        @JsonProperty("default")
        private Boolean defaultRegistry;

        @Doc("Use the sign-in when pulling (default true)")
        private Boolean enabled;
    }

    private final DockerRegistryRepository registries;
    private final HostedRegistryRepository hosted;
    private final DockerRegistryService service;
    private final EncryptionService encryption;

    public RegistriesHandler(
            DockerRegistryRepository registries,
            HostedRegistryRepository hosted,
            DockerRegistryService service,
            EncryptionService encryption) {
        this.registries = registries;
        this.hosted = hosted;
        this.service = service;
        this.encryption = encryption;
    }

    @Override
    public String section() {
        return "registries";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public Class<RegistrySpec> specType() {
        return RegistrySpec.class;
    }

    @Override
    public String description() {
        return "Registries to sign in to when pulling images";
    }

    /** Registry entries a hosted registry made for itself. */
    private Set<String> hostedEntries() {
        return hosted.findAll().stream()
                .map(HostedRegistry::getRegistryEntryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static RegistryType type(RegistrySpec s) {
        return s.getType() == null ? RegistryType.PRIVATE : s.getType();
    }

    private static String url(RegistrySpec s) {
        if (s.getUrl() != null && !s.getUrl().isBlank()) return s.getUrl().trim();
        return type(s) == RegistryType.DOCKER_HUB ? "https://index.docker.io" : null;
    }

    @Override
    public void validate(Entry<RegistrySpec> entry, ApplyContext ctx) {
        RegistrySpec s = entry.spec();
        if (url(s) == null) throw new IacException("url: say where the registry is");
        switch (type(s)) {
            case AWS_ECR -> {
                if (s.getAwsRegion() == null)
                    throw new IacException("awsRegion: required for aws-ecr");
            }
            case GCR -> {
                if (s.getGcpServiceAccountJson() == null) {
                    throw new IacException("gcpServiceAccountJson: required for gcr");
                }
            }
            case ACR -> {
                if (s.getAzureClientId() == null || s.getAzureClientSecret() == null) {
                    throw new IacException("azureClientId and azureClientSecret: required for acr");
                }
            }
            default -> {}
        }
        Optional<DockerRegistry> same = registries.findByName(entry.nameOr(s.getName()));
        if (same.isPresent() && hostedEntries().contains(same.get().getId())) {
            throw new IacException(
                    "a hosted registry uses the name \""
                            + same.get().getName()
                            + "\"; choose another");
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<RegistrySpec> entry, ApplyContext ctx) {
        Set<String> skip = hostedEntries();
        return registries
                .findByName(entry.nameOr(entry.spec().getName()))
                .filter(r -> !skip.contains(r.getId()))
                .map(this::target);
    }

    @Override
    public Optional<String> lookup(String name, ApplyContext ctx) {
        return registries.findByName(name).map(DockerRegistry::getId);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return registries.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<RegistrySpec> entry, ApplyContext ctx) {
        RegistrySpec s = entry.spec();
        try {
            DockerRegistry r =
                    service.createRegistry(
                            new DockerRegistryService.RegistryCreateRequest(
                                    entry.nameOr(s.getName()),
                                    url(s),
                                    type(s),
                                    blank(s.getUsername()),
                                    Secret.valueOf(s.getPassword()),
                                    s.getAwsRegion(),
                                    Secret.valueOf(s.getAwsAccessKeyId()),
                                    Secret.valueOf(s.getAwsSecretKey()),
                                    s.getGcpProjectId(),
                                    Secret.valueOf(s.getGcpServiceAccountJson()),
                                    s.getAzureClientId(),
                                    Secret.valueOf(s.getAzureClientSecret()),
                                    s.getAzureTenantId(),
                                    Boolean.TRUE.equals(s.getDefaultRegistry())));
            if (Boolean.FALSE.equals(s.getEnabled())) {
                r.setEnabled(false);
                r = registries.save(r);
            }
            return target(r);
        } catch (IllegalArgumentException e) {
            throw new IacException(e.getMessage());
        }
    }

    @Override
    public Target update(
            Entry<RegistrySpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        RegistrySpec s = entry.spec();
        DockerRegistry current =
                registries
                        .findById(record.getTargetId())
                        .orElseThrow(() -> new IacException("the registry no longer exists"));
        if (current.getRegistryType() != type(s)) {
            // The type decides which credentials are kept; start from a clean slate.
            current.setRegistryType(type(s));
            current.setUsername(null);
            current.setPassword(null);
            current.setAwsRegion(null);
            current.setAwsAccessKeyId(null);
            current.setAwsSecretKey(null);
            current.setGcpProjectId(null);
            current.setGcpServiceAccountJson(null);
            current.setAzureClientId(null);
            current.setAzureClientSecret(null);
            current.setAzureTenantId(null);
            registries.save(current);
        }
        try {
            DockerRegistry r =
                    service.updateRegistry(
                            current.getId(),
                            new DockerRegistryService.RegistryUpdateRequest(
                                    entry.nameOr(s.getName()),
                                    url(s),
                                    s.getEnabled() == null || s.getEnabled(),
                                    blank(s.getUsername()),
                                    Secret.valueOf(s.getPassword()),
                                    s.getAwsRegion(),
                                    Secret.valueOf(s.getAwsAccessKeyId()),
                                    Secret.valueOf(s.getAwsSecretKey()),
                                    s.getGcpProjectId(),
                                    Secret.valueOf(s.getGcpServiceAccountJson()),
                                    s.getAzureClientId(),
                                    Secret.valueOf(s.getAzureClientSecret()),
                                    s.getAzureTenantId(),
                                    Boolean.TRUE.equals(s.getDefaultRegistry())));
            // The service only sets credentials it is given; clear the ones the file dropped.
            if (changes.has("username") && blank(s.getUsername()) == null) r.setUsername(null);
            if (changes.has("password") && s.getPassword() == null) r.setPassword(null);
            if (changes.has("awsAccessKeyId") && s.getAwsAccessKeyId() == null) {
                r.setAwsAccessKeyId(null);
            }
            if (changes.has("awsSecretKey") && s.getAwsSecretKey() == null) r.setAwsSecretKey(null);
            return target(registries.save(r));
        } catch (IllegalArgumentException e) {
            throw new IacException(e.getMessage());
        }
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (registries.existsById(record.getTargetId())) {
            service.deleteRegistry(record.getTargetId());
        }
    }

    @Override
    public Map<String, RegistrySpec> export(ExportContext ctx) {
        Set<String> skip = hostedEntries();
        Map<String, RegistrySpec> out = new LinkedHashMap<>();
        for (DockerRegistry r : service.getAllRegistries()) {
            if (skip.contains(r.getId())) continue;
            String key = ctx.key(section(), r.getId(), r.getName());
            RegistrySpec s = new RegistrySpec();
            if (!key.equals(r.getName())) s.setName(r.getName());
            s.setType(r.getRegistryType());
            s.setUrl(r.getUrl());
            if (r.getUsername() != null) {
                try {
                    s.setUsername(encryption.decrypt(r.getUsername()));
                } catch (RuntimeException e) {
                    // Unreadable (another encryption key): leave it out.
                }
            }
            if (r.getPassword() != null) {
                s.setPassword(
                        ctx.secret(section(), key, "password", "password of registry " + key));
            }
            s.setAwsRegion(r.getAwsRegion());
            if (r.getAwsAccessKeyId() != null) {
                s.setAwsAccessKeyId(
                        ctx.secret(
                                section(),
                                key,
                                "aws_access_key_id",
                                "AWS key id of registry " + key));
            }
            if (r.getAwsSecretKey() != null) {
                s.setAwsSecretKey(
                        ctx.secret(
                                section(),
                                key,
                                "aws_secret_key",
                                "AWS secret key of registry " + key));
            }
            s.setGcpProjectId(r.getGcpProjectId());
            if (r.getGcpServiceAccountJson() != null) {
                s.setGcpServiceAccountJson(
                        ctx.secret(
                                section(),
                                key,
                                "gcp_service_account",
                                "GCP key of registry " + key));
            }
            s.setAzureClientId(r.getAzureClientId());
            if (r.getAzureClientSecret() != null) {
                s.setAzureClientSecret(
                        ctx.secret(
                                section(),
                                key,
                                "azure_client_secret",
                                "Azure secret of registry " + key));
            }
            s.setAzureTenantId(r.getAzureTenantId());
            if (r.isDefault()) s.setDefaultRegistry(true);
            if (!r.isEnabled()) s.setEnabled(false);
            out.put(key, s);
        }
        return out;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Target target(DockerRegistry r) {
        return new Target(r.getId(), null, r.getName());
    }
}
