package com.wannaverse.service;

import com.wannaverse.persistence.DeploymentJob;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.NotificationEventType;
import com.wannaverse.persistence.User;
import com.wannaverse.persistence.UserNotificationPreference;
import com.wannaverse.persistence.UserNotificationPreferenceRepository;
import com.wannaverse.persistence.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final EmailService emailService;
    private final EmailTemplateService templateService;
    private final UserNotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    public NotificationService(
            EmailService emailService,
            EmailTemplateService templateService,
            UserNotificationPreferenceRepository preferenceRepository,
            UserRepository userRepository) {
        this.emailService = emailService;
        this.templateService = templateService;
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
    }

    // ==================== Deployment Notifications ====================

    @Async("emailExecutor")
    public void notifyDeploymentStarted(DeploymentJob job) {
        if (!emailService.isConfigured()) {
            log.debug("SMTP not configured, skipping deployment started notification");
            return;
        }

        Map<String, Object> context = buildDeploymentContext(job);
        context.put("timestamp", Instant.now());

        notifyForEvent(
                NotificationEventType.DEPLOYMENT_STARTED, job.getId(), context, NotifyTarget.ALL);
    }

    @Async("emailExecutor")
    public void notifyDeploymentCompleted(DeploymentJob job) {
        if (!emailService.isConfigured()) {
            log.debug("SMTP not configured, skipping deployment completed notification");
            return;
        }

        Map<String, Object> context = buildDeploymentContext(job);
        context.put("timestamp", Instant.now());

        if (job.getStartedAt() > 0 && job.getCompletedAt() > 0) {
            Duration duration = Duration.ofMillis(job.getCompletedAt() - job.getStartedAt());
            context.put("duration", formatDuration(duration));
        }

        notifyForEvent(
                NotificationEventType.DEPLOYMENT_COMPLETED, job.getId(), context, NotifyTarget.ALL);
    }

    @Async("emailExecutor")
    public void notifyDeploymentFailed(DeploymentJob job) {
        if (!emailService.isConfigured()) {
            log.debug("SMTP not configured, skipping deployment failed notification");
            return;
        }

        Map<String, Object> context = buildDeploymentContext(job);
        context.put("timestamp", Instant.now());
        context.put(
                "errorMessage",
                job.getErrorMessage() != null ? job.getErrorMessage() : "Unknown error");

        notifyForEvent(
                NotificationEventType.DEPLOYMENT_FAILED, job.getId(), context, NotifyTarget.ALL);
    }

    private Map<String, Object> buildDeploymentContext(DeploymentJob job) {
        Map<String, Object> context = new HashMap<>();
        if (job.getGitRepository() != null) {
            context.put("repoName", job.getGitRepository().getName());
            context.put("branch", job.getGitRepository().getBranch());
        }
        context.put(
                "triggerType",
                job.getTriggerType() != null ? job.getTriggerType().name() : "MANUAL");
        context.put("commitSha", job.getCommitSha() != null ? job.getCommitSha() : "");
        return context;
    }

    // ==================== Container Notifications ====================

    @Async("emailExecutor")
    public void notifyContainerStoppedUnexpectedly(
            String hostId, String hostName, String containerId, String containerName) {
        if (!emailService.isConfigured()) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        context.put("containerId", containerId);
        context.put("containerName", containerName);
        context.put("hostName", hostName);
        context.put("timestamp", Instant.now());

        notifyForEvent(
                NotificationEventType.CONTAINER_STOPPED_UNEXPECTEDLY,
                containerId,
                context,
                NotifyTarget.ADMINS);
    }

    @Async("emailExecutor")
    public void notifyContainerHealthCheckFailed(
            String hostId,
            String hostName,
            String containerId,
            String containerName,
            String healthStatus) {
        if (!emailService.isConfigured()) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        context.put("containerId", containerId);
        context.put("containerName", containerName);
        context.put("hostName", hostName);
        context.put("healthStatus", healthStatus);
        context.put("timestamp", Instant.now());

        notifyForEvent(
                NotificationEventType.CONTAINER_HEALTH_CHECK_FAILED,
                containerId,
                context,
                NotifyTarget.ADMINS);
    }

    // Overload for health monitor service
    public void notifyContainerHealthCheckFailed(
            DockerHost host, String containerId, String containerName) {
        notifyContainerHealthCheckFailed(
                host.getId(), host.getId(), containerId, containerName, "Unhealthy");
    }

    // Overload for health monitor service
    public void notifyContainerStopped(DockerHost host, String containerId, String containerName) {
        notifyContainerStoppedUnexpectedly(host.getId(), host.getId(), containerId, containerName);
    }

    @Async("emailExecutor")
    public void notifyContainerRestartLoop(
            DockerHost host, String containerId, String containerName, int restartCount) {
        if (!emailService.isConfigured()) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        context.put("containerId", containerId);
        context.put("containerName", containerName);
        context.put("hostName", host.getId());
        context.put("restartCount", restartCount);
        context.put("timestamp", Instant.now());

        notifyForEvent(
                NotificationEventType.CONTAINER_RESTART_LOOP,
                containerId,
                context,
                NotifyTarget.ADMINS);
    }

    @Async("emailExecutor")
    public void notifyContainerOomKilled(
            DockerHost host, String containerId, String containerName) {
        if (!emailService.isConfigured()) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        context.put("containerId", containerId);
        context.put("containerName", containerName);
        context.put("hostName", host.getId());
        context.put("timestamp", Instant.now());

        notifyForEvent(
                NotificationEventType.CONTAINER_OOM_KILLED,
                containerId,
                context,
                NotifyTarget.ADMINS);
    }

    // ==================== Security Notifications ====================

    @Async("emailExecutor")
    public void notifyUserLogin(User user, String ipAddress) {
        if (!emailService.isConfigured() || user.getEmail() == null || user.getEmail().isEmpty()) {
            return;
        }

        // Check if user has this notification enabled
        if (!isNotificationEnabledForUser(user.getId(), NotificationEventType.USER_LOGIN)) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        context.put("ipAddress", ipAddress);
        context.put("timestamp", Instant.now());

        String html = templateService.render(NotificationEventType.USER_LOGIN, context);
        String subject = templateService.getSubject(NotificationEventType.USER_LOGIN, context);

        emailService.sendEmailAsync(
                user.getEmail(),
                user.getId(),
                subject,
                html,
                NotificationEventType.USER_LOGIN,
                user.getId());
    }

    @Async("emailExecutor")
    public void notifyPasswordChanged(User user) {
        if (!emailService.isConfigured() || user.getEmail() == null || user.getEmail().isEmpty()) {
            return;
        }

        // Check if user has this notification enabled
        if (!isNotificationEnabledForUser(user.getId(), NotificationEventType.PASSWORD_CHANGED)) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        context.put("timestamp", Instant.now());

        String html = templateService.render(NotificationEventType.PASSWORD_CHANGED, context);
        String subject =
                templateService.getSubject(NotificationEventType.PASSWORD_CHANGED, context);

        emailService.sendEmailAsync(
                user.getEmail(),
                user.getId(),
                subject,
                html,
                NotificationEventType.PASSWORD_CHANGED,
                user.getId());
    }

    // ==================== System Notifications ====================

    @Async("emailExecutor")
    public void notifyHostDisconnected(DockerHost host) {
        if (!emailService.isConfigured()) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        context.put("hostName", host.getId());
        context.put("hostUrl", host.getDockerHostUrl());
        context.put("timestamp", Instant.now());

        notifyForEvent(
                NotificationEventType.HOST_DISCONNECTED,
                host.getId(),
                context,
                NotifyTarget.ADMINS);
    }

    @Async("emailExecutor")
    public void notifyHostReconnected(DockerHost host) {
        if (!emailService.isConfigured()) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        context.put("hostName", host.getId());
        context.put("hostUrl", host.getDockerHostUrl());
        context.put("timestamp", Instant.now());

        notifyForEvent(
                NotificationEventType.HOST_RECONNECTED, host.getId(), context, NotifyTarget.ADMINS);
    }

    // ==================== Helper Methods ====================

    private enum NotifyTarget {
        ALL,
        ADMINS
    }

    private void notifyForEvent(
            NotificationEventType eventType,
            String resourceId,
            Map<String, Object> context,
            NotifyTarget target) {

        List<User> usersToNotify;

        if (target == NotifyTarget.ADMINS) {
            usersToNotify = userRepository.findByIsAdminTrue();
        } else {
            usersToNotify = userRepository.findAll();
        }

        for (User user : usersToNotify) {
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                continue;
            }

            if (!isNotificationEnabledForUser(user.getId(), eventType)) {
                continue;
            }

            try {
                String html = templateService.render(eventType, context);
                String subject = templateService.getSubject(eventType, context);

                emailService.sendEmailAsync(
                        user.getEmail(), user.getId(), subject, html, eventType, resourceId);

            } catch (Exception e) {
                log.error(
                        "Failed to send {} notification to user {}: {}",
                        eventType,
                        user.getId(),
                        e.getMessage());
            }
        }
    }

    private boolean isNotificationEnabledForUser(String userId, NotificationEventType eventType) {
        return preferenceRepository
                .findByUserIdAndEventType(userId, eventType)
                .map(UserNotificationPreference::isEmailEnabled)
                .orElse(true); // Default to enabled if no preference exists
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return seconds + " seconds";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return minutes + " min " + secs + " sec";
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return hours + " hr " + minutes + " min";
        }
    }
}
