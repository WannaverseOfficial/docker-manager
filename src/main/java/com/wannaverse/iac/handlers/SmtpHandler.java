package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.SmtpConfiguration;
import com.wannaverse.persistence.SmtpConfigurationRepository;
import com.wannaverse.service.EncryptionService;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** {@code smtp}: the mail server used for notifications. */
@Component
public class SmtpHandler implements ResourceHandler<SmtpHandler.SmtpSpec> {

    @Data
    public static class SmtpSpec {
        @Doc(value = "Mail server name", required = true)
        private String host;

        @Doc("Port (default 587)")
        private Integer port;

        @Doc("Sign-in name, if the server needs one")
        private String username;

        @Doc("Sign-in password")
        private Secret password;

        @Doc(value = "The From address of notifications", required = true)
        private String from;

        @Doc("The From name (default: Docker Manager)")
        private String fromName;

        @Doc("Connection security (default starttls)")
        private SmtpConfiguration.SecurityType security;

        @Doc("Send notifications (default true)")
        private Boolean enabled;
    }

    private final SmtpConfigurationRepository repo;
    private final EncryptionService encryption;

    public SmtpHandler(SmtpConfigurationRepository repo, EncryptionService encryption) {
        this.repo = repo;
        this.encryption = encryption;
    }

    @Override
    public String section() {
        return "smtp";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean singleton() {
        return true;
    }

    @Override
    public Class<SmtpSpec> specType() {
        return SmtpSpec.class;
    }

    @Override
    public String description() {
        return "The mail server used to send notifications";
    }

    @Override
    public Optional<Target> findExisting(Entry<SmtpSpec> entry, ApplyContext ctx) {
        return repo.findAll().stream().findFirst().map(this::target);
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return repo.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<SmtpSpec> entry, ApplyContext ctx) {
        SmtpConfiguration c = repo.findAll().stream().findFirst().orElseGet(SmtpConfiguration::new);
        return save(c, entry.spec());
    }

    @Override
    public Target update(
            Entry<SmtpSpec> entry, Changes changes, ManagedResource record, ApplyContext ctx) {
        SmtpConfiguration c =
                repo.findById(record.getTargetId())
                        .orElseGet(
                                () ->
                                        repo.findAll().stream()
                                                .findFirst()
                                                .orElseGet(SmtpConfiguration::new));
        return save(c, entry.spec());
    }

    private Target save(SmtpConfiguration c, SmtpSpec spec) {
        c.setHost(spec.getHost().trim());
        c.setPort(spec.getPort() == null ? 587 : spec.getPort());
        c.setUsername(spec.getUsername());
        c.setFromAddress(spec.getFrom().trim());
        c.setFromName(spec.getFromName() == null ? "Docker Manager" : spec.getFromName());
        c.setSecurityType(
                spec.getSecurity() == null
                        ? SmtpConfiguration.SecurityType.STARTTLS
                        : spec.getSecurity());
        c.setEnabled(spec.getEnabled() == null || spec.getEnabled());
        c.setEncryptedPassword(
                spec.getPassword() == null || spec.getPassword().isBlank()
                        ? null
                        : encryption.encrypt(spec.getPassword().value()));
        return target(repo.save(c));
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        repo.deleteAll();
    }

    @Override
    public Map<String, SmtpSpec> export(ExportContext ctx) {
        return repo.findAll().stream()
                .findFirst()
                .map(
                        c -> {
                            SmtpSpec s = new SmtpSpec();
                            s.setHost(c.getHost());
                            s.setPort(c.getPort());
                            s.setUsername(c.getUsername());
                            if (c.getEncryptedPassword() != null) {
                                s.setPassword(ctx.secret("smtp", "", "password", "SMTP password"));
                            }
                            s.setFrom(c.getFromAddress());
                            s.setFromName(c.getFromName());
                            s.setSecurity(c.getSecurityType());
                            s.setEnabled(c.isEnabled());
                            return Map.of("default", s);
                        })
                .orElse(Map.of());
    }

    private Target target(SmtpConfiguration c) {
        return new Target(c.getId(), null, c.getHost());
    }
}
