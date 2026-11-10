package com.wannaverse.service;

import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * On a fresh install, adds the Docker daemon whose socket is mounted into this container, so the
 * app is usable straight away instead of opening on an empty "add a host" screen.
 */
@Service
public class LocalHostBootstrap {
    private static final Logger log = LoggerFactory.getLogger(LocalHostBootstrap.class);
    private static final String SOCKET = "/var/run/docker.sock";
    static final String LOCAL_URL = "unix://" + SOCKET;

    private final DockerHostRepository hostRepository;
    private final DockerService dockerService;

    public LocalHostBootstrap(DockerHostRepository hostRepository, DockerService dockerService) {
        this.hostRepository = hostRepository;
        this.dockerService = dockerService;
    }

    /** The name earlier versions gave the local host. */
    private static final String OLD_DEFAULT_NAME = "This server";

    @EventListener(ApplicationReadyEvent.class)
    public void registerLocalSocket() {
        if (!Files.exists(Path.of(SOCKET))) return;
        if (hostRepository.count() > 0) {
            renameOldDefault();
            return;
        }
        String name = localName();
        if (name == null) return;
        DockerHost host = new DockerHost();
        host.setDockerHostUrl(LOCAL_URL);
        host.setName(name);
        hostRepository.save(host);
        log.info("Added the local Docker daemon ({}) as host {}", SOCKET, name);
    }

    /** Hosts added as "This server" get the machine's name, unless someone renamed them. */
    private void renameOldDefault() {
        for (DockerHost host : hostRepository.findAll()) {
            if (LOCAL_URL.equals(host.getDockerHostUrl())
                    && OLD_DEFAULT_NAME.equals(host.getName())) {
                String name = localName();
                if (name != null) {
                    host.setName(name);
                    hostRepository.save(host);
                }
            }
        }
    }

    /**
     * The machine's hostname as Docker reports it (for example "docker-desktop" or "web-01"), or
     * null when Docker does not answer.
     */
    private String localName() {
        var client = dockerService.createClient(LOCAL_URL);
        try {
            var api = dockerService.dockerAPI(client);
            if (!api.ping()) {
                log.warn(
                        "{} is mounted but Docker did not answer. Check the socket permissions.",
                        SOCKET);
                return null;
            }
            String name = api.getHostInfo().getName();
            return name == null || name.isBlank() ? "Local" : name.trim();
        } catch (Exception e) {
            log.warn("Could not read the local Docker daemon's name: {}", e.getMessage());
            return null;
        } finally {
            dockerService.close(client);
        }
    }
}
