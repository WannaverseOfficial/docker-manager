package com.wannaverse.service;

import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AdminInitService {
    private static final Logger log = LoggerFactory.getLogger(AdminInitService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminInitService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // After the configuration file is applied (ConfigFileWatcher), which may add administrators.
    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    @Transactional
    public void initializeAdmin() {
        if (userRepository.countByIsAdminTrue() > 0) {
            log.debug("An administrator exists, skipping admin initialization");
            return;
        }
        if (userRepository.existsByUsername("admin")) {
            log.warn(
                    "There is no administrator, and the user \"admin\" exists but is not one."
                            + " Make someone an administrator in the configuration file.");
            return;
        }

        String tempPassword = generateSecurePassword();

        User admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@localhost");
        admin.setPasswordHash(passwordEncoder.encode(tempPassword));
        admin.setAdmin(true);
        admin.setMustChangePassword(true);
        admin.setEnabled(true);

        userRepository.save(admin);

        log.info("");
        log.info("========================================");
        log.info("INITIAL ADMIN ACCOUNT CREATED");
        log.info("========================================");
        log.info("Username: admin");
        log.info("Temporary Password: {}", tempPassword);
        log.info("========================================");
        log.info("IMPORTANT: Change this password immediately!");
        log.info("========================================");
        log.info("");
    }

    private String generateSecurePassword() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
