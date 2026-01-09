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

    public CertificateService(
            IngressCertificateRepository certificateRepository,
            IngressConfigRepository configRepository,
            IngressAuditLogRepository auditLogRepository,
            IngressRouteRepository routeRepository,
            DockerHostRepository hostRepository,
            DockerService dockerService,
            NginxConfigGenerator nginxConfigGenerator) {
        this.certificateRepository = certificateRepository;
        this.configRepository = configRepository;
        this.auditLogRepository = auditLogRepository;
        this.routeRepository = routeRepository;
        this.hostRepository = hostRepository;
        this.dockerService = dockerService;
        this.nginxConfigGenerator = nginxConfigGenerator;
    }

    /** Get a certificate by ID. */
    public Optional<IngressCertificate> getCertificate(String certificateId) {
        return certificateRepository.findById(certificateId);
    }

    /**
     * Request a new Let's Encrypt certificate. This is an async operation that: 1. Creates an ACME
     * account (if needed) 2. Submits a certificate order 3. Sets up HTTP-01 challenge 4. Waits for
     * challenge validation 5. Downloads and stores the certificate
     */
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

            // Step 1: Get or create account
            updateCertificateStatus(cert, CertificateStatus.PENDING, "Creating ACME account...");
            Session session = new Session(config.getAcmeDirectoryUrl());
            KeyPair accountKeyPair = getOrCreateAccountKeyPair(config.getId());

            Account account = findOrRegisterAccount(session, accountKeyPair, config.getAcmeEmail());
            log.info("ACME account ready: {}", account.getLocation());

            auditLog(
                    config,
                    IngressAction.CERTIFICATE_REQUESTED,
                    "ACME account created/retrieved: " + account.getLocation(),
                    userId,
                    username);

            // Step 2: Create order
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

            // Step 3: Process authorizations
            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() == Status.VALID) {
                    continue; // Already authorized
                }

                updateCertificateStatus(
                        cert,
                        CertificateStatus.PENDING,
                        "Processing authorization for " + auth.getIdentifier().getDomain() + "...");

                processAuthorization(cert, config, auth, userId, username);
            }

            // Step 4: Wait for order to be ready
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

            // Step 5: Generate CSR and finalize order
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

            // Step 6: Wait for certificate
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

            // Step 7: Download certificate
            updateCertificateStatus(cert, CertificateStatus.PENDING, "Downloading certificate...");
            Certificate certificate = order.getCertificate();
            X509Certificate x509Cert = certificate.getCertificate();
            List<X509Certificate> chain = certificate.getCertificateChain();

            // Store certificate and key
            StringWriter certWriter = new StringWriter();
            StringWriter keyWriter = new StringWriter();
            StringWriter chainWriter = new StringWriter();

            KeyPairUtils.writeKeyPair(domainKeyPair, keyWriter);

            // Write certificate PEM
            java.io.PrintWriter pw = new java.io.PrintWriter(certWriter);
            pw.println("-----BEGIN CERTIFICATE-----");
            pw.println(
                    java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                            .encodeToString(x509Cert.getEncoded()));
            pw.println("-----END CERTIFICATE-----");
            pw.flush();

            // Write chain PEM
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
            cert.setPrivateKeyPem(keyWriter.toString());
            cert.setChainPem(chainWriter.toString());
            cert.setIssuer(x509Cert.getIssuerX500Principal().getName());
            cert.setSubject(x509Cert.getSubjectX500Principal().getName());
            cert.setSerialNumber(x509Cert.getSerialNumber().toString(16));
            cert.setIssuedAt(x509Cert.getNotBefore().getTime());
            cert.setExpiresAt(x509Cert.getNotAfter().getTime());
            cert.setStatus(CertificateStatus.ACTIVE);
            cert.setStatusMessage("Certificate issued successfully!");
            cert.setAcmeChallengeToken(null); // Clear challenge data
            cert.setAcmeChallengeContent(null);

            certificateRepository.save(cert);

            // Clean up challenge from memory
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

            // Step 8: Deploy certificate to nginx
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

        // Find HTTP-01 challenge
        Http01Challenge challenge = auth.findChallenge(Http01Challenge.class).orElse(null);
        if (challenge == null) {
            throw new AcmeException("No HTTP-01 challenge available for " + domain);
        }

        // Store challenge for the challenge endpoint
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

        // Also store in memory for fast access
        activeChallenges.put(token, content);

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

        // Trigger the challenge
        updateCertificateStatus(
                cert, CertificateStatus.PENDING, "Triggering HTTP-01 challenge verification...");
        challenge.trigger();

        // Wait for challenge to be validated
        int attempts = 0;
        while (challenge.getStatus() != Status.VALID && attempts < 30) {
            if (challenge.getStatus() == Status.INVALID) {
                String error =
                        challenge
                                .getError()
                                .flatMap(p -> p.getDetail())
                                .orElse("Challenge validation failed");
                throw new AcmeException("Challenge failed: " + error);
            }

            updateCertificateStatus(
                    cert,
                    CertificateStatus.PENDING,
                    "Waiting for challenge validation (attempt " + (attempts + 1) + "/30)...");

            Thread.sleep(3000);
            challenge.update();
            attempts++;
        }

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

    /** Get challenge response for HTTP-01 validation. This is called by the challenge endpoint. */
    public Optional<String> getChallengeResponse(String token) {
        // First check memory cache
        String content = activeChallenges.get(token);
        if (content != null) {
            return Optional.of(content);
        }

        // Fall back to database
        return certificateRepository
                .findByAcmeChallengeToken(token)
                .map(IngressCertificate::getAcmeChallengeContent);
    }

    /** Renew a certificate that is expiring soon. */
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

        // Mark as renewal pending
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

        // Reuse the same flow as initial request
        requestLetsEncryptCertificate(certificateId, userId, username);
    }

    /** Get certificates that need renewal (expiring within 30 days). */
    public List<IngressCertificate> getCertificatesNeedingRenewal() {
        long thirtyDaysFromNow = System.currentTimeMillis() + Duration.ofDays(30).toMillis();
        return certificateRepository.findByStatusAndExpiresAtBefore(
                CertificateStatus.ACTIVE, thirtyDaysFromNow);
    }

    /** Retry a failed certificate request. */
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

        // Reset status and retry
        cert.setStatus(CertificateStatus.PENDING);
        cert.setStatusMessage("Retrying certificate request...");
        cert.setLastRenewalError(null);
        certificateRepository.save(cert);

        requestLetsEncryptCertificate(certificateId, userId, username);
    }

    /** Delete a certificate. */
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

        // Remove certificate files from nginx container if they exist
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

                String certDir = "/etc/nginx/certs/" + hostname;
                api.execCommand(config.getNginxContainerId(), "rm", "-rf", certDir);
                log.info("Removed certificate files for {} from nginx", hostname);
            }
        } catch (Exception e) {
            log.warn("Failed to remove certificate files from nginx: {}", e.getMessage());
        }

        // Clean up any active challenges
        if (cert.getAcmeChallengeToken() != null) {
            activeChallenges.remove(cert.getAcmeChallengeToken());
        }

        // Delete from database
        certificateRepository.delete(cert);

        auditLog(
                config,
                IngressAction.CERTIFICATE_DELETED,
                "Certificate deleted for " + hostname,
                userId,
                username);

        log.info("Certificate deleted for {}", hostname);
    }

    /** Set auto-renewal flag for a certificate. */
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

    /** Upload a custom certificate. */
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
            // Parse and validate certificate
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate x509Cert =
                    (X509Certificate)
                            cf.generateCertificate(
                                    new java.io.ByteArrayInputStream(certificatePem.getBytes()));

            // Validate hostname matches
            // (In production, do proper SAN/CN validation)

            cert.setCertificatePem(certificatePem);
            cert.setPrivateKeyPem(privateKeyPem);
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

            // Deploy to nginx
            deployCertificateToNginx(cert, config);

            return cert;

        } catch (Exception e) {
            log.error("Failed to upload certificate: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upload certificate: " + e.getMessage(), e);
        }
    }

    /** Deploy a certificate to the nginx container. */
    private void deployCertificateToNginx(IngressCertificate cert, IngressConfig config) {
        try {
            DockerHost host =
                    hostRepository
                            .findById(config.getDockerHostId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Host not found: " + config.getDockerHostId()));

            DockerAPI api =
                    dockerService.dockerAPI(
                            dockerService.createClientCached(host.getDockerHostUrl()));

            String containerId = config.getNginxContainerId();
            String hostname = cert.getHostname();

            // Create certificate directory
            String certDir = "/etc/nginx/certs/" + hostname;
            api.execCommand(containerId, "mkdir", "-p", certDir);

            // Write certificate files using heredoc through sh
            String fullchain = cert.getCertificatePem();
            if (cert.getChainPem() != null && !cert.getChainPem().isEmpty()) {
                fullchain += "\n" + cert.getChainPem();
            }

            // Write certificate file
            api.execCommand(
                    containerId,
                    "sh",
                    "-c",
                    "cat > " + certDir + "/fullchain.pem << 'CERTEOF'\n" + fullchain + "\nCERTEOF");

            // Write private key file
            api.execCommand(
                    containerId,
                    "sh",
                    "-c",
                    "cat > "
                            + certDir
                            + "/privkey.pem << 'KEYEOF'\n"
                            + cert.getPrivateKeyPem()
                            + "\nKEYEOF");

            // Set proper permissions
            api.execCommand(containerId, "chmod", "600", certDir + "/privkey.pem");
            api.execCommand(containerId, "chmod", "644", certDir + "/fullchain.pem");

            // Regenerate nginx config to enable HTTPS
            regenerateNginxConfig(config, host);

            // Reload nginx
            api.execCommand(containerId, "nginx", "-s", "reload");

            log.info("Certificate deployed to nginx for {}", hostname);

        } catch (Exception e) {
            log.error("Failed to deploy certificate to nginx: {}", e.getMessage(), e);
            // Don't throw - certificate is still valid, just not deployed
        }
    }

    private void regenerateNginxConfig(IngressConfig config, DockerHost host) {
        List<IngressRoute> routes =
                routeRepository.findByIngressConfigIdAndEnabled(config.getId(), true);
        List<IngressCertificate> certs =
                certificateRepository.findByIngressConfigId(config.getId());

        java.util.Map<String, IngressCertificate> certMap =
                certs.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        IngressCertificate::getHostname, c -> c, (a, b) -> a));

        String nginxConfig = nginxConfigGenerator.generateFullConfig(config, routes, certMap);
        config.setCurrentNginxConfig(nginxConfig);
        configRepository.save(config);

        // Apply config to container
        try {
            DockerAPI api =
                    dockerService.dockerAPI(
                            dockerService.createClientCached(host.getDockerHostUrl()));
            String containerId = config.getNginxContainerId();

            // Write nginx.conf
            api.execCommand(
                    containerId,
                    "sh",
                    "-c",
                    "cat > /etc/nginx/nginx.conf << 'CONFEOF'\n" + nginxConfig + "\nCONFEOF");

            // Test config
            String testResult = api.execCommand(containerId, "nginx", "-t");
            log.debug("Nginx config test: {}", testResult);

        } catch (Exception e) {
            log.error("Failed to apply nginx config: {}", e.getMessage(), e);
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

    private KeyPair getOrCreateAccountKeyPair(String configId) {
        return accountKeyPairs.computeIfAbsent(
                configId,
                k -> {
                    try {
                        return KeyPairUtils.createKeyPair(2048);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to create account key pair", e);
                    }
                });
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
