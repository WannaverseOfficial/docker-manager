package com.wannaverse.controllers;

import com.wannaverse.dto.AuditLogResponse;
import com.wannaverse.persistence.AuditLog;
import com.wannaverse.persistence.AuditLogRepository;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.security.SecurityContext;
import com.wannaverse.security.SecurityContextHolder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    private void requireAdmin() {
        SecurityContext ctx = SecurityContextHolder.getContext();
        if (ctx == null || !ctx.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
    }

    @GetMapping
    @RequirePermission(resource = Resource.AUDIT_LOGS, action = "list")
    public ResponseEntity<Map<String, Object>> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime endDate,
            @RequestParam(required = false) String search) {

        requireAdmin();

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));

        Resource resourceTypeEnum = null;
        if (resourceType != null && !resourceType.isEmpty()) {
            try {
                resourceTypeEnum = Resource.valueOf(resourceType);
            } catch (IllegalArgumentException e) {
                // Invalid resource type, ignore filter
            }
        }

        Instant startInstant = startDate != null ? startDate.toInstant(ZoneOffset.UTC) : null;
        Instant endInstant = endDate != null ? endDate.toInstant(ZoneOffset.UTC) : null;

        Page<AuditLog> logsPage =
                auditLogRepository.findWithFilters(
                        userId,
                        action,
                        resourceTypeEnum,
                        startInstant,
                        endInstant,
                        search,
                        pageable);

        List<AuditLogResponse> logs =
                logsPage.getContent().stream()
                        .map(AuditLogResponse::fromEntity)
                        .collect(Collectors.toList());

        return ResponseEntity.ok(
                Map.of(
                        "content", logs,
                        "totalElements", logsPage.getTotalElements(),
                        "totalPages", logsPage.getTotalPages(),
                        "number", logsPage.getNumber(),
                        "size", logsPage.getSize()));
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = Resource.AUDIT_LOGS, action = "read")
    public ResponseEntity<AuditLogResponse> getAuditLog(@PathVariable String id) {
        requireAdmin();

        return auditLogRepository
                .findById(id)
                .map(log -> ResponseEntity.ok(AuditLogResponse.fromEntity(log)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/actions")
    @RequirePermission(resource = Resource.AUDIT_LOGS, action = "list")
    public ResponseEntity<List<String>> getDistinctActions() {
        requireAdmin();
        return ResponseEntity.ok(auditLogRepository.findDistinctActions());
    }

    @GetMapping("/resource-types")
    @RequirePermission(resource = Resource.AUDIT_LOGS, action = "list")
    public ResponseEntity<List<String>> getResourceTypes() {
        requireAdmin();
        return ResponseEntity.ok(
                Arrays.stream(Resource.values()).map(Enum::name).collect(Collectors.toList()));
    }
}
