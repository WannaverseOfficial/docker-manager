package com.wannaverse.controllers;

import com.wannaverse.dto.EmailLogResponse;
import com.wannaverse.persistence.EmailLog;
import com.wannaverse.persistence.EmailLogRepository;
import com.wannaverse.persistence.NotificationEventType;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.security.SecurityContext;
import com.wannaverse.security.SecurityContextHolder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/email-logs")
public class EmailLogController {

    private final EmailLogRepository emailLogRepository;

    public EmailLogController(EmailLogRepository emailLogRepository) {
        this.emailLogRepository = emailLogRepository;
    }

    private void requireAdmin() {
        SecurityContext ctx = SecurityContextHolder.getContext();
        if (ctx == null || !ctx.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
    }

    @GetMapping
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "read")
    public ResponseEntity<Map<String, Object>> getEmailLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventType) {
        requireAdmin();

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));

        EmailLog.EmailStatus statusFilter = null;
        if (status != null && !status.isEmpty()) {
            try {
                statusFilter = EmailLog.EmailStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
            }
        }

        NotificationEventType eventTypeFilter = null;
        if (eventType != null && !eventType.isEmpty()) {
            try {
                eventTypeFilter = NotificationEventType.valueOf(eventType);
            } catch (IllegalArgumentException e) {
            }
        }

        Page<EmailLog> logsPage;
        if (statusFilter != null || eventTypeFilter != null) {
            logsPage = emailLogRepository.findWithFilters(statusFilter, eventTypeFilter, pageable);
        } else {
            logsPage = emailLogRepository.findAllOrderByCreatedAtDesc(pageable);
        }

        List<EmailLogResponse> logs =
                logsPage.getContent().stream()
                        .map(EmailLogResponse::fromEntity)
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
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "read")
    public ResponseEntity<EmailLogResponse> getEmailLog(@PathVariable String id) {
        requireAdmin();

        return emailLogRepository
                .findById(id)
                .map(log -> ResponseEntity.ok(EmailLogResponse.fromEntity(log)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "read")
    public ResponseEntity<Map<String, Object>> getEmailStats() {
        requireAdmin();

        Instant last24Hours = Instant.now().minus(24, ChronoUnit.HOURS);
        Instant last7Days = Instant.now().minus(7, ChronoUnit.DAYS);

        long sentLast24h =
                emailLogRepository.countByStatusAndCreatedAtAfter(
                        EmailLog.EmailStatus.SENT, last24Hours);
        long failedLast24h =
                emailLogRepository.countByStatusAndCreatedAtAfter(
                        EmailLog.EmailStatus.FAILED, last24Hours);
        long pendingLast24h =
                emailLogRepository.countByStatusAndCreatedAtAfter(
                        EmailLog.EmailStatus.PENDING, last24Hours);

        long sentLast7d =
                emailLogRepository.countByStatusAndCreatedAtAfter(
                        EmailLog.EmailStatus.SENT, last7Days);
        long failedLast7d =
                emailLogRepository.countByStatusAndCreatedAtAfter(
                        EmailLog.EmailStatus.FAILED, last7Days);

        long totalEmails = emailLogRepository.count();

        return ResponseEntity.ok(
                Map.of(
                        "last24Hours",
                                Map.of(
                                        "sent", sentLast24h,
                                        "failed", failedLast24h,
                                        "pending", pendingLast24h),
                        "last7Days", Map.of("sent", sentLast7d, "failed", failedLast7d),
                        "total", totalEmails));
    }

    @GetMapping("/statuses")
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "read")
    public ResponseEntity<List<String>> getStatuses() {
        requireAdmin();

        List<String> statuses =
                List.of(
                        EmailLog.EmailStatus.PENDING.name(),
                        EmailLog.EmailStatus.SENT.name(),
                        EmailLog.EmailStatus.FAILED.name());

        return ResponseEntity.ok(statuses);
    }
}
