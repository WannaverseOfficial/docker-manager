package com.wannaverse.config;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Last line of defence after {@link SecretsBootstrap}: refuses to start with JWT secrets that could
 * be guessed, since anyone holding them can sign in as any user.
 */
@Component
@Profile("!dev")
public class SecurityConfigurationValidator {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfigurationValidator.class);

    @Value("${app.encryption.key}")
    private String encryptionKey;

    @Value("${app.jwt.access-secret}")
    private String jwtAccessSecret;

    @Value("${app.jwt.refresh-secret}")
    private String jwtRefreshSecret;

    @PostConstruct
    public void validateSecurityConfiguration() {
        requireStrong("JWT_ACCESS_SECRET", jwtAccessSecret);
        requireStrong("JWT_REFRESH_SECRET", jwtRefreshSecret);

        if (jwtAccessSecret.equals(jwtRefreshSecret)) {
            throw new SecurityConfigurationException(
                    "JWT_ACCESS_SECRET and JWT_REFRESH_SECRET must be different values");
        }

        if (!SecretsBootstrap.isStrong(encryptionKey)) {
            // Kept on purpose (see SecretsBootstrap): replacing it would lose stored credentials.
            log.warn(
                    "ENCRYPTION_KEY is weak or public. Stored credentials are not well protected.");
        }

        log.info("Security configuration validated");
    }

    private void requireStrong(String name, String value) {
        if (!SecretsBootstrap.isStrong(value)) {
            throw new SecurityConfigurationException(
                    name
                            + " is missing, a placeholder, a published example, or shorter than "
                            + SecretsBootstrap.MIN_LENGTH
                            + " characters");
        }
    }

    public static class SecurityConfigurationException extends RuntimeException {
        public SecurityConfigurationException(String message) {
            super("SECURITY CONFIGURATION ERROR: " + message);
        }
    }
}
