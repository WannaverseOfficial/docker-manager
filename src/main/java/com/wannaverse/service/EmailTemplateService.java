package com.wannaverse.service;

import com.wannaverse.persistence.NotificationEventType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
public class EmailTemplateService {

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss z").withZone(ZoneId.systemDefault());

    public String render(NotificationEventType eventType, Map<String, Object> context) {
        String content =
                switch (eventType) {
                    case DEPLOYMENT_STARTED -> renderDeploymentStarted(context);
                    case DEPLOYMENT_COMPLETED -> renderDeploymentCompleted(context);
                    case DEPLOYMENT_FAILED -> renderDeploymentFailed(context);
                    case CONTAINER_STOPPED_UNEXPECTEDLY -> renderContainerStopped(context);
                    case CONTAINER_HEALTH_CHECK_FAILED -> renderHealthCheckFailed(context);
                    case CONTAINER_RESTART_LOOP -> renderContainerRestartLoop(context);
                    case CONTAINER_OOM_KILLED -> renderContainerOomKilled(context);
                    case USER_LOGIN -> renderUserLogin(context);
                    case PASSWORD_CHANGED -> renderPasswordChanged(context);
                    case HOST_DISCONNECTED -> renderHostDisconnected(context);
                    case HOST_RECONNECTED -> renderHostReconnected(context);
                };
        return wrapInTemplate(eventType.getDisplayName(), content);
    }

    public String getSubject(NotificationEventType eventType, Map<String, Object> context) {
        return switch (eventType) {
            case DEPLOYMENT_STARTED ->
                    "Deployment Started: " + context.getOrDefault("repoName", "Unknown");
            case DEPLOYMENT_COMPLETED ->
                    "Deployment Successful: " + context.getOrDefault("repoName", "Unknown");
            case DEPLOYMENT_FAILED ->
                    "Deployment Failed: " + context.getOrDefault("repoName", "Unknown");
            case CONTAINER_STOPPED_UNEXPECTEDLY ->
                    "Alert: Container Stopped - "
                            + context.getOrDefault("containerName", "Unknown");
            case CONTAINER_HEALTH_CHECK_FAILED ->
                    "Alert: Health Check Failed - "
                            + context.getOrDefault("containerName", "Unknown");
            case CONTAINER_RESTART_LOOP ->
                    "Alert: Restart Loop Detected - "
                            + context.getOrDefault("containerName", "Unknown");
            case CONTAINER_OOM_KILLED ->
                    "Alert: Container OOM Killed - "
                            + context.getOrDefault("containerName", "Unknown");
            case USER_LOGIN -> "New Login to Docker Manager";
            case PASSWORD_CHANGED -> "Password Changed Successfully";
            case HOST_DISCONNECTED ->
                    "Alert: Docker Host Disconnected - "
                            + context.getOrDefault("hostName", "Unknown");
            case HOST_RECONNECTED ->
                    "Docker Host Reconnected - " + context.getOrDefault("hostName", "Unknown");
        };
    }

