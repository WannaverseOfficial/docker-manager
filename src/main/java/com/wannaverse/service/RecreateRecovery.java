package com.wannaverse.service;

import com.github.dockerjava.api.model.Container;
import com.wannaverse.persistence.DockerHostRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts containers back that a recreate left behind when Docker Manager stopped in the middle of it
 * (crash, restart, host reboot).
 *
 * <p>A recreate stops the container, renames it to {@code <name>-old-<millis>}, creates and starts
 * the new one, then removes the old one. If that was interrupted, the app is down under the backup
 * name. On startup the backup gets its name back, unless a new container with that name was
 * started; a replacement that was created but never started is removed first. The backup is only
 * started again if it was stopped by the recreate, not before it.
 */
@Service
public class RecreateRecovery {
    private static final Logger log = LoggerFactory.getLogger(RecreateRecovery.class);
    private static final Pattern BACKUP = Pattern.compile("^/?(.+)-old-(\\d{13})$");

    /** Stopped within this long after the backup time means the recreate stopped it. */
    private static final long STOPPED_BY_RECREATE_MS = 5 * 60_000;

    private final DockerHostRepository hostRepository;
    private final DockerHostService dockerHostService;

    public RecreateRecovery(
            DockerHostRepository hostRepository, DockerHostService dockerHostService) {
        this.hostRepository = hostRepository;
        this.dockerHostService = dockerHostService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreInterruptedRecreates() {
        for (var host : hostRepository.findAll()) {
            try {
                restore(dockerHostService.getDockerAPI(host.getId()));
            } catch (Exception e) {
                log.debug("Skipping recreate recovery on {}: {}", host.getId(), e.getMessage());
            }
        }
    }

    void restore(DockerAPI api) {
        List<Container> all = api.listAllContainers();
        Map<String, Container> byName = new HashMap<>();
        for (Container c : all) {
            if (c.getNames() == null) continue;
            for (String n : c.getNames()) byName.put(n.replaceFirst("^/", ""), c);
        }
        for (Container backup : all) {
            if (backup.getNames() == null || backup.getNames().length == 0) continue;
            Matcher m = BACKUP.matcher(backup.getNames()[0]);
            if (!m.matches() || "running".equals(backup.getState())) continue;
            String original = m.group(1);
            long backedUpAt = Long.parseLong(m.group(2));

            Container replacement = byName.get(original);
            if (replacement != null) {
                if (!"created".equals(replacement.getState())) {
                    // A new container took over; the next cleanup can remove the backup.
                    continue;
                }
                log.warn(
                        "Removing {}: it was created by an interrupted change and never started",
                        original);
                api.client().removeContainerCmd(replacement.getId()).withForce(true).exec();
            }
            log.warn("Restoring {} after an interrupted change", original);
            api.client().renameContainerCmd(backup.getId()).withName(original).exec();
            var state = api.inspectContainer(backup.getId()).getState();
            if (stoppedByRecreate(state.getFinishedAt(), backedUpAt)) {
                api.startContainer(backup.getId());
            }
        }
    }

    static boolean stoppedByRecreate(String finishedAt, long backedUpAt) {
        if (finishedAt == null || finishedAt.startsWith("0001-")) return false;
        try {
            long finished = Instant.parse(finishedAt).toEpochMilli();
            return Math.abs(finished - backedUpAt) < STOPPED_BY_RECREATE_MS;
        } catch (Exception e) {
            return false;
        }
    }
}
