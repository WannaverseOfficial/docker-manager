package com.wannaverse.service;

import com.wannaverse.persistence.*;
import com.wannaverse.persistence.IngressAuditLog.IngressAction;
import com.wannaverse.persistence.IngressCertificate.AcmeChallengeType;
import com.wannaverse.persistence.IngressCertificate.CertificateSource;
import com.wannaverse.persistence.IngressCertificate.CertificateStatus;

import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CertificateService {
    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);

    // In-memory store for active challenges (token -> content)
    private final ConcurrentHashMap<String, String> activeChallenges = new ConcurrentHashMap<>();

    // Account key pairs stored per config (in production, persist these)
    private final ConcurrentHashMap<String, KeyPair> accountKeyPairs = new ConcurrentHashMap<>();

    private final IngressCertificateRepository certificateRepository;
    private final IngressConfigRepository configRepository;
    private final IngressAuditLogRepository auditLogRepository;
    private final IngressRouteRepository routeRepository;
    private final DockerHostRepository hostRepository;
    private final DockerService dockerService;
    private final NginxConfigGenerator nginxConfigGenerator;
    private final NginxApplier applier;
    private final EncryptionService encryptionService;

    public CertificateService(
            IngressCertificateRepository certificateRepository,
            IngressConfigRepository configRepository,
            IngressAuditLogRepository auditLogRepository,
            IngressRouteRepository routeRepository,
            DockerHostRepository hostRepository,
            DockerService dockerService,
            NginxConfigGenerator nginxConfigGenerator,
            NginxApplier applier,
            EncryptionService encryptionService) {
        this.applier = applier;
        this.encryptionService = encryptionService;
        this.certificateRepository = certificateRepository;
        this.configRepository = configRepository;
        this.auditLogRepository = auditLogRepository;
        this.routeRepository = routeRepository;
        this.hostRepository = hostRepository;
        this.dockerService = dockerService;
        this.nginxConfigGenerator = nginxConfigGenerator;
    }

    public Optional<IngressCertificate> getCertificate(String certificateId) {
        return certificateRepository.findById(certificateId);
    }

    @Async
    public void requestLetsEncryptCertificate(
            String certificateId, String userId, String username) {
        log.info("Starting Let's Encrypt certificate request for certificate {}", certificateId);

        IngressCertificate cert =
                certificateRepository
                        .findById(certificateId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Certificate not found: " + certificateId));

        IngressConfig config =
                configRepository
                        .findById(cert.getIngressConfigId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Config not found for certificate"));

        try {
            updateCertificateStatus(
                    cert, CertificateStatus.PENDING, "Initializing Let's Encrypt request...");

            auditLog(
                    config,
                    IngressAction.CERTIFICATE_REQUESTED,
                    "Starting Let's Encrypt certificate request for " + cert.getHostname(),
                    userId,
                    username);

            updateCertificateStatus(cert, CertificateStatus.PENDING, "Creating ACME account...");
            Session session = new Session(config.getAcmeDirectoryUrl());
            KeyPair accountKeyPair = getOrCreateAccountKeyPair(config);

            Account account = findOrRegisterAccount(session, accountKeyPair, config.getAcmeEmail());
            log.info("ACME account ready: {}", account.getLocation());

            auditLog(
                    config,
                    IngressAction.CERTIFICATE_REQUESTED,
                    "ACME account created/retrieved: " + account.getLocation(),
                    userId,
                    username);

            updateCertificateStatus(
                    cert, CertificateStatus.PENDING, "Creating certificate order...");
            Order order = account.newOrder().domain(cert.getHostname()).create();
            cert.setAcmeOrderUrl(order.getLocation().toString());
            certificateRepository.save(cert);

            log.info("Order created: {}", order.getLocation());
            auditLog(
                    config,
                    IngressAction.CERTIFICATE_REQUESTED,
                    "Certificate order created: " + order.getLocation(),
                    userId,
                    username);

            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() == Status.VALID) {
                    continue;
                }

                updateCertificateStatus(
                        cert,
                        CertificateStatus.PENDING,
                        "Processing authorization for " + auth.getIdentifier().getDomain() + "...");

                processAuthorization(cert, config, auth, userId, username);
            }

            updateCertificateStatus(
                    cert, CertificateStatus.PENDING, "Waiting for order to be ready...");
            int attempts = 0;
            while (order.getStatus() != Status.READY && attempts < 30) {
                if (order.getStatus() == Status.INVALID) {
                    throw new AcmeException("Order became invalid");
                }
                Thread.sleep(3000);
                order.update();
                attempts++;
            }

            if (order.getStatus() != Status.READY) {
                throw new AcmeException("Order did not become ready in time");
            }

            updateCertificateStatus(
                    cert, CertificateStatus.PENDING, "Generating certificate signing request...");
            KeyPair domainKeyPair = KeyPairUtils.createKeyPair(2048);

            CSRBuilder csrBuilder = new CSRBuilder();
            csrBuilder.addDomain(cert.getHostname());
            csrBuilder.sign(domainKeyPair);

            byte[] csr = csrBuilder.getEncoded();
            order.execute(csr);

            auditLog(
                    config,
                    IngressAction.CERTIFICATE_REQUESTED,
                    "CSR submitted, waiting for certificate issuance...",
                    userId,
                    username);

            updateCertificateStatus(
                    cert, CertificateStatus.PENDING, "Waiting for certificate issuance...");
            attempts = 0;
            while (order.getStatus() != Status.VALID && attempts < 30) {
                if (order.getStatus() == Status.INVALID) {
                    throw new AcmeException("Order became invalid after CSR submission");
                }
                Thread.sleep(3000);
                order.update();
                attempts++;
            }

            if (order.getStatus() != Status.VALID) {
                throw new AcmeException("Certificate was not issued in time");
            }

            updateCertificateStatus(cert, CertificateStatus.PENDING, "Downloading certificate...");
            Certificate certificate = order.getCertificate();
            X509Certificate x509Cert = certificate.getCertificate();
            List<X509Certificate> chain = certificate.getCertificateChain();

            StringWriter certWriter = new StringWriter();
            StringWriter keyWriter = new StringWriter();
            StringWriter chainWriter = new StringWriter();

            KeyPairUtils.writeKeyPair(domainKeyPair, keyWriter);

            java.io.PrintWriter pw = new java.io.PrintWriter(certWriter);
            pw.println("-----BEGIN CERTIFICATE-----");
            pw.println(
                    java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                            .encodeToString(x509Cert.getEncoded()));
            pw.println("-----END CERTIFICATE-----");
            pw.flush();

            pw = new java.io.PrintWriter(chainWriter);
            for (X509Certificate chainCert : chain) {
                if (!chainCert.equals(x509Cert)) {
                    pw.println("-----BEGIN CERTIFICATE-----");
                    pw.println(
                            java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                                    .encodeToString(chainCert.getEncoded()));
                    pw.println("-----END CERTIFICATE-----");
                }
            }
            pw.flush();

            cert.setCertificatePem(certWriter.toString());
            cert.setPrivateKeyPem(applier.encryptPem(keyWriter.toString()));
            cert.setChainPem(chainWriter.toString());
            cert.setIssuer(x509Cert.getIssuerX500Principal().getName());
            cert.setSubject(x509Cert.getSubjectX500Principal().getName());
            cert.setSerialNumber(x509Cert.getSerialNumber().toString(16));
            cert.setIssuedAt(x509Cert.getNotBefore().getTime());
            cert.setExpiresAt(x509Cert.getNotAfter().getTime());
            cert.setStatus(CertificateStatus.ACTIVE);
            cert.setStatusMessage("Certificate issued successfully!");
            cert.setAcmeChallengeToken(null);
            cert.setAcmeChallengeContent(null);

            certificateRepository.save(cert);

            if (cert.getAcmeChallengeToken() != null) {
                activeChallenges.remove(cert.getAcmeChallengeToken());
            }

            log.info(
                    "Certificate issued successfully for {}. Expires: {}",
                    cert.getHostname(),
                    x509Cert.getNotAfter());

            auditLog(
                    config,
                    IngressAction.CERTIFICATE_ISSUED,
                    "Certificate issued for "
                            + cert.getHostname()
                            + ". Valid until: "
                            + x509Cert.getNotAfter(),
                    userId,
                    username);

            deployCertificateToNginx(cert, config);

        } catch (Exception e) {
            log.error(
                    "Failed to obtain certificate for {}: {}",
                    cert.getHostname(),
                    e.getMessage(),
                    e);

            cert.setStatus(CertificateStatus.ERROR);
            cert.setStatusMessage("Failed: " + e.getMessage());
            cert.setLastRenewalError(e.getMessage());
            cert.setLastRenewalAttempt(System.currentTimeMillis());
            certificateRepository.save(cert);

            auditLog(
                    config,
                    IngressAction.CERTIFICATE_ERROR,
                    "Certificate request failed for " + cert.getHostname() + ": " + e.getMessage(),
                    userId,
                    username);
        }
    }

    private void processAuthorization(
            IngressCertificate cert,
            IngressConfig config,
            Authorization auth,
            String userId,
            String username)
            throws AcmeException, InterruptedException {
        String domain = auth.getIdentifier().getDomain();
        log.info("Processing authorization for domain: {}", domain);

        Http01Challenge challenge = auth.findChallenge(Http01Challenge.class).orElse(null);
        if (challenge == null) {
            throw new AcmeException("No HTTP-01 challenge available for " + domain);
        }

        String token = challenge.getToken();
        String content = challenge.getAuthorization();

        cert.setAcmeChallengeToken(token);
        cert.setAcmeChallengeContent(content);
        cert.setAcmeChallengeType(AcmeChallengeType.HTTP_01);
        cert.setStatusMessage(
                "HTTP-01 challenge ready. Waiting for Let's Encrypt to verify at"
                        + " http://"
                        + domain
                        + "/.well-known/acme-challenge/"
                        + token);
        certificateRepository.save(cert);

        activeChallenges.put(token, content);
        // Served by the proxy itself from its ACME webroot.
        applier.writeChallenge(config, token, content);

        log.info("Challenge ready: token={}", token);
        auditLog(
                config,
                IngressAction.CERTIFICATE_REQUESTED,
                "HTTP-01 challenge prepared for "
                        + domain
                        + ". Token: "
                        + token.substring(0, 8)
                        + "...",
                userId,
                username);

        updateCertificateStatus(
                cert, CertificateStatus.PENDING, "Triggering HTTP-01 challenge verification...");
        challenge.trigger();

        int attempts = 0;
        while (challenge.getStatus() != Status.VALID && attempts < 30) {
            if (challenge.getStatus() == Status.INVALID) {
                String error =
                        challenge
                                .getError()
                                .flatMap(p -> p.getDetail())
                                .orElse("Challenge validation failed");
                applier.removeChallenge(config, token);
                activeChallenges.remove(token);
                throw new AcmeException(
                        "Let's Encrypt could not verify "
                                + domain
                                + ": "
                                + error
                                + ". Check that its DNS record points at this server and that port"
                                + " 80 is reachable from the internet.");
            }

            updateCertificateStatus(
                    cert,
                    CertificateStatus.PENDING,
                    "Waiting for challenge validation (attempt " + (attempts + 1) + "/30)...");

            Thread.sleep(3000);
            challenge.update();
            attempts++;
        }

        applier.removeChallenge(config, token);
        activeChallenges.remove(token);
        if (challenge.getStatus() != Status.VALID) {
            throw new AcmeException("Challenge did not pass in time");
        }

        log.info("Challenge validated successfully for {}", domain);
        auditLog(
                config,
                IngressAction.CERTIFICATE_REQUESTED,
                "HTTP-01 challenge validated for " + domain,
                userId,
                username);
    }

    public Optional<String> getChallengeResponse(String token) {
        String content = activeChallenges.get(token);
        if (content != null) {
            return Optional.of(content);
        }

        return certificateRepository
                .findByAcmeChallengeToken(token)
                .map(IngressCertificate::getAcmeChallengeContent);
    }

    @Async
    public void renewCertificate(String certificateId, String userId, String username) {
        log.info("Starting certificate renewal for {}", certificateId);

        IngressCertificate cert =
                certificateRepository
                        .findById(certificateId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Certificate not found: " + certificateId));

        IngressConfig config =
                configRepository
                        .findById(cert.getIngressConfigId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Config not found for certificate"));

        cert.setStatus(CertificateStatus.RENEWAL_PENDING);
        cert.setStatusMessage("Renewal in progress...");
        cert.setLastRenewalAttempt(System.currentTimeMillis());
        certificateRepository.save(cert);

        auditLog(
                config,
                IngressAction.CERTIFICATE_RENEWED,
                "Starting certificate renewal for " + cert.getHostname(),
                userId,
                username);

        requestLetsEncryptCertificate(certificateId, userId, username);
    }

    public List<IngressCertificate> getCertificatesNeedingRenewal() {
        long now = System.currentTimeMillis();
        long thirtyDaysFromNow = now + Duration.ofDays(30).toMillis();
        List<IngressCertificate> due =
                new java.util.ArrayList<>(
                        certificateRepository.findByStatusAndExpiresAtBefore(
                                CertificateStatus.ACTIVE, thirtyDaysFromNow));
        // Renewals that failed are retried at most twice a day until they succeed.
        for (IngressCertificate c : certificateRepository.findByStatus(CertificateStatus.ERROR)) {
            boolean expiring = c.getExpiresAt() != null && c.getExpiresAt() < thirtyDaysFromNow;
            boolean waited =
                    c.getLastRenewalAttempt() == null
                            || now - c.getLastRenewalAttempt() > Duration.ofHours(12).toMillis();
            if (expiring && waited && c.getSource() == CertificateSource.LETS_ENCRYPT) due.add(c);
        }
        return due;
    }

    public void retryCertificateRequest(String certificateId, String userId, String username) {
        IngressCertificate cert =
                certificateRepository
                        .findById(certificateId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Certificate not found: " + certificateId));

        if (cert.getSource() != CertificateSource.LETS_ENCRYPT) {
            throw new IllegalArgumentException("Only Let's Encrypt certificates can be retried");
        }

        cert.setStatus(CertificateStatus.PENDING);
        cert.setStatusMessage("Retrying certificate request...");
        cert.setLastRenewalError(null);
        certificateRepository.save(cert);

        requestLetsEncryptCertificate(certificateId, userId, username);
    }

    @Transactional
    public void deleteCertificate(String certificateId, String userId, String username) {
        IngressCertificate cert =
                certificateRepository
                        .findById(certificateId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Certificate not found: " + certificateId));

        IngressConfig config =
                configRepository
                        .findById(cert.getIngressConfigId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Config not found for certificate"));

        String hostname = cert.getHostname();

        try {
            DockerHost host =
                    hostRepository
                            .findById(config.getDockerHostId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Host not found: " + config.getDockerHostId()));

            if (config.getNginxContainerId() != null) {
                DockerAPI api =
                        dockerService.dockerAPI(
                                dockerService.createClientCached(host.getDockerHostUrl()));

                certificateRepository.delete(cert);
                if (config.getStatus() == IngressConfig.IngressStatus.ENABLED)
                    applier.apply(config);
                api.exec(
                        config.getNginxContainerId(),
                        "rm",
                        "-rf",
                        NginxConfigGenerator.CERT_DIR + "/" + hostname);
            }
        } catch (Exception e) {
            log.warn("Failed to remove certificate files from nginx: {}", e.getMessage());
        }

        if (cert.getAcmeChallengeToken() != null) {
            activeChallenges.remove(cert.getAcmeChallengeToken());
        }

        if (certificateRepository.existsById(cert.getId())) certificateRepository.delete(cert);

        auditLog(
                config,
                IngressAction.CERTIFICATE_DELETED,
                "Certificate deleted for " + hostname,
                userId,
                username);

        log.info("Certificate deleted for {}", hostname);
    }

    @Transactional
    public IngressCertificate setAutoRenew(
            String certificateId, boolean autoRenew, String userId, String username) {
        IngressCertificate cert =
                certificateRepository
                        .findById(certificateId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Certificate not found: " + certificateId));

        IngressConfig config =
                configRepository
                        .findById(cert.getIngressConfigId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Config not found for certificate"));

        cert.setAutoRenew(autoRenew);
        cert = certificateRepository.save(cert);

        auditLog(
                config,
                IngressAction.CERTIFICATE_UPDATED,
                "Auto-renewal "
                        + (autoRenew ? "enabled" : "disabled")
                        + " for "
                        + cert.getHostname(),
                userId,
                username);

        log.info(
                "Auto-renewal {} for certificate {}",
                autoRenew ? "enabled" : "disabled",
                cert.getHostname());

        return cert;
    }

    @Transactional
    public IngressCertificate uploadCertificate(
            String certificateId,
            String certificatePem,
            String privateKeyPem,
            String chainPem,
            String userId,
            String username) {
        IngressCertificate cert =
                certificateRepository
                        .findById(certificateId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Certificate not found: " + certificateId));

        IngressConfig config =
                configRepository
                        .findById(cert.getIngressConfigId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Config not found for certificate"));

        try {
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate x509Cert =
                    (X509Certificate)
                            cf.generateCertificate(
                                    new java.io.ByteArrayInputStream(certificatePem.getBytes()));
            CertificateChecks.requireMatchingKey(x509Cert, privateKeyPem);
            CertificateChecks.requireHostname(x509Cert, cert.getHostname());
            if (x509Cert.getNotAfter().getTime() < System.currentTimeMillis()) {
                throw new IllegalArgumentException("This certificate has expired");
            }

            cert.setCertificatePem(certificatePem.strip());
            cert.setPrivateKeyPem(applier.encryptPem(privateKeyPem.strip()));
            cert.setChainPem(chainPem);
            cert.setIssuer(x509Cert.getIssuerX500Principal().getName());
            cert.setSubject(x509Cert.getSubjectX500Principal().getName());
            cert.setSerialNumber(x509Cert.getSerialNumber().toString(16));
            cert.setIssuedAt(x509Cert.getNotBefore().getTime());
            cert.setExpiresAt(x509Cert.getNotAfter().getTime());
            cert.setStatus(CertificateStatus.ACTIVE);
            cert.setStatusMessage("Certificate uploaded successfully");

            certificateRepository.save(cert);

            auditLog(
                    config,
                    IngressAction.CERTIFICATE_ISSUED,
                    "Custom certificate uploaded for "
                            + cert.getHostname()
                            + ". Valid until: "
                            + x509Cert.getNotAfter(),
                    userId,
                    username);

            deployCertificateToNginx(cert, config);

            return cert;

        } catch (Exception e) {
            log.warn("Certificate upload rejected: {}", e.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Could not use this certificate: " + e.getMessage());
        }
    }

    private void deployCertificateToNginx(IngressCertificate cert, IngressConfig config) {
        IngressConfig current = configRepository.findById(config.getId()).orElse(config);
        if (current.getStatus() != IngressConfig.IngressStatus.ENABLED) return;
        try {
            applier.apply(current);
            log.info("Certificate for {} is now served", cert.getHostname());
        } catch (Exception e) {
            log.error(
                    "Could not apply the certificate for {}: {}",
                    cert.getHostname(),
                    e.getMessage());
            cert.setStatusMessage("Issued, but nginx could not load it: " + e.getMessage());
            certificateRepository.save(cert);
        }
    }

    private Account findOrRegisterAccount(Session session, KeyPair accountKeyPair, String email)
            throws AcmeException {
        AccountBuilder builder =
                new AccountBuilder().agreeToTermsOfService().useKeyPair(accountKeyPair);

        if (email != null && !email.isEmpty()) {
            builder.addEmail(email);
        }

        return builder.create(session);
    }

    /**
     * The ACME account key, kept (encrypted) with the ingress settings. It used to live only in
     * memory, so every restart registered a new Let's Encrypt account.
     */
    private synchronized KeyPair getOrCreateAccountKeyPair(IngressConfig config) {
        KeyPair cached = accountKeyPairs.get(config.getId());
        if (cached != null) return cached;
        try {
            KeyPair pair;
            if (config.getAcmeAccountKeyPem() != null) {
                String pem = encryptionService.decrypt(config.getAcmeAccountKeyPem());
                pair = KeyPairUtils.readKeyPair(new java.io.StringReader(pem));
            } else {
                pair = KeyPairUtils.createKeyPair(2048);
                StringWriter out = new StringWriter();
                KeyPairUtils.writeKeyPair(pair, out);
                IngressConfig fresh = configRepository.findById(config.getId()).orElse(config);
                fresh.setAcmeAccountKeyPem(encryptionService.encrypt(out.toString()));
                configRepository.save(fresh);
            }
            accountKeyPairs.put(config.getId(), pair);
            return pair;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not load the ACME account key: " + e.getMessage(), e);
        }
    }

    private void updateCertificateStatus(
            IngressCertificate cert, CertificateStatus status, String message) {
        cert.setStatus(status);
        cert.setStatusMessage(message);
        certificateRepository.save(cert);
        log.debug("Certificate {} status: {} - {}", cert.getHostname(), status, message);
    }

    private void auditLog(
            IngressConfig config,
            IngressAction action,
            String details,
            String userId,
            String username) {
        auditLogRepository.save(
                IngressAuditLog.success(config.getId(), action, details, userId, username));
    }
}
