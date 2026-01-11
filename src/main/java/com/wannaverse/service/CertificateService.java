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
            KeyPair accountKeyPair = getOrCreateAccountKeyPair(config.getId());

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
            cert.setPrivateKeyPem(keyWriter.toString());
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
        long thirtyDaysFromNow = System.currentTimeMillis() + Duration.ofDays(30).toMillis();
        return certificateRepository.findByStatusAndExpiresAtBefore(
                CertificateStatus.ACTIVE, thirtyDaysFromNow);
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

                String certDir = "/etc/nginx/certs/" + hostname;
                api.execCommand(config.getNginxContainerId(), "rm", "-rf", certDir);
                log.info("Removed certificate files for {} from nginx", hostname);
            }
        } catch (Exception e) {
            log.warn("Failed to remove certificate files from nginx: {}", e.getMessage());
        }

        if (cert.getAcmeChallengeToken() != null) {
            activeChallenges.remove(cert.getAcmeChallengeToken());
        }

        certificateRepository.delete(cert);

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

            deployCertificateToNginx(cert, config);

            return cert;

        } catch (Exception e) {
            log.error("Failed to upload certificate: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upload certificate: " + e.getMessage(), e);
        }
    }

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
            String certDir = "/etc/nginx/certs/" + hostname;
            api.execCommand(containerId, "mkdir", "-p", certDir);

            String fullchain = cert.getCertificatePem();
            if (cert.getChainPem() != null && !cert.getChainPem().isEmpty()) {
                fullchain += "\n" + cert.getChainPem();
            }

            String base64Fullchain =
                    java.util.Base64.getEncoder().encodeToString(fullchain.getBytes());
            api.execCommand(
                    containerId,
                    "sh",
                    "-c",
                    "echo '" + base64Fullchain + "' | base64 -d > " + certDir + "/fullchain.pem");

            String base64PrivKey =
                    java.util.Base64.getEncoder()
                            .encodeToString(cert.getPrivateKeyPem().getBytes());
            api.execCommand(
                    containerId,
                    "sh",
                    "-c",
                    "echo '" + base64PrivKey + "' | base64 -d > " + certDir + "/privkey.pem");

            api.execCommand(containerId, "chmod", "600", certDir + "/privkey.pem");
            api.execCommand(containerId, "chmod", "644", certDir + "/fullchain.pem");

            regenerateNginxConfig(config, host);

            reloadOrStartNginx(api, containerId);

            log.info("Certificate deployed to nginx for {}", hostname);

        } catch (Exception e) {
            log.error("Failed to deploy certificate to nginx: {}", e.getMessage(), e);
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

        try {
            DockerAPI api =
                    dockerService.dockerAPI(
                            dockerService.createClientCached(host.getDockerHostUrl()));
            String containerId = config.getNginxContainerId();

            String base64Config =
                    java.util.Base64.getEncoder().encodeToString(nginxConfig.getBytes());
            api.execCommand(
                    containerId,
                    "sh",
                    "-c",
                    "echo '" + base64Config + "' | base64 -d > /etc/nginx/nginx.conf");

            String testResult = api.execCommand(containerId, "nginx", "-t");
            log.info("Nginx config test: {}", testResult);

        } catch (Exception e) {
            log.error("Failed to apply nginx config: {}", e.getMessage(), e);
        }
    }

    /** Reload nginx by sending HUP signal to the master process (PID 1 in container). */
    private void reloadOrStartNginx(DockerAPI api, String containerId) {
        log.info("Sending SIGHUP to nginx master process (PID 1)");
        String reloadResult = api.execCommand(containerId, "kill", "-HUP", "1");
        log.info(
                "Nginx reload result: {}",
                reloadResult.isEmpty() ? "(success - no output)" : reloadResult);
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
