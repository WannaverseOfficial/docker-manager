package com.wannaverse.service;

import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;

import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DockerHostService {

    private final DockerHostRepository dockerHostRepository;
    private final DockerService dockerService;

    public DockerHostService(
            DockerHostRepository dockerHostRepository, DockerService dockerService) {
        this.dockerHostRepository = dockerHostRepository;
        this.dockerService = dockerService;
    }

    public DockerAPI getDockerAPI(String hostId) {
        DockerHost host =
                dockerHostRepository
                        .findById(hostId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Docker host not found: " + hostId));
        return dockerService.dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()));
    }

    public DockerAPI getDockerAPI(DockerHost host) {
        return dockerService.dockerAPI(dockerService.createClientCached(host.getDockerHostUrl()));
    }

    public Optional<DockerHost> findById(String hostId) {
        return dockerHostRepository.findById(hostId);
    }
}
