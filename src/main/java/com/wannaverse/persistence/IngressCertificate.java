package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ingress_certificates_t",
        indexes = {
            @Index(name = "idx_cert_config", columnList = "ingress_config_id"),
            @Index(name = "idx_cert_hostname", columnList = "hostname"),
            @Index(name = "idx_cert_expiry", columnList = "expires_at"),
            @Index(name = "idx_cert_status", columnList = "status")
        })
public class IngressCertificate {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "ingress_config_id", nullable = false)
    private String ingressConfigId;

    @Column(nullable = false)
    private String hostname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CertificateSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CertificateStatus status = CertificateStatus.PENDING;

    // Certificate data (encrypted at rest)
    @Column(columnDefinition = "TEXT")
    private String certificatePem;

    @Column(columnDefinition = "TEXT")
    private String privateKeyPem;

    @Column(columnDefinition = "TEXT")
    private String chainPem;

    // Certificate metadata
    private String issuer;

    private String subject;

    private String serialNumber;

    private String fingerprint;

    private Long issuedAt;

    @Column(name = "expires_at")
    private Long expiresAt;

    // ACME/Let's Encrypt tracking for transparency
    private String acmeOrderUrl;

    private String acmeChallengeToken;

    @Column(columnDefinition = "TEXT")
    private String acmeChallengeContent;

    @Enumerated(EnumType.STRING)
    private AcmeChallengeType acmeChallengeType;

    // Human readable status message for UI
    @Column(columnDefinition = "TEXT")
    private String statusMessage;

    private Long lastRenewalAttempt;

    private String lastRenewalError;

    /** Auto-renewal enabled flag. Defaults to true for Let's Encrypt certificates. */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean autoRenew = true;

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

    public enum CertificateSource {
        LETS_ENCRYPT, // Automatic from Let's Encrypt
        UPLOADED, // User uploaded certificate
        EXTERNAL // Externally managed (BYO paths)
    }

    public enum CertificateStatus {
        PENDING, // Request in progress
        ACTIVE, // Valid and in use
        EXPIRED, // Past expiry date
        RENEWAL_PENDING, // Renewal in progress
        ERROR // Something went wrong
    }

    public enum AcmeChallengeType {
        HTTP_01, // HTTP challenge
        DNS_01 // DNS challenge
    }

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = System.currentTimeMillis();
    }

    /**
     * Calculate days until certificate expiry.
     *
     * @return days until expiry, negative if already expired
     */
    public int getDaysUntilExpiry() {
        if (expiresAt == null) {
            return -1;
        }
        long diff = expiresAt - System.currentTimeMillis();
        return (int) (diff / (1000 * 60 * 60 * 24));
    }

    /** Check if certificate needs renewal (expires within 30 days). */
    public boolean needsRenewal() {
        return getDaysUntilExpiry() <= 30;
    }
}
