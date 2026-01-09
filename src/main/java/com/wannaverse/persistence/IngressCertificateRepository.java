package com.wannaverse.persistence;

import com.wannaverse.persistence.IngressCertificate.CertificateStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IngressCertificateRepository extends JpaRepository<IngressCertificate, String> {
    List<IngressCertificate> findByIngressConfigId(String ingressConfigId);

    Optional<IngressCertificate> findByIngressConfigIdAndHostname(
            String ingressConfigId, String hostname);

    List<IngressCertificate> findByStatus(CertificateStatus status);

    List<IngressCertificate> findByStatusAndExpiresAtBefore(
            CertificateStatus status, long timestamp);

    List<IngressCertificate> findByExpiresAtBetween(long start, long end);

    Optional<IngressCertificate> findByAcmeChallengeToken(String token);

    int countByIngressConfigId(String ingressConfigId);

    int countByIngressConfigIdAndStatus(String ingressConfigId, CertificateStatus status);
}
