package com.wannaverse.controllers;

import com.wannaverse.persistence.Resource;
import com.wannaverse.persistence.SecurityScan;
import com.wannaverse.security.AdminOnly;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.SecurityScanService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Vulnerability scans (Trivy, Docker Scout), host checks (Docker Bench) and their fixes. */
@RestController
@AdminOnly
@RequestMapping("/api/security")
public class SecurityController {
    private final SecurityScanService scans;

    public SecurityController(SecurityScanService scans) {
        this.scans = scans;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("scout", scans.scoutAvailable());
    }

    @GetMapping("/hosts/{hostId}/scans")
    public List<SecurityScan> list(@PathVariable String hostId) {
        // The list leaves out the findings; open a scan to see them.
        return scans.list(hostId).stream().map(SecurityController::withoutFindings).toList();
    }

    @GetMapping("/scans/{id}")
    public SecurityScan get(@PathVariable String id) {
        return scans.get(id);
    }

    public record ScanRequest(String kind, String image) {}

    @PostMapping("/hosts/{hostId}/scans")
    @Auditable(resource = Resource.SECURITY, action = "security_scan", captureRequestBody = true)
    public ResponseEntity<SecurityScan> start(
            @PathVariable String hostId, @RequestBody ScanRequest request) {
        SecurityScan.Kind kind;
        try {
            kind =
                    SecurityScan.Kind.valueOf(
                            String.valueOf(request.kind()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scan type");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        scans.start(
                                hostId,
                                kind,
                                request.image(),
                                SecurityContextHolder.getCurrentUsername()));
    }

    public record FixRequest(String container, String fix, Long value) {}

    @PostMapping("/hosts/{hostId}/fix")
    @Auditable(resource = Resource.SECURITY, action = "security_fix", captureRequestBody = true)
    public Map<String, String> fix(@PathVariable String hostId, @RequestBody FixRequest request) {
        SecurityScanService.Fix fix;
        try {
            fix =
                    SecurityScanService.Fix.valueOf(
                            String.valueOf(request.fix()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown fix");
        }
        if (request.container() == null || request.container().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a container");
        }
        return Map.of("id", scans.applyFix(hostId, request.container(), fix, request.value()));
    }

    private static SecurityScan withoutFindings(SecurityScan s) {
        SecurityScan copy = new SecurityScan();
        copy.setId(s.getId());
        copy.setDockerHostId(s.getDockerHostId());
        copy.setKind(s.getKind());
        copy.setTarget(s.getTarget());
        copy.setStatus(s.getStatus());
        copy.setStartedAt(s.getStartedAt());
        copy.setFinishedAt(s.getFinishedAt());
        copy.setStartedBy(s.getStartedBy());
        copy.setSummaryJson(s.getSummaryJson());
        copy.setError(s.getError());
        return copy;
    }
}
