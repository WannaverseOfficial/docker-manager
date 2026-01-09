package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SmtpConfigurationRepository extends JpaRepository<SmtpConfiguration, String> {

    Optional<SmtpConfiguration> findFirstByEnabledTrue();

    boolean existsByEnabledTrue();
}
