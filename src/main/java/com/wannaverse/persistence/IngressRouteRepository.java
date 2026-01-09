package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IngressRouteRepository extends JpaRepository<IngressRoute, String> {
    List<IngressRoute> findByIngressConfigId(String ingressConfigId);

    List<IngressRoute> findByIngressConfigIdAndEnabled(String ingressConfigId, boolean enabled);

    Optional<IngressRoute> findByIngressConfigIdAndHostnameAndPathPrefix(
            String ingressConfigId, String hostname, String pathPrefix);

    List<IngressRoute> findByTargetContainerId(String containerId);

    boolean existsByIngressConfigIdAndHostnameAndPathPrefix(
            String ingressConfigId, String hostname, String pathPrefix);

    int countByIngressConfigId(String ingressConfigId);

    int countByIngressConfigIdAndEnabled(String ingressConfigId, boolean enabled);
}
