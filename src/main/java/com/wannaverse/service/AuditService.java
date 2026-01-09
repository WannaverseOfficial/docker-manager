package com.wannaverse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannaverse.persistence.AuditLog;
import com.wannaverse.persistence.AuditLogRepository;
import com.wannaverse.persistence.Resource;
import com.wannaverse.security.SecurityContext;
import com.wannaverse.security.SecurityContextHolder;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Set<String> SENSITIVE_FIELDS =
            Set.of(
                    "password",
                    "token",
                    "sshKey",
                    "encryptedToken",
                    "encryptedSshKey",
                    "passwordHash",
                    "secret",
                    "apiKey",
                    "accessToken",
                    "refreshToken");

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = new ObjectMapper();
    }

    public void logAction(
            String action,
            Resource resourceType,
            String resourceId,
            Map<String, Object> details,
            boolean success,
            String errorMessage) {
        try {
            AuditLog auditLog = new AuditLog();

            SecurityContext ctx = SecurityContextHolder.getContext();
            if (ctx != null && ctx.isAuthenticated()) {
                auditLog.setUserId(ctx.getUserId());
                auditLog.setUsername(ctx.getUsername());
            }

            auditLog.setAction(action);
            auditLog.setResourceType(resourceType);
            auditLog.setResourceId(resourceId);
            auditLog.setIpAddress(getClientIpAddress());
            auditLog.setSuccess(success);
            auditLog.setErrorMessage(truncate(errorMessage, 500));

            if (details != null && !details.isEmpty()) {
                try {
                    Map<String, Object> sanitized = sanitizeDetails(details);
                    auditLog.setDetails(objectMapper.writeValueAsString(sanitized));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize audit details", e);
                    auditLog.setDetails("{}");
                }
            }

            auditLogRepository.save(auditLog);

        } catch (Exception e) {
            log.error("Failed to create audit log entry", e);
        }
    }

    @Async
    public void logActionAsync(
            String action,
            Resource resourceType,
            String resourceId,
            Map<String, Object> details,
            boolean success,
            String errorMessage) {
        logAction(action, resourceType, resourceId, details, success, errorMessage);
    }

    public void logSuccess(
            String action, Resource resourceType, String resourceId, Map<String, Object> details) {
        logAction(action, resourceType, resourceId, details, true, null);
    }

    public void logFailure(
            String action,
            Resource resourceType,
            String resourceId,
            Map<String, Object> details,
            String errorMessage) {
        logAction(action, resourceType, resourceId, details, false, errorMessage);
    }

    private String getClientIpAddress() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();

                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                    return xForwardedFor.split(",")[0].trim();
                }

                String xRealIp = request.getHeader("X-Real-IP");
                if (xRealIp != null && !xRealIp.isEmpty()) {
                    return xRealIp;
                }

                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            log.debug("Could not determine client IP address", e);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (SENSITIVE_FIELDS.contains(key.toLowerCase())) {
                sanitized.put(key, "[REDACTED]");
            } else if (value instanceof Map) {
                sanitized.put(key, sanitizeDetails((Map<String, Object>) value));
            } else {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
    }
}
