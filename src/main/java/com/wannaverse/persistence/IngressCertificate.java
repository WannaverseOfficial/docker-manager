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

    @Column(columnDefinition = "TEXT")
    private String certificatePem;

    @Column(columnDefinition = "TEXT")
    private String privateKeyPem;

    @Column(columnDefinition = "TEXT")
    private String chainPem;

    private String issuer;

    private String subject;

    private String serialNumber;

    private String fingerprint;

    private Long issuedAt;

    @Column(name = "expires_at")
    private Long expiresAt;

    private String acmeOrderUrl;

    private String acmeChallengeToken;

    @Column(columnDefinition = "TEXT")
    private String acmeChallengeContent;

    @Enumerated(EnumType.STRING)
    private AcmeChallengeType acmeChallengeType;

    @Column(columnDefinition = "TEXT")
    private String statusMessage;

    private Long lastRenewalAttempt;

    private String lastRenewalError;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean autoRenew = true;

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

    public enum CertificateSource {
        LETS_ENCRYPT,
        UPLOADED,
        EXTERNAL
    }

    public enum CertificateStatus {
        PENDING,
        ACTIVE,
        EXPIRED,
        RENEWAL_PENDING,
        ERROR
    }

    public enum AcmeChallengeType {
        HTTP_01,
        DNS_01
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

    public int getDaysUntilExpiry() {
        if (expiresAt == null) {
            return -1;
        }
        long diff = expiresAt - System.currentTimeMillis();
        return (int) (diff / (1000 * 60 * 60 * 24));
    }

    public boolean needsRenewal() {
        return getDaysUntilExpiry() <= 30;
    }
}
