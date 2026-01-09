package com.wannaverse.controllers;

import com.github.dockerjava.api.DockerClient;
import com.wannaverse.dto.DockerLinkRequest;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.service.DockerAPI;
import com.wannaverse.service.DockerService;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
public class DockerLinkController {
    private final DockerService dockerService;
    private final DockerHostRepository hostRepository;

    @Autowired
    public DockerLinkController(DockerService dockerService, DockerHostRepository hostRepository) {
        this.dockerService = dockerService;
        this.hostRepository = hostRepository;
    }

    @PostMapping("/link")
    public ResponseEntity<?> linkDockerServer(
            @RequestBody @Valid DockerLinkRequest dockerLinkRequest) {
        Optional<DockerHost> optionalDockerHost =
                hostRepository.findByDockerHostUrl(dockerLinkRequest.getDockerHostUrl());

        if (optionalDockerHost.isPresent()) {
            return ResponseEntity.ok().build();
        }

        DockerClient dockerClient =
                dockerService.createClient(dockerLinkRequest.getDockerHostUrl());
        DockerAPI dockerAPI = dockerService.dockerAPI(dockerClient);

        if (dockerAPI.ping()) {
            DockerHost dockerHost = new DockerHost();
            dockerHost.setDockerHostUrl(dockerLinkRequest.getDockerHostUrl());
            hostRepository.save(dockerHost);
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/hosts")
    public List<DockerHost> list() {
        return hostRepository.getAllByDockerHostUrlNotNull();
    }
}
