package com.wannaverse.service;

import com.wannaverse.persistence.EmailLog;
import com.wannaverse.persistence.EmailLogRepository;
import com.wannaverse.persistence.NotificationEventType;
import com.wannaverse.persistence.SmtpConfiguration;
import com.wannaverse.persistence.SmtpConfigurationRepository;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final SmtpConfigurationRepository smtpConfigRepository;
    private final EmailLogRepository emailLogRepository;
    private final EncryptionService encryptionService;

    public EmailService(
            SmtpConfigurationRepository smtpConfigRepository,
            EmailLogRepository emailLogRepository,
            EncryptionService encryptionService) {
        this.smtpConfigRepository = smtpConfigRepository;
        this.emailLogRepository = emailLogRepository;
        this.encryptionService = encryptionService;
    }

    public boolean isConfigured() {
        return smtpConfigRepository.existsByEnabledTrue();
    }

    public Optional<SmtpConfiguration> getActiveConfiguration() {
        return smtpConfigRepository.findFirstByEnabledTrue();
    }

    @Async("emailExecutor")
    public CompletableFuture<Boolean> sendEmailAsync(
            String recipientEmail,
            String recipientUserId,
            String subject,
            String htmlContent,
            NotificationEventType eventType,
            String relatedResourceId) {

        EmailLog emailLog = new EmailLog();
        emailLog.setRecipientEmail(recipientEmail);
        emailLog.setRecipientUserId(recipientUserId);
        emailLog.setSubject(subject);
        emailLog.setEventType(eventType);
        emailLog.setRelatedResourceId(relatedResourceId);
        emailLog.setStatus(EmailLog.EmailStatus.PENDING);
        emailLogRepository.save(emailLog);

        try {
            Optional<SmtpConfiguration> configOpt = getActiveConfiguration();
            if (configOpt.isEmpty()) {
                emailLog.setStatus(EmailLog.EmailStatus.FAILED);
                emailLog.setErrorMessage("SMTP not configured");
                emailLogRepository.save(emailLog);
                return CompletableFuture.completedFuture(false);
            }

            SmtpConfiguration config = configOpt.get();
            boolean success = doSendEmail(config, recipientEmail, subject, htmlContent);

            if (success) {
                emailLog.setStatus(EmailLog.EmailStatus.SENT);
                emailLog.setSentAt(Instant.now());
            } else {
                emailLog.setStatus(EmailLog.EmailStatus.FAILED);
                emailLog.setErrorMessage("Failed to send email");
            }
            emailLogRepository.save(emailLog);

            return CompletableFuture.completedFuture(success);

        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", recipientEmail, e.getMessage());
            emailLog.setStatus(EmailLog.EmailStatus.FAILED);
            emailLog.setErrorMessage(truncate(e.getMessage(), 500));
            emailLogRepository.save(emailLog);
            return CompletableFuture.completedFuture(false);
        }
    }

    public boolean sendEmailSync(String recipientEmail, String subject, String htmlContent) {
        try {
            Optional<SmtpConfiguration> configOpt = getActiveConfiguration();
            if (configOpt.isEmpty()) {
                log.warn("SMTP not configured, cannot send email");
                return false;
            }

            return doSendEmail(configOpt.get(), recipientEmail, subject, htmlContent);

        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", recipientEmail, e.getMessage());
            return false;
        }
    }

    private boolean doSendEmail(
            SmtpConfiguration config, String recipientEmail, String subject, String htmlContent) {
        try {
            Session session = createMailSession(config);

            MimeMessage message = new MimeMessage(session);

            String fromName = config.getFromName();
            if (fromName != null && !fromName.isEmpty()) {
                message.setFrom(new InternetAddress(config.getFromAddress(), fromName));
            } else {
                message.setFrom(new InternetAddress(config.getFromAddress()));
            }

            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
            message.setSubject(subject);
            message.setContent(htmlContent, "text/html; charset=utf-8");

            Transport.send(message);

            log.info("Email sent successfully to {}", recipientEmail);
            return true;

        } catch (Exception e) {
            log.error("Failed to send email: {}", e.getMessage());
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }

    private Session createMailSession(SmtpConfiguration config) {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getHost());
        props.put("mail.smtp.port", String.valueOf(config.getPort()));

        switch (config.getSecurityType()) {
            case STARTTLS:
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                break;
            case SSL_TLS:
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.port", String.valueOf(config.getPort()));
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                break;
            case NONE:
            default:
                break;
        }

        if (config.getUsername() != null && !config.getUsername().isEmpty()) {
            props.put("mail.smtp.auth", "true");

            String password = "";
            if (config.getEncryptedPassword() != null && !config.getEncryptedPassword().isEmpty()) {
                password = encryptionService.decrypt(config.getEncryptedPassword());
            }

            final String finalPassword = password;
            final String username = config.getUsername();

            return Session.getInstance(
                    props,
                    new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(username, finalPassword);
                        }
                    });
        }

        return Session.getInstance(props);
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
    }
}
