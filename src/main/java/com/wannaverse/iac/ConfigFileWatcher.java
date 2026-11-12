package com.wannaverse.iac;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Applies DM_CONFIG_FILE at startup and whenever it changes, and every few minutes puts back
 * anything managed that was removed by other means (for example a container deleted with the Docker
 * CLI). Without a file, the periodic check uses the document last applied from the page.
 */
@Component
public class ConfigFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigFileWatcher.class);

    /** Ordered after the local host is added (0), before the first administrator is made (20). */
    public static final int STARTUP_ORDER = 10;

    private final ConfigService config;
    private final long reconcileMillis;
    private final long startupWaitMinutes;
    private volatile String lastFileHash;
    private volatile String pendingHash;
    private volatile long lastReconcile = System.currentTimeMillis();
    private volatile boolean started;

    public ConfigFileWatcher(
            ConfigService config,
            @Value("${app.config.reconcile-seconds:300}") long reconcileSeconds,
            @Value("${app.config.startup-wait-minutes:15}") long startupWaitMinutes) {
        this.config = config;
        this.reconcileMillis = reconcileSeconds * 1000;
        this.startupWaitMinutes = startupWaitMinutes;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(STARTUP_ORDER)
    public void applyAtStartup() {
        try {
            if (!config.fileMode()) return;
            Optional<String> text = config.readFile();
            if (text.isEmpty()) {
                log.error(
                        "DM_CONFIG_FILE is set to {} but it can't be read; nothing was applied",
                        config.file());
                return;
            }
            lastFileHash = ConfigParser.sha256(text.get());
            log.info("Applying the configuration file {}", config.file());
            ConfigRun run =
                    config.start(
                            text.get(),
                            ConfigService.SOURCE_FILE,
                            null,
                            ConfigService.FILE_ACTOR,
                            false);
            config.await(run, startupWaitMinutes);
            if (run != null) {
                log.info("Configuration file applied: {}", run.status);
            } else {
                log.info("Configuration file: nothing to change");
            }
        } catch (RuntimeException e) {
            log.error("Could not apply the configuration file", e);
        } finally {
            lastReconcile = System.currentTimeMillis();
            started = true;
        }
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 10_000)
    public void tick() {
        if (!started && config.fileMode()) return;
        try {
            if (config.fileMode()) {
                Optional<String> text = config.readFile();
                if (text.isPresent()) {
                    String hash = ConfigParser.sha256(text.get());
                    if (!hash.equals(lastFileHash)) {
                        // Wait until the file reads the same twice in a row (10 seconds apart),
                        // so a file caught halfway through being written is never applied: a
                        // truncated file could still be valid YAML and remove everything after
                        // the cut.
                        if (!hash.equals(pendingHash)) {
                            pendingHash = hash;
                            return;
                        }
                        pendingHash = null;
                        lastFileHash = hash;
                        lastReconcile = System.currentTimeMillis();
                        log.info("The configuration file changed; applying it");
                        config.start(
                                text.get(),
                                ConfigService.SOURCE_FILE,
                                null,
                                ConfigService.FILE_ACTOR,
                                false);
                        return;
                    }
                }
            }
            if (reconcileMillis > 0
                    && System.currentTimeMillis() - lastReconcile >= reconcileMillis) {
                lastReconcile = System.currentTimeMillis();
                config.reconcile(false, null);
            }
        } catch (org.springframework.web.server.ResponseStatusException e) {
            // An apply is already running; try again on the next tick.
            lastFileHash = null;
        } catch (RuntimeException e) {
            log.warn("Configuration check failed: {}", e.getMessage());
        }
    }
}
