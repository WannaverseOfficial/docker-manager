package com.wannaverse.persistence;

import com.wannaverse.persistence.IngressConfig.IngressStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IngressConfigRepository extends JpaRepository<IngressConfig, String> {
    Optional<IngressConfig> findByDockerHostId(String dockerHostId);

    boolean existsByDockerHostId(String dockerHostId);

    boolean existsByDockerHostIdAndStatusNot(String dockerHostId, IngressStatus status);

    List<IngressConfig> findByStatus(IngressStatus status);
}
