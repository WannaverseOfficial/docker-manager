package com.wannaverse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Knows which container Docker Manager itself runs in, so it never stops, removes or replaces
 * itself. (Stopping it from its own UI would take the UI down with nothing left to start it again;
 * deploying its own compose project would stop the very process running compose.)
 */
@Service
public class SelfContainer {
    private static final Logger log = LoggerFactory.getLogger(SelfContainer.class);
    private static final Pattern CONTAINER_ID = Pattern.compile("/containers/([0-9a-f]{64})/");
    private static final Pattern SHORT_ID = Pattern.compile("^[0-9a-f]{12}$");

    private final String id;
    private final String shortId;

    public SelfContainer() {
        String found = null;
        // Docker bind-mounts /etc/hostname etc. from /var/lib/docker/containers/<id>/.
        try {
            Path mountinfo = Path.of("/proc/self/mountinfo");
            if (Files.isReadable(mountinfo)) {
                Matcher m = CONTAINER_ID.matcher(Files.readString(mountinfo));
                if (m.find()) found = m.group(1);
            }
        } catch (Exception e) {
            log.debug("Could not read /proc/self/mountinfo: {}", e.getMessage());
        }
        String hostname = System.getenv("HOSTNAME");
        this.id = found;
        this.shortId =
                found != null
                        ? found.substring(0, 12)
                        : hostname != null && SHORT_ID.matcher(hostname).matches()
                                ? hostname
                                : null;
        if (shortId != null) {
            log.info("Running in container {}", shortId);
        } else if (Files.exists(Path.of("/.dockerenv"))) {
            log.warn(
                    "Running in a container, but couldn't tell which one. Docker Manager can't"
                            + " protect its own container from being stopped or removed.");
        }
    }

    /** True when the container with this full ID is Docker Manager itself. */
    public boolean is(String containerId) {
        if (containerId == null || shortId == null) return false;
        return id != null ? id.equals(containerId) : containerId.startsWith(shortId);
    }

    /** Refuses an action on Docker Manager's own container. */
    public void protect(String containerId, String action) {
        if (is(containerId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This is Docker Manager itself. It can't "
                            + action
                            + " its own container: that would take it offline with nothing to"
                            + " bring it back. Do it on the server instead, for example with"
                            + " docker compose.");
        }
    }

    /** True when these labels belong to Docker Manager's own compose project's container. */
    public boolean isOwnProject(String project, Map<String, String> selfLabels) {
        return project != null
                && selfLabels != null
                && project.equals(selfLabels.get(ContainerSpecService.COMPOSE_PROJECT_LABEL));
    }

    public String id() {
        return id;
    }

    public String shortId() {
        return shortId;
    }
}
