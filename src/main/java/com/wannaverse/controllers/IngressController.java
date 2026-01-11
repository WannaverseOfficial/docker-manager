package com.wannaverse.controllers;

import com.wannaverse.persistence.*;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.CertificateService;
import com.wannaverse.service.IngressService;
import com.wannaverse.service.IngressService.*;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ingress")
public class IngressController {

    private final IngressService ingressService;
    private final CertificateService certificateService;

    public IngressController(IngressService ingressService, CertificateService certificateService) {
        this.ingressService = ingressService;
        this.certificateService = certificateService;
    }

    @GetMapping("/hosts/{hostId}/status")
    @RequirePermission(resource = Resource.INGRESS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<IngressStatusResponse> getIngressStatus(@PathVariable String hostId) {
        var config = ingressService.getIngressConfig(hostId).orElse(null);
        var nginxStatus = ingressService.getNginxStatus(hostId);

        if (config == null) {
            return ResponseEntity.ok(
                    new IngressStatusResponse(hostId, "DISABLED", null, null, 0, 0, null, null));
        }

        return ResponseEntity.ok(
                new IngressStatusResponse(
                        hostId,
                        config.getStatus().name(),
                        config.getNginxContainerId(),
                        nginxStatus.containerStatus(),
                        nginxStatus.activeRoutes(),
                        nginxStatus.activeCertificates(),
                        config.getEnabledAt(),
                        config.getEnabledByUsername()));
    }

    @GetMapping("/hosts/{hostId}/config")
    @RequirePermission(resource = Resource.INGRESS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<IngressConfigResponse> getIngressConfig(@PathVariable String hostId) {
        var config = ingressService.getIngressConfig(hostId).orElse(null);

        if (config == null) {
            return ResponseEntity.ok(
                    new IngressConfigResponse(
                            null,
                            hostId,
                            "DISABLED",
                            null,
                            null,
                            null,
                            null,
                            80,
                            443,
                            8080,
                            null,
                            false,
                            null,
                            null,
                            null));
        }

        return ResponseEntity.ok(IngressConfigResponse.fromEntity(config));
    }

    @PostMapping("/hosts/{hostId}/enable/preview")
    @RequirePermission(resource = Resource.INGRESS, action = "enable", hostIdParam = "hostId")
    public ResponseEntity<EnableIngressPreview> previewEnable(
            @PathVariable String hostId, @RequestBody EnableIngressRequest request) {
        var preview = ingressService.previewEnableIngress(hostId, request);
        return ResponseEntity.ok(preview);
    }

    @PostMapping("/hosts/{hostId}/enable")
    @RequirePermission(resource = Resource.INGRESS, action = "enable", hostIdParam = "hostId")
    public ResponseEntity<EnableIngressResult> enableIngress(
            @PathVariable String hostId, @RequestBody EnableIngressRequest request) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        var result = ingressService.enableIngress(hostId, request, userId, username);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/hosts/{hostId}/disable/preview")
    @RequirePermission(resource = Resource.INGRESS, action = "disable", hostIdParam = "hostId")
    public ResponseEntity<DisableIngressPreview> previewDisable(@PathVariable String hostId) {
        var preview = ingressService.previewDisableIngress(hostId);
        return ResponseEntity.ok(preview);
    }

    @PostMapping("/hosts/{hostId}/disable")
    @RequirePermission(resource = Resource.INGRESS, action = "disable", hostIdParam = "hostId")
    public ResponseEntity<DisableIngressResult> disableIngress(@PathVariable String hostId) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        var result = ingressService.disableIngress(hostId, userId, username);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/hosts/{hostId}/routes")
    @RequirePermission(resource = Resource.INGRESS_ROUTES, action = "list", hostIdParam = "hostId")
    public ResponseEntity<List<IngressRouteResponse>> listRoutes(@PathVariable String hostId) {
        var routes =
                ingressService.getRoutes(hostId).stream()
                        .map(IngressRouteResponse::fromEntity)
                        .toList();
        return ResponseEntity.ok(routes);
    }

    @GetMapping("/routes/{routeId}")
    @RequirePermission(resource = Resource.INGRESS_ROUTES, action = "read")
    public ResponseEntity<IngressRouteResponse> getRoute(@PathVariable String routeId) {
        return ingressService
                .getRoute(routeId)
                .map(r -> ResponseEntity.ok(IngressRouteResponse.fromEntity(r)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/hosts/{hostId}/routes/preview")
    @RequirePermission(
            resource = Resource.INGRESS_ROUTES,
            action = "create",
            hostIdParam = "hostId")
    public ResponseEntity<ExposeAppPreview> previewExposeApp(
            @PathVariable String hostId, @RequestBody ExposeAppRequest request) {
        var preview = ingressService.previewExposeApp(hostId, request);
        return ResponseEntity.ok(preview);
    }

    @PostMapping("/hosts/{hostId}/routes")
    @RequirePermission(
            resource = Resource.INGRESS_ROUTES,
            action = "create",
            hostIdParam = "hostId")
    public ResponseEntity<IngressRouteResponse> createRoute(
            @PathVariable String hostId, @RequestBody ExposeAppRequest request) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        var route = ingressService.exposeApp(hostId, request, userId, username);
        return ResponseEntity.ok(IngressRouteResponse.fromEntity(route));
    }

    @DeleteMapping("/routes/{routeId}")
    @RequirePermission(resource = Resource.INGRESS_ROUTES, action = "delete")
    public ResponseEntity<Void> deleteRoute(
            @PathVariable String routeId,
            @RequestParam(defaultValue = "false") boolean disconnectFromNetwork) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        ingressService.removeRoute(routeId, disconnectFromNetwork, userId, username);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/hosts/{hostId}/nginx/config")
    @RequirePermission(resource = Resource.INGRESS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<NginxConfigResponse> getNginxConfig(@PathVariable String hostId) {
        String config = ingressService.getNginxConfig(hostId);
        var status = ingressService.getNginxStatus(hostId);
        return ResponseEntity.ok(
                new NginxConfigResponse(
                        config,
                        System.currentTimeMillis(),
                        config != null && !config.isEmpty(),
                        null));
    }

    @GetMapping("/hosts/{hostId}/nginx/status")
    @RequirePermission(resource = Resource.INGRESS, action = "read", hostIdParam = "hostId")
    public ResponseEntity<NginxStatus> getNginxStatus(@PathVariable String hostId) {
        var status = ingressService.getNginxStatus(hostId);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/hosts/{hostId}/nginx/reload")
    @RequirePermission(resource = Resource.INGRESS, action = "manage", hostIdParam = "hostId")
    public ResponseEntity<Void> reloadNginx(@PathVariable String hostId) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        ingressService.regenerateNginxConfig(hostId, userId, username);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/hosts/{hostId}/certificates")
    @RequirePermission(
            resource = Resource.INGRESS_CERTIFICATES,
            action = "list",
            hostIdParam = "hostId")
    public ResponseEntity<List<CertificateResponse>> listCertificates(@PathVariable String hostId) {
        var certs =
                ingressService.getCertificates(hostId).stream()
                        .map(CertificateResponse::fromEntity)
                        .toList();
        return ResponseEntity.ok(certs);
    }

    @GetMapping("/certificates/{certId}")
    @RequirePermission(resource = Resource.INGRESS_CERTIFICATES, action = "read")
    public ResponseEntity<CertificateResponse> getCertificate(@PathVariable String certId) {
        return certificateService
                .getCertificate(certId)
                .map(c -> ResponseEntity.ok(CertificateResponse.fromEntity(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/certificates/{certId}/request")
    @RequirePermission(resource = Resource.INGRESS_CERTIFICATES, action = "manage")
    public ResponseEntity<Void> requestCertificate(@PathVariable String certId) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        certificateService.requestLetsEncryptCertificate(certId, userId, username);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/certificates/{certId}/retry")
    @RequirePermission(resource = Resource.INGRESS_CERTIFICATES, action = "manage")
    public ResponseEntity<Void> retryCertificateRequest(@PathVariable String certId) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        certificateService.retryCertificateRequest(certId, userId, username);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/certificates/{certId}/renew")
    @RequirePermission(resource = Resource.INGRESS_CERTIFICATES, action = "manage")
    public ResponseEntity<Void> renewCertificate(@PathVariable String certId) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        certificateService.renewCertificate(certId, userId, username);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/certificates/{certId}/upload")
    @RequirePermission(resource = Resource.INGRESS_CERTIFICATES, action = "manage")
    public ResponseEntity<CertificateResponse> uploadCertificate(
            @PathVariable String certId, @RequestBody UploadCertificateRequest request) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        var cert =
                certificateService.uploadCertificate(
                        certId,
                        request.certificatePem(),
                        request.privateKeyPem(),
                        request.chainPem(),
                        userId,
                        username);
        return ResponseEntity.ok(CertificateResponse.fromEntity(cert));
    }

    @DeleteMapping("/certificates/{certId}")
    @RequirePermission(resource = Resource.INGRESS_CERTIFICATES, action = "delete")
    public ResponseEntity<Void> deleteCertificate(@PathVariable String certId) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        certificateService.deleteCertificate(certId, userId, username);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/certificates/{certId}/auto-renew")
    @RequirePermission(resource = Resource.INGRESS_CERTIFICATES, action = "manage")
    public ResponseEntity<CertificateResponse> setAutoRenew(
            @PathVariable String certId, @RequestBody AutoRenewRequest request) {
        String userId = SecurityContextHolder.getCurrentUserId();
        String username = SecurityContextHolder.getCurrentUsername();
        var cert = certificateService.setAutoRenew(certId, request.autoRenew(), userId, username);
        return ResponseEntity.ok(CertificateResponse.fromEntity(cert));
    }

    @GetMapping("/hosts/{hostId}/audit")
    @RequirePermission(resource = Resource.INGRESS, action = "audit", hostIdParam = "hostId")
    public ResponseEntity<List<IngressAuditLogResponse>> getAuditLogs(@PathVariable String hostId) {
        var logs =
                ingressService.getAuditLogs(hostId).stream()
                        .map(IngressAuditLogResponse::fromEntity)
                        .toList();
        return ResponseEntity.ok(logs);
    }

    public record IngressStatusResponse(
            String hostId,
            String status,
            String nginxContainerId,
            String nginxContainerStatus,
            int activeRouteCount,
            int certificateCount,
            Long enabledAt,
            String enabledByUsername) {}

    public record IngressConfigResponse(
            String id,
            String dockerHostId,
            String status,
            String nginxContainerId,
            String nginxContainerName,
            String ingressNetworkId,
            String ingressNetworkName,
            int httpPort,
            int httpsPort,
            int acmeProxyPort,
            String acmeEmail,
            boolean acmeEnabled,
            String acmeDirectoryUrl,
            Long enabledAt,
            String enabledByUsername) {
        public static IngressConfigResponse fromEntity(IngressConfig c) {
            return new IngressConfigResponse(
                    c.getId(),
                    c.getDockerHostId(),
                    c.getStatus().name(),
                    c.getNginxContainerId(),
                    c.getNginxContainerName(),
                    c.getIngressNetworkId(),
                    c.getIngressNetworkName(),
                    c.getHttpPort(),
                    c.getHttpsPort(),
                    c.getAcmeProxyPort(),
                    c.getAcmeEmail(),
                    c.isAcmeEnabled(),
                    c.getAcmeDirectoryUrl(),
                    c.getEnabledAt(),
                    c.getEnabledByUsername());
        }
    }

    public record IngressRouteResponse(
            String id,
            String hostname,
            String pathPrefix,
            String targetContainerId,
            String targetContainerName,
            int targetPort,
            String protocol,
            String tlsMode,
            boolean authEnabled,
            boolean enabled,
            String certificateId,
            long createdAt,
            String createdByUsername) {
        public static IngressRouteResponse fromEntity(IngressRoute r) {
            return new IngressRouteResponse(
                    r.getId(),
                    r.getHostname(),
                    r.getPathPrefix(),
                    r.getTargetContainerId(),
                    r.getTargetContainerName(),
                    r.getTargetPort(),
                    r.getProtocol().name(),
                    r.getTlsMode().name(),
                    r.isAuthEnabled(),
                    r.isEnabled(),
                    r.getCertificateId(),
                    r.getCreatedAt(),
                    r.getCreatedByUsername());
        }
    }

    public record NginxConfigResponse(
            String config, long lastUpdated, boolean valid, List<String> validationErrors) {}

    public record CertificateResponse(
            String id,
            String hostname,
            String source,
            String status,
            String statusMessage,
            String issuer,
            String subject,
            Long issuedAt,
            Long expiresAt,
            int daysUntilExpiry,
            boolean renewalNeeded,
            boolean autoRenew,
            String acmeChallengeType,
            String lastRenewalError,
            Long lastRenewalAttempt) {
        public static CertificateResponse fromEntity(IngressCertificate c) {
            return new CertificateResponse(
                    c.getId(),
                    c.getHostname(),
                    c.getSource().name(),
                    c.getStatus().name(),
                    c.getStatusMessage(),
                    c.getIssuer(),
                    c.getSubject(),
                    c.getIssuedAt(),
                    c.getExpiresAt(),
                    c.getDaysUntilExpiry(),
                    c.needsRenewal(),
                    c.isAutoRenew(),
                    c.getAcmeChallengeType() != null ? c.getAcmeChallengeType().name() : null,
                    c.getLastRenewalError(),
                    c.getLastRenewalAttempt());
        }
    }

    public record UploadCertificateRequest(
            String certificatePem, String privateKeyPem, String chainPem) {}

    public record AutoRenewRequest(boolean autoRenew) {}

    public record IngressAuditLogResponse(
            String id,
            String action,
            String resourceType,
            String resourceId,
            String details,
            boolean success,
            String errorMessage,
            String username,
            long timestamp) {
        public static IngressAuditLogResponse fromEntity(IngressAuditLog l) {
            return new IngressAuditLogResponse(
                    l.getId(),
                    l.getAction().name(),
                    l.getResourceType(),
                    l.getResourceId(),
                    l.getDetails(),
                    l.isSuccess(),
                    l.getErrorMessage(),
                    l.getUsername(),
                    l.getTimestamp());
        }
    }
}
