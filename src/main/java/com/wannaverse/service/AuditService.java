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
import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * A request field is redacted when its name contains any of these. Matching by substring covers
     * camelCase names (sshKey, privateKeyPem, encryptedToken) and anything that can carry
     * credentials (environment variables, compose files and .env content, exec commands).
     */
    private static final List<String> SENSITIVE_NAME_PARTS =
            List.of(
                    "password",
                    "passwd",
                    "secret",
                    "token",
                    "key",
                    "credential",
                    "auth",
                    "env",
                    "compose",
                    "command",
                    "pem",
                    "cert");

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

                // Proxy headers are already applied for trusted proxies by
                // server.forward-headers-strategy; reading them directly is spoofable.
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            log.debug("Could not determine client IP address", e);
        }
        return null;
    }

    static boolean isSensitive(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        for (String part : SENSITIVE_NAME_PARTS) {
            if (k.contains(part)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            sanitized.put(key, isSensitive(key) ? "[REDACTED]" : sanitizeValue(value));
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return sanitizeDetails((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sanitizeValue).toList();
        }
        return value;
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
    }
}
