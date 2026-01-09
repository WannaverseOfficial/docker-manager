package com.wannaverse.service;

import com.wannaverse.persistence.IngressCertificate;
import com.wannaverse.persistence.IngressCertificate.CertificateSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Scheduled service that checks for certificates needing renewal and automatically renews them.
 * Runs daily at 2 AM.
 */
@Service
public class CertificateRenewalScheduler {
    private static final Logger log = LoggerFactory.getLogger(CertificateRenewalScheduler.class);

    private static final String SYSTEM_USER_ID = "system";
    private static final String SYSTEM_USERNAME = "Auto-Renewal";

    private final CertificateService certificateService;

    public CertificateRenewalScheduler(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    /** Check for expiring certificates daily at 2 AM and renew them. */
    @Scheduled(cron = "0 0 2 * * *")
    public void checkAndRenewCertificates() {
        log.info("Starting scheduled certificate renewal check");

        List<IngressCertificate> certsNeedingRenewal =
                certificateService.getCertificatesNeedingRenewal();

        if (certsNeedingRenewal.isEmpty()) {
            log.info("No certificates need renewal");
            return;
        }

        log.info("Found {} certificates needing renewal", certsNeedingRenewal.size());

        for (IngressCertificate cert : certsNeedingRenewal) {
            // Only auto-renew Let's Encrypt certificates with autoRenew enabled
            if (cert.getSource() == CertificateSource.LETS_ENCRYPT) {
                if (!cert.isAutoRenew()) {
                    log.info(
                            "Skipping auto-renewal for {} (auto-renew disabled, expires in {}"
                                    + " days)",
                            cert.getHostname(),
                            cert.getDaysUntilExpiry());
                    continue;
                }

                log.info(
                        "Auto-renewing certificate for {} (expires in {} days)",
                        cert.getHostname(),
                        cert.getDaysUntilExpiry());

                try {
                    certificateService.renewCertificate(
                            cert.getId(), SYSTEM_USER_ID, SYSTEM_USERNAME);
                } catch (Exception e) {
                    log.error(
                            "Failed to initiate renewal for {}: {}",
                            cert.getHostname(),
                            e.getMessage());
                }
            } else {
                log.warn(
                        "Certificate for {} expires in {} days but is not auto-renewable (source:"
                                + " {})",
                        cert.getHostname(),
                        cert.getDaysUntilExpiry(),
                        cert.getSource());
            }
        }

        log.info("Certificate renewal check completed");
    }
}