    private String wrapInTemplate(String title, String content) {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>%s</title>
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                    line-height: 1.6;
                    margin: 0;
                    padding: 0;
                    background-color: #f5f5f5;
                }
                .container {
                    max-width: 600px;
                    margin: 0 auto;
                    background-color: #ffffff;
                }
                .header {
                    background: linear-gradient(135deg, #1a1c22 0%%, #2d3139 100%%);
                    padding: 32px 24px;
                    text-align: center;
                }
                .header h1 {
                    color: #a0c4ff;
                    margin: 0;
                    font-size: 24px;
                    font-weight: 600;
                }
                .header .subtitle {
                    color: #9ca3af;
                    font-size: 14px;
                    margin-top: 4px;
                }
                .content {
                    padding: 32px 24px;
                }
                .content h2 {
                    color: #1f2937;
                    margin: 0 0 16px 0;
                    font-size: 20px;
                }
                .content p {
                    color: #4b5563;
                    margin: 0 0 16px 0;
                }
                .info-box {
                    background-color: #f9fafb;
                    border: 1px solid #e5e7eb;
                    border-radius: 8px;
                    padding: 16px;
                    margin: 16px 0;
                }
                .info-row {
                    display: flex;
                    justify-content: space-between;
                    padding: 8px 0;
                    border-bottom: 1px solid #e5e7eb;
                }
                .info-row:last-child {
                    border-bottom: none;
                }
                .info-label {
                    color: #6b7280;
                    font-size: 14px;
                }
                .info-value {
                    color: #1f2937;
                    font-weight: 500;
                    font-size: 14px;
                }
                .status-success {
                    color: #059669;
                    font-weight: 600;
                }
                .status-failed {
                    color: #dc2626;
                    font-weight: 600;
                }
                .status-warning {
                    color: #d97706;
                    font-weight: 600;
                }
                .status-info {
                    color: #2563eb;
                    font-weight: 600;
                }
                .button {
                    display: inline-block;
                    padding: 12px 24px;
                    background-color: #a0c4ff;
                    color: #1a1c22;
                    text-decoration: none;
                    border-radius: 6px;
                    font-weight: 600;
                    margin-top: 16px;
                }
                .button:hover {
                    background-color: #7fb3ff;
                }
                .alert-box {
                    background-color: #fef2f2;
                    border: 1px solid #fecaca;
                    border-left: 4px solid #dc2626;
                    border-radius: 8px;
                    padding: 16px;
                    margin: 16px 0;
                }
                .alert-box.warning {
                    background-color: #fffbeb;
                    border-color: #fde68a;
                    border-left-color: #d97706;
                }
                .alert-box.success {
                    background-color: #ecfdf5;
                    border-color: #a7f3d0;
                    border-left-color: #059669;
                }
                .footer {
                    background-color: #f9fafb;
                    padding: 24px;
                    text-align: center;
                    border-top: 1px solid #e5e7eb;
                }
                .footer p {
                    color: #9ca3af;
                    font-size: 12px;
                    margin: 0 0 8px 0;
                }
                .footer a {
                    color: #6b7280;
                    text-decoration: underline;
                }
                code {
                    background-color: #f3f4f6;
                    padding: 2px 6px;
                    border-radius: 4px;
                    font-family: 'SF Mono', Monaco, Consolas, monospace;
                    font-size: 13px;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <h1>Docker Manager</h1>
                    <div class="subtitle">Notification</div>
                </div>
                <div class="content">
                    %s
                </div>
                <div class="footer">
                    <p>This is an automated notification from Docker Manager.</p>
                    <p><a href="%s/#notification-preferences">Manage notification preferences</a></p>
                </div>
            </div>
        </body>
        </html>
        """
                .formatted(title, content, baseUrl);
    }

    private String renderDeploymentStarted(Map<String, Object> context) {
        String repoName = (String) context.getOrDefault("repoName", "Unknown");
        String branch = (String) context.getOrDefault("branch", "main");
        String triggerType = (String) context.getOrDefault("triggerType", "MANUAL");
        String commitSha = (String) context.getOrDefault("commitSha", "");
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Deployment Started</h2>
        <p>A new deployment has been initiated for your repository.</p>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Repository</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Branch</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Trigger</span>
                <span class="info-value">%s</span>
            </div>
            %s
            <div class="info-row">
                <span class="info-label">Started At</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Status</span>
                <span class="info-value status-info">In Progress</span>
            </div>
        </div>
        <a href="%s/#git-repos" class="button">View Deployment</a>
        """
                .formatted(
                        repoName,
                        branch,
                        triggerType,
                        commitSha.isEmpty()
                                ? ""
                                : "<div class=\"info-row\"><span"
                                        + " class=\"info-label\">Commit</span><span"
                                        + " class=\"info-value\"><code>"
                                        + commitSha.substring(0, Math.min(8, commitSha.length()))
                                        + "</code></span></div>",
                        timestamp,
                        baseUrl);
    }

    private String renderDeploymentCompleted(Map<String, Object> context) {
        String repoName = (String) context.getOrDefault("repoName", "Unknown");
        String branch = (String) context.getOrDefault("branch", "main");
        String duration = (String) context.getOrDefault("duration", "Unknown");
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Deployment Successful</h2>
        <div class="alert-box success">
            <strong>Great news!</strong> Your deployment completed successfully.
        </div>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Repository</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Branch</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Duration</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Completed At</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Status</span>
                <span class="info-value status-success">Success</span>
            </div>
        </div>
        <a href="%s/#git-repos" class="button">View Details</a>
        """
                .formatted(repoName, branch, duration, timestamp, baseUrl);
    }

    private String renderDeploymentFailed(Map<String, Object> context) {
        String repoName = (String) context.getOrDefault("repoName", "Unknown");
        String branch = (String) context.getOrDefault("branch", "main");
        String errorMessage = (String) context.getOrDefault("errorMessage", "Unknown error");
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Deployment Failed</h2>
        <div class="alert-box">
            <strong>Attention Required!</strong> Your deployment has failed.
        </div>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Repository</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Branch</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Failed At</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Status</span>
                <span class="info-value status-failed">Failed</span>
            </div>
        </div>
        <p><strong>Error:</strong></p>
        <div class="info-box">
            <code>%s</code>
        </div>
        <a href="%s/#git-repos" class="button">View Logs</a>
        """
                .formatted(repoName, branch, timestamp, escapeHtml(errorMessage), baseUrl);
    }

    private String renderContainerStopped(Map<String, Object> context) {
        String containerName = (String) context.getOrDefault("containerName", "Unknown");
        String containerId = (String) context.getOrDefault("containerId", "");
        String hostName = (String) context.getOrDefault("hostName", "Unknown");
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Container Stopped Unexpectedly</h2>
        <div class="alert-box warning">
            <strong>Warning!</strong> A container has stopped unexpectedly.
        </div>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Container</span>
                <span class="info-value">%s</span>
            </div>
            %s
            <div class="info-row">
                <span class="info-label">Host</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Stopped At</span>
                <span class="info-value">%s</span>
            </div>
        </div>
        <a href="%s/#containers" class="button">View Containers</a>
        """
                .formatted(
                        containerName,
                        containerId.isEmpty()
                                ? ""
                                : "<div class=\"info-row\"><span class=\"info-label\">Container"
                                        + " ID</span><span class=\"info-value\"><code>"
                                        + containerId.substring(
                                                0, Math.min(12, containerId.length()))
                                        + "</code></span></div>",
                        hostName,
                        timestamp,
                        baseUrl);
    }

    private String renderHealthCheckFailed(Map<String, Object> context) {
        String containerName = (String) context.getOrDefault("containerName", "Unknown");
        String hostName = (String) context.getOrDefault("hostName", "Unknown");
        String healthStatus = (String) context.getOrDefault("healthStatus", "Unhealthy");
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Health Check Failed</h2>
        <div class="alert-box">
            <strong>Alert!</strong> A container health check has failed.
        </div>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Container</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Host</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Health Status</span>
                <span class="info-value status-failed">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Detected At</span>
                <span class="info-value">%s</span>
            </div>
        </div>
        <a href="%s/#containers" class="button">View Container</a>
        """
                .formatted(containerName, hostName, healthStatus, timestamp, baseUrl);
    }

    private String renderContainerRestartLoop(Map<String, Object> context) {
        String containerName = (String) context.getOrDefault("containerName", "Unknown");
        String containerId = (String) context.getOrDefault("containerId", "");
        String hostName = (String) context.getOrDefault("hostName", "Unknown");
        Object restartCountObj = context.get("restartCount");
        String restartCount = restartCountObj != null ? restartCountObj.toString() : "Multiple";
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Container Restart Loop Detected</h2>
        <div class="alert-box">
            <strong>Critical Alert!</strong> A container is stuck in a restart loop.
        </div>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Container</span>
                <span class="info-value">%s</span>
            </div>
            %s
            <div class="info-row">
                <span class="info-label">Host</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Restart Count</span>
                <span class="info-value status-failed">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Detected At</span>
                <span class="info-value">%s</span>
            </div>
        </div>
        <p>The container has restarted multiple times in a short period. This usually indicates a configuration issue or application crash.</p>
        <a href="%s/#container-health" class="button">View Container Health</a>
        """
                .formatted(
                        containerName,
                        containerId.isEmpty()
                                ? ""
                                : "<div class=\"info-row\"><span class=\"info-label\">Container"
                                        + " ID</span><span class=\"info-value\"><code>"
                                        + containerId.substring(
                                                0, Math.min(12, containerId.length()))
                                        + "</code></span></div>",
                        hostName,
                        restartCount,
                        timestamp,
                        baseUrl);
    }

    private String renderContainerOomKilled(Map<String, Object> context) {
        String containerName = (String) context.getOrDefault("containerName", "Unknown");
        String containerId = (String) context.getOrDefault("containerId", "");
        String hostName = (String) context.getOrDefault("hostName", "Unknown");
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Container Killed - Out of Memory</h2>
        <div class="alert-box">
            <strong>Critical Alert!</strong> A container was terminated due to memory exhaustion.
        </div>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Container</span>
                <span class="info-value">%s</span>
            </div>
            %s
            <div class="info-row">
                <span class="info-label">Host</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Reason</span>
                <span class="info-value status-failed">OOM Killed</span>
            </div>
            <div class="info-row">
                <span class="info-label">Killed At</span>
                <span class="info-value">%s</span>
            </div>
        </div>
        <p>The container exceeded its memory limit and was killed by the OOM killer. Consider increasing the memory limit or optimizing the application.</p>
        <a href="%s/#container-health" class="button">View Container Health</a>
        """
                .formatted(
                        containerName,
                        containerId.isEmpty()
                                ? ""
                                : "<div class=\"info-row\"><span class=\"info-label\">Container"
                                        + " ID</span><span class=\"info-value\"><code>"
                                        + containerId.substring(
                                                0, Math.min(12, containerId.length()))
                                        + "</code></span></div>",
                        hostName,
                        timestamp,
                        baseUrl);
    }

    private String renderUserLogin(Map<String, Object> context) {
        String ipAddress = (String) context.getOrDefault("ipAddress", "Unknown");
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>New Login Detected</h2>
        <p>A new login was detected on your Docker Manager account.</p>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">IP Address</span>
                <span class="info-value"><code>%s</code></span>
            </div>
            <div class="info-row">
                <span class="info-label">Time</span>
                <span class="info-value">%s</span>
            </div>
        </div>
        <p>If this wasn't you, please change your password immediately and contact your administrator.</p>
        """
                .formatted(ipAddress, timestamp);
    }

    private String renderPasswordChanged(Map<String, Object> context) {
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Password Changed</h2>
        <div class="alert-box success">
            Your password has been successfully changed.
        </div>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Changed At</span>
                <span class="info-value">%s</span>
            </div>
        </div>
        <p>If you did not make this change, please contact your administrator immediately.</p>
        """
                .formatted(timestamp);
    }

    private String renderHostDisconnected(Map<String, Object> context) {
        String hostName = (String) context.getOrDefault("hostName", "Unknown");
        String hostUrl = (String) context.getOrDefault("hostUrl", "Unknown");
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Docker Host Disconnected</h2>
        <div class="alert-box">
            <strong>Alert!</strong> A Docker host has become unreachable.
        </div>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Host</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">URL</span>
                <span class="info-value"><code>%s</code></span>
            </div>
            <div class="info-row">
                <span class="info-label">Disconnected At</span>
                <span class="info-value">%s</span>
            </div>
        </div>
        <p>Please check the Docker host and network connectivity.</p>
        <a href="%s/#hosts" class="button">View Hosts</a>
        """
                .formatted(hostName, hostUrl, timestamp, baseUrl);
    }

    private String renderHostReconnected(Map<String, Object> context) {
        String hostName = (String) context.getOrDefault("hostName", "Unknown");
        String timestamp = formatTimestamp(context.get("timestamp"));

        return """
        <h2>Docker Host Reconnected</h2>
        <div class="alert-box success">
            <strong>Good news!</strong> A Docker host is back online.
        </div>
        <div class="info-box">
            <div class="info-row">
                <span class="info-label">Host</span>
                <span class="info-value">%s</span>
            </div>
            <div class="info-row">
                <span class="info-label">Reconnected At</span>
                <span class="info-value">%s</span>
            </div>
        </div>
        <a href="%s/#hosts" class="button">View Hosts</a>
        """
                .formatted(hostName, timestamp, baseUrl);
    }

    private String formatTimestamp(Object timestamp) {
        if (timestamp instanceof Instant instant) {
            return DATE_FORMAT.format(instant);
        } else if (timestamp instanceof Long epochMillis) {
            return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis));
        }
        return DATE_FORMAT.format(Instant.now());
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
