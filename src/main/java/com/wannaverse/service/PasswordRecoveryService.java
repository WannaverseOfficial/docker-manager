package com.wannaverse.service;

import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.UserStatusCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gets someone back in when nobody can reset their password from the Users page, for example the
 * only administrator: start Docker Manager with {@code DM_RESET_PASSWORD=<username>} and that user
 * gets a temporary password, printed in the logs. Whoever can set the container's environment
 * already controls the server, so this opens nothing new.
 *
 * <p>A marker file in the data folder remembers the reset. While the variable is still set, later
 * restarts reset again only until the user has chosen their own password, so a forgotten variable
 * doesn't keep locking them out. Removing the variable (and restarting) clears the marker.
 */
@Service
public class PasswordRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
    static final String MARKER = ".password-reset";

    private final UserRepository userRepository;
    private final UserService userService;
    private final UserStatusCache userStatusCache;
    private final String username;
    private final Path dataDir;

    public PasswordRecoveryService(
            UserRepository userRepository,
            UserService userService,
            UserStatusCache userStatusCache,
            @Value("${DM_RESET_PASSWORD:}") String username,
            @Value("${APP_DATA_DIR:database}") String dataDir) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.userStatusCache = userStatusCache;
        this.username = username == null ? "" : username.trim();
        this.dataDir = Path.of(dataDir);
    }

    // After the configuration file (ConfigFileWatcher) and the first administrator
    // (AdminInitService).
    @EventListener(ApplicationReadyEvent.class)
    @Order(30)
    public void resetIfAsked() {
        Path marker = dataDir.resolve(MARKER);
        if (username.isEmpty()) {
            deleteQuietly(marker);
            return;
        }

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.warn(
                    "DM_RESET_PASSWORD: there is no user \"{}\". Nothing was reset. Set it to an"
                            + " existing user name.",
                    username);
            return;
        }
        if (username.equals(readQuietly(marker)) && !user.isMustChangePassword()) {
            log.warn(
                    "DM_RESET_PASSWORD is still set. {}'s password was already reset and they have"
                            + " chosen a new one, so it was left alone. Remove DM_RESET_PASSWORD.",
                    username);
            return;
        }

        String temporary = userService.resetPassword(user.getId()).getTemporaryPassword();
        boolean enabled = user.isEnabled();
        if (!enabled) {
            userRepository
                    .findById(user.getId())
                    .ifPresent(
                            u -> {
                                u.setEnabled(true);
                                userRepository.save(u);
                            });
            userStatusCache.evict(user.getId());
        }
        writeQuietly(marker, username);

        log.warn("");
        log.warn("========================================");
        log.warn("PASSWORD RESET (DM_RESET_PASSWORD)");
        log.warn("========================================");
        log.warn("Username: {}", username);
        log.warn("Temporary Password: {}", temporary);
        if (!enabled) log.warn("The account was disabled and has been enabled again.");
        log.warn("Sign in with it and choose a new password, then remove DM_RESET_PASSWORD.");
        log.warn("========================================");
        log.warn("");
    }

    private static String readQuietly(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Files.readString(file, StandardCharsets.UTF_8).trim()
                    : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeQuietly(Path file, String value) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, value + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not write {}: {}", file, e.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete {}: {}", file, e.getMessage());
        }
    }
}
