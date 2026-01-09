package com.wannaverse.controllers;

import com.wannaverse.dto.DiffResponse;
import com.wannaverse.persistence.*;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.service.DiffService;
import com.wannaverse.service.OperationTrackingService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@RestController
@RequestMapping("/api/diff")
public class DiffController {
    private final DiffService diffService;
    private final OperationTrackingService trackingService;
    private final StateSnapshotRepository snapshotRepository;
    private final ComposeDeploymentRepository composeRepository;
    private final DockerOperationRepository operationRepository;

    public DiffController(
            DiffService diffService,
            OperationTrackingService trackingService,
            StateSnapshotRepository snapshotRepository,
            ComposeDeploymentRepository composeRepository,
            DockerOperationRepository operationRepository) {
        this.diffService = diffService;
        this.trackingService = trackingService;
        this.snapshotRepository = snapshotRepository;
        this.composeRepository = composeRepository;
        this.operationRepository = operationRepository;
    }

    @GetMapping("/operations/{operationId}")
    @RequirePermission(resource = Resource.STATE_SNAPSHOTS, action = "read")
    public ResponseEntity<DiffResponse> getOperationDiff(@PathVariable String operationId) {
        Optional<StateSnapshot> beforeOpt =
                snapshotRepository.findByOperationIdAndSnapshotType(
                        operationId, StateSnapshot.SnapshotType.BEFORE);
        Optional<StateSnapshot> afterOpt =
                snapshotRepository.findByOperationIdAndSnapshotType(
                        operationId, StateSnapshot.SnapshotType.AFTER);

        if (beforeOpt.isEmpty() && afterOpt.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No snapshots found for operation");
        }

        StateSnapshot before = beforeOpt.orElse(null);
        StateSnapshot after = afterOpt.orElse(null);

        DiffService.DiffResult result = diffService.compareSnapshots(before, after);
        return ResponseEntity.ok(DiffResponse.fromDiffResult(result));
    }

    @GetMapping("/compose/{deploymentId1}/{deploymentId2}")
    @RequirePermission(resource = Resource.COMPOSE_DEPLOYMENTS, action = "read")
    public ResponseEntity<DiffResponse> compareComposeDeployments(
            @PathVariable String deploymentId1, @PathVariable String deploymentId2) {

        ComposeDeployment d1 =
                composeRepository
                        .findById(deploymentId1)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Deployment not found: " + deploymentId1));
        ComposeDeployment d2 =
                composeRepository
                        .findById(deploymentId2)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Deployment not found: " + deploymentId2));

        DiffService.DiffResult result =
                diffService.compareComposeContent(d1.getComposeContent(), d2.getComposeContent());

        return ResponseEntity.ok(DiffResponse.fromDiffResult(result));
    }

    @GetMapping("/snapshots/{snapshotId1}/{snapshotId2}")
    @RequirePermission(resource = Resource.STATE_SNAPSHOTS, action = "read")
    public ResponseEntity<DiffResponse> compareSnapshots(
            @PathVariable String snapshotId1, @PathVariable String snapshotId2) {

        StateSnapshot s1 =
                snapshotRepository
                        .findById(snapshotId1)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Snapshot not found: " + snapshotId1));
        StateSnapshot s2 =
                snapshotRepository
                        .findById(snapshotId2)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Snapshot not found: " + snapshotId2));

        DiffService.DiffResult result = diffService.compareSnapshots(s1, s2);
        return ResponseEntity.ok(DiffResponse.fromDiffResult(result));
    }

    @GetMapping("/compose/project/{projectName}/versions/{v1}/{v2}")
    @RequirePermission(resource = Resource.COMPOSE_DEPLOYMENTS, action = "read")
    public ResponseEntity<DiffResponse> compareComposeVersions(
            @PathVariable String projectName,
            @PathVariable int v1,
            @PathVariable int v2,
            @RequestParam String hostId) {

        ComposeDeployment d1 =
                composeRepository
                        .findByDockerHostIdAndProjectNameAndVersion(hostId, projectName, v1)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Version "
                                                        + v1
                                                        + " not found for project "
                                                        + projectName));
        ComposeDeployment d2 =
                composeRepository
                        .findByDockerHostIdAndProjectNameAndVersion(hostId, projectName, v2)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Version "
                                                        + v2
                                                        + " not found for project "
                                                        + projectName));

        DiffService.DiffResult result =
                diffService.compareComposeContent(d1.getComposeContent(), d2.getComposeContent());

        return ResponseEntity.ok(DiffResponse.fromDiffResult(result));
    }
}
