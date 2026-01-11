package com.wannaverse.controllers;

import com.wannaverse.dto.SmtpConfigurationRequest;
import com.wannaverse.dto.SmtpConfigurationResponse;
import com.wannaverse.dto.TestEmailRequest;
import com.wannaverse.persistence.Resource;
import com.wannaverse.persistence.SmtpConfiguration;
import com.wannaverse.persistence.SmtpConfigurationRepository;
import com.wannaverse.security.Auditable;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.security.SecurityContext;
import com.wannaverse.security.SecurityContextHolder;
import com.wannaverse.service.EmailService;
import com.wannaverse.service.EncryptionService;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/smtp")
public class SmtpController {

    private static final Logger log = LoggerFactory.getLogger(SmtpController.class);

    private final SmtpConfigurationRepository smtpConfigRepository;
    private final EmailService emailService;
    private final EncryptionService encryptionService;

    public SmtpController(
            SmtpConfigurationRepository smtpConfigRepository,
            EmailService emailService,
            EncryptionService encryptionService) {
        this.smtpConfigRepository = smtpConfigRepository;
        this.emailService = emailService;
        this.encryptionService = encryptionService;
    }

    private void requireAdmin() {
        SecurityContext ctx = SecurityContextHolder.getContext();
        if (ctx == null || !ctx.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
    }

    @GetMapping
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "read")
    public ResponseEntity<SmtpConfigurationResponse> getSmtpConfiguration() {
        requireAdmin();

        Optional<SmtpConfiguration> configOpt = smtpConfigRepository.findFirstByEnabledTrue();
        if (configOpt.isEmpty()) {
            var allConfigs = smtpConfigRepository.findAll();
            if (allConfigs.isEmpty()) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(SmtpConfigurationResponse.fromEntity(allConfigs.get(0)));
        }

        return ResponseEntity.ok(SmtpConfigurationResponse.fromEntity(configOpt.get()));
    }

    @PutMapping
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "manage")
    @Auditable(resource = Resource.NOTIFICATIONS, action = "update_smtp")
    public ResponseEntity<SmtpConfigurationResponse> updateSmtpConfiguration(
            @Valid @RequestBody SmtpConfigurationRequest request) {
        requireAdmin();

        SmtpConfiguration config =
                smtpConfigRepository.findAll().stream().findFirst().orElse(new SmtpConfiguration());

        config.setHost(request.getHost());
        config.setPort(request.getPort());
        config.setUsername(request.getUsername());
        config.setFromAddress(request.getFromAddress());
        config.setFromName(request.getFromName());
        config.setSecurityType(request.getSecurityType());
        config.setEnabled(request.isEnabled());

        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            config.setEncryptedPassword(encryptionService.encrypt(request.getPassword()));
        }

        SmtpConfiguration saved = smtpConfigRepository.save(config);
        log.info("SMTP configuration updated by admin");

        return ResponseEntity.ok(SmtpConfigurationResponse.fromEntity(saved));
    }

    @PostMapping("/test")
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "manage")
    @Auditable(resource = Resource.NOTIFICATIONS, action = "test_smtp")
    public ResponseEntity<Map<String, Object>> sendTestEmail(
            @Valid @RequestBody TestEmailRequest request) {
        requireAdmin();

        if (!emailService.isConfigured()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "SMTP is not configured or enabled"));
        }

        String testSubject = "Test Email from Docker Manager";
        String testHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                </head>
                <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background-color: #f5f5f5;">
                    <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.1);">
                        <div style="background-color: #1a1c22; padding: 24px; text-align: center;">
                            <h1 style="margin: 0; color: #a0c4ff; font-size: 24px;">Docker Manager</h1>
                        </div>
                        <div style="padding: 32px;">
                            <h2 style="color: #1a1c22; margin-top: 0;">Test Email</h2>
                            <p style="color: #666; line-height: 1.6;">
                                This is a test email to verify your SMTP configuration is working correctly.
                            </p>
                            <p style="color: #666; line-height: 1.6;">
                                If you received this email, your SMTP settings are configured properly.
                            </p>
                            <div style="background-color: #d4edda; border: 1px solid #c3e6cb; border-radius: 4px; padding: 16px; margin-top: 24px;">
                                <p style="margin: 0; color: #155724;">
                                    <strong>Status:</strong> SMTP configuration verified successfully
                                </p>
                            </div>
                        </div>
                        <div style="background-color: #f8f9fa; padding: 16px; text-align: center; border-top: 1px solid #e9ecef;">
                            <p style="margin: 0; color: #6c757d; font-size: 12px;">
                                This is an automated test message from Docker Manager
                            </p>
                        </div>
                    </div>
                </body>
                </html>
                """;

        try {
            boolean success =
                    emailService.sendEmailSync(request.getRecipientEmail(), testSubject, testHtml);

            if (success) {
                log.info("Test email sent successfully to {}", request.getRecipientEmail());
                return ResponseEntity.ok(
                        Map.of(
                                "success",
                                true,
                                "message",
                                "Test email sent successfully to " + request.getRecipientEmail()));
            } else {
                return ResponseEntity.ok(
                        Map.of("success", false, "error", "Failed to send test email"));
            }
        } catch (Exception e) {
            log.error("Failed to send test email: {}", e.getMessage());
            return ResponseEntity.ok(
                    Map.of(
                            "success",
                            false,
                            "error",
                            "Failed to send test email: " + e.getMessage()));
        }
    }

    @DeleteMapping
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "manage")
    @Auditable(resource = Resource.NOTIFICATIONS, action = "delete_smtp")
    public ResponseEntity<Void> deleteSmtpConfiguration() {
        requireAdmin();

        smtpConfigRepository.deleteAll();
        log.info("SMTP configuration deleted by admin");

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "read")
    public ResponseEntity<Map<String, Boolean>> getSmtpStatus() {
        requireAdmin();

        return ResponseEntity.ok(Map.of("configured", emailService.isConfigured()));
    }
}
