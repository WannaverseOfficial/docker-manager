package com.wannaverse.config;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Validates security-critical configuration at startup. Fails fast if required secrets are not
 * properly configured.
 *
 * <p>This validator is disabled in the "dev" profile to allow local development without setting up
 * secrets. In production, always ensure proper secrets are configured.
 */
@Component
@Profile("!dev")
public class SecurityConfigurationValidator {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfigurationValidator.class);

    private static final int MIN_SECRET_LENGTH = 32;
    private static final String PLACEHOLDER_PREFIX = "change-this1";

    @Value("${app.encryption.key}")
    private String encryptionKey;

    @Value("${app.jwt.access-secret}")
    private String jwtAccessSecret;

    @Value("${app.jwt.refresh-secret}")
    private String jwtRefreshSecret;

    @PostConstruct
    public void validateSecurityConfiguration() {
        validateSecret("ENCRYPTION_KEY (app.encryption.key)", encryptionKey);
        validateSecret("JWT_ACCESS_SECRET (app.jwt.access-secret)", jwtAccessSecret);
        validateSecret("JWT_REFRESH_SECRET (app.jwt.refresh-secret)", jwtRefreshSecret);

        // Ensure access and refresh secrets are different
        if (jwtAccessSecret.equals(jwtRefreshSecret)) {
            throw new SecurityConfigurationException(
                    "JWT_ACCESS_SECRET and JWT_REFRESH_SECRET must be different values");
        }

        log.info("Security configuration validated successfully");
    }

    private void validateSecret(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new SecurityConfigurationException(name + " is not configured");
        }

        if (value.toLowerCase().startsWith(PLACEHOLDER_PREFIX)) {
            throw new SecurityConfigurationException(
                    name
                            + " contains placeholder value. "
                            + "Set a secure random value in environment variables for production.");
        }

        if (value.length() < MIN_SECRET_LENGTH) {
            throw new SecurityConfigurationException(
                    name + " must be at least " + MIN_SECRET_LENGTH + " characters long");
        }
    }

    public static class SecurityConfigurationException extends RuntimeException {
        public SecurityConfigurationException(String message) {
            super("SECURITY CONFIGURATION ERROR: " + message);
        }
    }
}
