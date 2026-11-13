package com.wannaverse.iac.handlers;

import com.wannaverse.iac.ApplyContext;
import com.wannaverse.iac.Changes;
import com.wannaverse.iac.ConfigExporter;
import com.wannaverse.iac.Doc;
import com.wannaverse.iac.ExportContext;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.ResourceHandler;
import com.wannaverse.iac.Secret;
import com.wannaverse.persistence.IngressCertificate;
import com.wannaverse.persistence.IngressCertificate.CertificateSource;
import com.wannaverse.persistence.IngressCertificate.CertificateStatus;
import com.wannaverse.persistence.IngressCertificateRepository;
import com.wannaverse.persistence.IngressConfig;
import com.wannaverse.persistence.IngressConfigRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.security.InputValidator;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.CertificateService;

import lombok.Data;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ingress.<host>.certificates}: certificates you bring, for routes with tls: custom-cert or
 * bring-your-own. Let's Encrypt certificates are requested by their routes and need no entry.
 */
@Component
public class IngressCertificatesHandler
        implements ResourceHandler<IngressCertificatesHandler.CertificateSpec> {

    @Data
    @Doc("A certificate you bring, served for one hostname")
    public static class CertificateSpec {
        @Doc("The hostname it is for. Defaults to the entry's key.")
        private String hostname;

        @Doc(value = "The certificate (PEM), such as ${file:/run/secrets/app.crt}", required = true)
        private Secret certificate;

        @Doc(value = "Its private key (PEM)", required = true)
        private Secret privateKey;

        @Doc("Intermediate certificates (PEM), if the certificate doesn't include them")
        private Secret chain;
    }

    private final IngressConfigRepository configs;
    private final IngressCertificateRepository certificates;
    private final CertificateService certificateService;

    public IngressCertificatesHandler(
            IngressConfigRepository configs,
            IngressCertificateRepository certificates,
            CertificateService certificateService) {
        this.configs = configs;
        this.certificates = certificates;
        this.certificateService = certificateService;
    }

    @Override
    public String section() {
        return "ingress.certificates";
    }

    @Override
    public String parentSection() {
        return "ingress";
    }

    @Override
    public String parentField() {
        return "certificates";
    }

    @Override
    public int order() {
        return 160;
    }

    @Override
    public Class<CertificateSpec> specType() {
        return CertificateSpec.class;
    }

    @Override
    public String description() {
        return "Certificates you bring, keyed by a name (usually the hostname)";
    }

    @Override
    public void validate(Entry<CertificateSpec> entry, ApplyContext ctx) {
        String hostname = hostname(entry);
        if (!InputValidator.isValidRouteHostname(hostname)) {
            throw new IacException("hostname: give a full hostname, such as app.example.com");
        }
        String cert = entry.spec().getCertificate().value();
        if (cert != null && !cert.contains("BEGIN CERTIFICATE")) {
            throw new IacException("certificate: expected a PEM certificate (BEGIN CERTIFICATE)");
        }
        String key = entry.spec().getPrivateKey().value();
        if (key != null && !key.contains("PRIVATE KEY")) {
            throw new IacException(
                    "privateKey: expected a PEM private key (BEGIN ... PRIVATE KEY)");
        }
    }

    @Override
    public Optional<Target> findExisting(Entry<CertificateSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.parentKey());
        if (hostId == null) return Optional.empty();
        return configs.findByDockerHostId(hostId)
                .flatMap(
                        c ->
                                certificates.findByIngressConfigIdAndHostname(
                                        c.getId(), hostname(entry)))
                .map(cert -> new Target(cert.getId(), hostId, cert.getHostname()));
    }

    @Override
    public boolean exists(ManagedResource record, ApplyContext ctx) {
        return certificates.existsById(record.getTargetId());
    }

    @Override
    public Target create(Entry<CertificateSpec> entry, ApplyContext ctx) {
        String hostId = ctx.hostId(entry.parentKey());
        IngressConfig config =
                configs.findByDockerHostId(hostId)
                        .orElseThrow(
                                () ->
                                        new IacException(
                                                "ingress is not set up on host "
                                                        + entry.parentKey()));
        String hostname = hostname(entry);
        IngressCertificate cert =
                certificates
                        .findByIngressConfigIdAndHostname(config.getId(), hostname)
                        .orElseGet(
                                () -> {
                                    IngressCertificate c = new IngressCertificate();
                                    c.setIngressConfigId(config.getId());
                                    c.setHostname(hostname);
                                    c.setStatus(CertificateStatus.PENDING);
                                    return c;
                                });
        return upload(cert, entry, ctx, hostId);
    }

    @Override
    public Target update(
            Entry<CertificateSpec> entry,
            Changes changes,
            ManagedResource record,
            ApplyContext ctx) {
        IngressCertificate cert = certificates.findById(record.getTargetId()).orElse(null);
        if (cert == null || !cert.getHostname().equals(hostname(entry))) {
            // A new hostname is another certificate; the old one goes.
            Target t = create(entry, ctx);
            if (cert != null && !cert.getId().equals(t.id())) {
                certificateService.deleteCertificate(
                        cert.getId(), SecurityContextHolder.getCurrentUserId(), ctx.actor());
            }
            return t;
        }
        if (!changes.has("certificate", "privateKey", "chain")) {
            return new Target(cert.getId(), record.getHostId(), cert.getHostname());
        }
        return upload(cert, entry, ctx, record.getHostId());
    }

    private Target upload(
            IngressCertificate cert,
            Entry<CertificateSpec> entry,
            ApplyContext ctx,
            String hostId) {
        cert.setSource(CertificateSource.UPLOADED);
        cert.setAutoRenew(false);
        cert = certificates.save(cert);
        CertificateSpec s = entry.spec();
        String chain = Secret.valueOf(s.getChain());
        IngressCertificate saved =
                certificateService.uploadCertificate(
                        cert.getId(),
                        s.getCertificate().value(),
                        s.getPrivateKey().value(),
                        chain == null || chain.isBlank() ? null : chain.strip(),
                        SecurityContextHolder.getCurrentUserId(),
                        ctx.actor());
        return new Target(saved.getId(), hostId, saved.getHostname());
    }

    @Override
    public void delete(ManagedResource record, ApplyContext ctx) {
        if (!certificates.existsById(record.getTargetId())) return;
        certificateService.deleteCertificate(
                record.getTargetId(), SecurityContextHolder.getCurrentUserId(), ctx.actor());
    }

    @Override
    public Map<String, CertificateSpec> export(ExportContext ctx) {
        Map<String, CertificateSpec> out = new LinkedHashMap<>();
        for (IngressConfig config : configs.findAll()) {
            String hostKey = ctx.hostKey(config.getDockerHostId());
            Set<String> used = new HashSet<>();
            for (IngressCertificate c : certificates.findByIngressConfigId(config.getId())) {
                if (c.getSource() != CertificateSource.UPLOADED || c.getCertificatePem() == null) {
                    continue;
                }
                String name = ctx.key(section(), c.getId(), ConfigExporter.slug(c.getHostname()));
                String base = name;
                for (int i = 2; !used.add(name); i++) name = base + "-" + i;
                String envKey = hostKey + "_" + name;
                CertificateSpec s = new CertificateSpec();
                if (!name.equals(c.getHostname())) s.setHostname(c.getHostname());
                s.setCertificate(
                        ctx.secret(
                                "certificate",
                                envKey,
                                "pem",
                                "certificate for " + c.getHostname() + " on " + hostKey));
                s.setPrivateKey(
                        ctx.secret(
                                "certificate",
                                envKey,
                                "key",
                                "private key for " + c.getHostname() + " on " + hostKey));
                if (c.getChainPem() != null && !c.getChainPem().isBlank()) {
                    s.setChain(
                            ctx.secret(
                                    "certificate",
                                    envKey,
                                    "chain",
                                    "chain for " + c.getHostname() + " on " + hostKey));
                }
                out.put(hostKey + "/" + name, s);
            }
        }
        return out;
    }

    private static String hostname(Entry<CertificateSpec> entry) {
        return entry.nameOr(entry.spec().getHostname()).toLowerCase(Locale.ROOT);
    }
}
