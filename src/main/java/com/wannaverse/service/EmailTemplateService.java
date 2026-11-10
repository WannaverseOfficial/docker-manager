package com.wannaverse.service;

import com.wannaverse.persistence.NotificationEventType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds notification emails in the same style as the web app: a white card on a light grey page,
 * the docker.manager wordmark, a status line, a short explanation, the details as label and value
 * rows, and one button to the relevant page. Every email has a plain-text version too, sent
 * alongside the HTML as multipart/alternative.
 *
 * <p>Layout uses tables and inline styles, which every mail client renders (Outlook desktop ignores
 * most CSS otherwise). Clients that support it switch to dark colours.
 */
@Service
public class EmailTemplateService {

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH)
                    .withZone(ZoneId.systemDefault());

    // The web app's light theme.
    private static final String FOG = "#fafafa";
    private static final String PAPER = "#ffffff";
    private static final String WELL = "#f0f0f0";
    private static final String LINE = "#e4e4e4";
    private static final String INK = "#18181b";
    private static final String SLATE = "#5f5f66";
    private static final String FONT =
            "Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO = "'JetBrains Mono',SFMono-Regular,Menlo,Consolas,monospace";

    /** The HTML and plain-text versions of one email. */
    public record Email(String html, String text) {}

    private enum Tone {
        OK("#1d7a4f"),
        DANGER("#b4233c"),
        WARN("#9a5a06"),
        LIVE("#c98f00"),
        INFO("#5f5f66");

        final String color;

        Tone(String color) {
            this.color = color;
        }
    }

    private record Message(
            Tone tone,
            String status,
            String title,
            String intro,
            List<String[]> rows,
            String detail,
            String actionLabel,
            String actionPath) {}

    public Email render(NotificationEventType eventType, Map<String, Object> ctx) {
        return build(message(eventType, ctx));
    }

    /** The message sent by "Send test email" in Settings. */
    public Email testEmail(String recipient) {
        return build(
                new Message(
                        Tone.OK,
                        "Email is working",
                        "Test email",
                        "Docker Manager can send email through your SMTP server. Notifications"
                                + " about deployments, containers and hosts will arrive like this.",
                        List.of(
                                new String[] {"Sent to", recipient},
                                new String[] {"Sent at", formatTimestamp(Instant.now())}),
                        null,
                        "Open Docker Manager",
                        "/"));
    }

    public String getSubject(NotificationEventType eventType, Map<String, Object> ctx) {
        String repo = str(ctx, "repoName", "a repository");
        String container = str(ctx, "containerName", "a container");
        String host = str(ctx, "hostName", "a host");
        return switch (eventType) {
            case DEPLOYMENT_STARTED -> "Deploying " + repo;
            case DEPLOYMENT_COMPLETED -> repo + " deployed";
            case DEPLOYMENT_FAILED -> "Deployment of " + repo + " failed";
            case CONTAINER_STOPPED_UNEXPECTEDLY -> container + " stopped unexpectedly";
            case CONTAINER_HEALTH_CHECK_FAILED -> container + " is failing its health check";
            case CONTAINER_RESTART_LOOP -> container + " keeps restarting";
            case CONTAINER_OOM_KILLED -> container + " ran out of memory";
            case USER_LOGIN -> "New sign-in to Docker Manager";
            case PASSWORD_CHANGED -> "Your Docker Manager password was changed";
            case HOST_DISCONNECTED -> "Lost connection to " + host;
            case HOST_RECONNECTED -> host + " is reachable again";
        };
    }

    private Message message(NotificationEventType type, Map<String, Object> ctx) {
        String repo = str(ctx, "repoName", "Unknown repository");
        String branch = str(ctx, "branch", "main");
        String container = str(ctx, "containerName", "Unknown container");
        String host = str(ctx, "hostName", "Unknown host");
        String when = formatTimestamp(ctx.get("timestamp"));
        String repoPath =
                ctx.get("repoId") != null ? "/git/view/?id=" + ctx.get("repoId") : "/git/";
        String containerPath =
                ctx.get("containerId") != null
                        ? "/containers/view/?id=" + ctx.get("containerId")
                        : "/containers/";

        return switch (type) {
            case DEPLOYMENT_STARTED ->
                    new Message(
                            Tone.LIVE,
                            "Deploying",
                            "Deploying " + repo,
                            "A deployment of " + repo + " has started.",
                            rows(
                                    "Repository",
                                    repo,
                                    "Branch",
                                    branch,
                                    "Commit",
                                    shortSha(str(ctx, "commitSha", "")),
                                    "Started by",
                                    trigger(str(ctx, "triggerType", ""))),
                            null,
                            "Follow the deployment",
                            repoPath);
            case DEPLOYMENT_COMPLETED ->
                    new Message(
                            Tone.OK,
                            "Deployed",
                            repo + " is deployed",
                            "The deployment finished and the new version is running.",
                            rows(
                                    "Repository", repo,
                                    "Branch", branch,
                                    "Commit", shortSha(str(ctx, "commitSha", "")),
                                    "Took", str(ctx, "duration", ""),
                                    "Finished", when),
                            null,
                            "View the deployment",
                            repoPath);
            case DEPLOYMENT_FAILED ->
                    new Message(
                            Tone.DANGER,
                            "Deployment failed",
                            "Deployment of " + repo + " failed",
                            "The previous version is still running. The error is below, and the"
                                    + " full log is in Docker Manager.",
                            rows(
                                    "Repository", repo,
                                    "Branch", branch,
                                    "Commit", shortSha(str(ctx, "commitSha", "")),
                                    "Failed", when),
                            str(ctx, "errorMessage", "No error message was recorded."),
                            "Open the log",
                            repoPath);
            case CONTAINER_STOPPED_UNEXPECTEDLY ->
                    new Message(
                            Tone.DANGER,
                            "Stopped",
                            container + " stopped unexpectedly",
                            "The container exited with an error. Its logs usually say why.",
                            rows("Container", container, "Host", host, "When", when),
                            null,
                            "View the logs",
                            containerPath);
            case CONTAINER_HEALTH_CHECK_FAILED ->
                    new Message(
                            Tone.WARN,
                            "Unhealthy",
                            container + " is failing its health check",
                            "The container is running, but its own health check reports a"
                                    + " problem. It may not be answering requests.",
                            rows("Container", container, "Host", host, "Since", when),
                            null,
                            "View the container",
                            containerPath);
            case CONTAINER_RESTART_LOOP ->
                    new Message(
                            Tone.DANGER,
                            "Restart loop",
                            container + " keeps restarting",
                            "It crashes soon after starting, and Docker keeps starting it again."
                                    + " The logs from just before each restart usually show the"
                                    + " cause.",
                            rows(
                                    "Container", container,
                                    "Host", host,
                                    "Restarts", str(ctx, "restartCount", ""),
                                    "Detected", when),
                            null,
                            "View the logs",
                            containerPath);
            case CONTAINER_OOM_KILLED ->
                    new Message(
                            Tone.DANGER,
                            "Out of memory",
                            container + " ran out of memory",
                            "The system stopped it because it used more memory than it is allowed."
                                    + " Raise its memory limit or find out what uses the memory.",
                            rows("Container", container, "Host", host, "When", when),
                            null,
                            "View the container",
                            containerPath);
            case USER_LOGIN ->
                    new Message(
                            Tone.INFO,
                            "New sign-in",
                            "Someone signed in to your account",
                            "If this was you, there's nothing to do. If not, change your password"
                                    + " now.",
                            rows("From", str(ctx, "ipAddress", "Unknown address"), "When", when),
                            null,
                            "Review your account",
                            "/account/");
            case PASSWORD_CHANGED ->
                    new Message(
                            Tone.INFO,
                            "Password changed",
                            "Your password was changed",
                            "You've been signed out everywhere else. If you didn't change it,"
                                    + " contact an administrator straight away.",
                            rows("When", when),
                            null,
                            "Open your account",
                            "/account/");
            case HOST_DISCONNECTED ->
                    new Message(
                            Tone.DANGER,
                            "Disconnected",
                            "Lost connection to " + host,
                            "Docker Manager can't reach this Docker host. Containers on it keep"
                                    + " running, but can't be managed until it's back.",
                            rows("Host", host, "Address", str(ctx, "hostUrl", ""), "Since", when),
                            null,
                            "Check the host",
                            "/hosts/");
            case HOST_RECONNECTED ->
                    new Message(
                            Tone.OK,
                            "Reconnected",
                            host + " is reachable again",
                            "Docker Manager is connected to this host again.",
                            rows("Host", host, "Address", str(ctx, "hostUrl", ""), "When", when),
                            null,
                            "Open the host",
                            "/hosts/");
        };
    }

    // --- rendering ---------------------------------------------------------------------------

    private Email build(Message m) {
        String url = link(m.actionPath());
        return new Email(html(m, url), text(m, url));
    }

    private String html(Message m, String actionUrl) {
        StringBuilder rows = new StringBuilder();
        for (String[] r : m.rows()) {
            rows.append(
                            "<tr><td class=\"dm-line dm-slate\" style=\"padding:10px"
                                    + " 0;border-top:1px solid ")
                    .append(LINE)
                    .append(";font-size:13px;color:")
                    .append(SLATE)
                    .append(";width:34%;vertical-align:top;\">")
                    .append(esc(r[0]))
                    .append(
                            "</td><td class=\"dm-line dm-ink\" style=\"padding:10px"
                                    + " 0;border-top:1px solid ")
                    .append(LINE)
                    .append(";font-size:14px;color:")
                    .append(INK)
                    .append(";vertical-align:top;word-break:break-word;\">")
                    .append(esc(r[1]))
                    .append("</td></tr>");
        }
        String detail =
                m.detail() == null
                        ? ""
                        : "<tr><td style=\"padding:4px 0 8px;\"><div class=\"dm-well\""
                                + " style=\"background:"
                                + WELL
                                + ";border-radius:10px;padding:12px 14px;font-family:"
                                + MONO
                                + ";font-size:12.5px;line-height:1.55;color:"
                                + INK
                                + ";white-space:pre-wrap;word-break:break-word;\">"
                                + esc(m.detail())
                                + "</div></td></tr>";
        String button =
                "<tr><td style=\"padding:20px 0 4px;\"><a class=\"dm-button\" href=\""
                        + esc(actionUrl)
                        + "\" style=\"display:inline-block;background:"
                        + INK
                        + ";color:"
                        + FOG
                        + ";text-decoration:none;font-size:14px;font-weight:500;line-height:20px;"
                        + "padding:10px 18px;border-radius:999px;\">"
                        + esc(m.actionLabel())
                        + "</a></td></tr>";

        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light dark\">"
                + "<meta name=\"supported-color-schemes\" content=\"light dark\">"
                + "<title>"
                + esc(m.title())
                + "</title><style>"
                + "@media (prefers-color-scheme: dark){"
                + ".dm-page{background:#0b0b0c !important}"
                + ".dm-card{background:#161618 !important;border-color:#2e2e32 !important}"
                + ".dm-ink{color:#ececee !important}"
                + ".dm-slate{color:#a8a8ae !important}"
                + ".dm-line{border-color:#2e2e32 !important}"
                + ".dm-well{background:#222225 !important;color:#ececee !important}"
                + ".dm-button{background:#ececee !important;color:#0a0a0a !important}}"
                + "@media (max-width:520px){.dm-pad{padding:24px 20px !important}}"
                + "</style></head>"
                + "<body class=\"dm-page\" style=\"margin:0;padding:0;background:"
                + FOG
                + ";\">"
                // Preview text in the inbox list, hidden in the message itself.
                + "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">"
                + esc(m.intro())
                + "</div>"
                + "<table role=\"presentation\" class=\"dm-page\" width=\"100%\" cellpadding=\"0\""
                + " cellspacing=\"0\" style=\"background:"
                + FOG
                + ";font-family:"
                + FONT
                + ";\"><tr><td align=\"center\" style=\"padding:32px 16px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
                + " style=\"max-width:560px;\">"
                // Wordmark
                + "<tr><td style=\"padding:0 4px 16px;font-size:20px;font-weight:600;"
                + "letter-spacing:-0.01em;\"><span class=\"dm-ink\" style=\"color:"
                + INK
                + ";\">docker</span><span class=\"dm-slate\" style=\"color:"
                + SLATE
                + ";\">.manager</span></td></tr>"
                // Card
                + "<tr><td class=\"dm-card dm-pad\" style=\"background:"
                + PAPER
                + ";border:1px solid "
                + LINE
                + ";border-radius:16px;padding:32px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">"
                + "<tr><td class=\"dm-slate\" style=\"font-size:13px;color:"
                + SLATE
                + ";padding-bottom:10px;\"><span style=\"display:inline-block;width:8px;height:8px;"
                + "border-radius:50%;background:"
                + m.tone().color
                + ";margin-right:7px;vertical-align:middle;\"></span><span"
                + " style=\"vertical-align:middle;\">"
                + esc(m.status())
                + "</span></td></tr>"
                + "<tr><td class=\"dm-ink\" style=\"font-size:22px;line-height:1.3;font-weight:600;"
                + "letter-spacing:-0.01em;color:"
                + INK
                + ";padding-bottom:8px;word-break:break-word;\">"
                + esc(m.title())
                + "</td></tr>"
                + "<tr><td class=\"dm-slate\" style=\"font-size:15px;line-height:1.6;color:"
                + SLATE
                + ";padding-bottom:20px;\">"
                + esc(m.intro())
                + "</td></tr>"
                + detail
                + (rows.isEmpty()
                        ? ""
                        : "<tr><td><table role=\"presentation\" width=\"100%\" cellpadding=\"0\""
                                + " cellspacing=\"0\">"
                                + rows
                                + "</table></td></tr>")
                + button
                + "</table></td></tr>"
                // Footer
                + "<tr><td class=\"dm-slate\" style=\"padding:16px 4px 0;font-size:12px;"
                + "line-height:1.6;color:"
                + SLATE
                + ";\">Sent by Docker Manager at <a class=\"dm-slate\" href=\""
                + esc(baseUrl())
                + "\" style=\"color:"
                + SLATE
                + ";\">"
                + esc(displayUrl())
                + "</a>. Choose which emails you get under <a class=\"dm-slate\" href=\""
                + esc(link("/settings/"))
                + "\" style=\"color:"
                + SLATE
                + ";\">Settings, Notifications</a>.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private String text(Message m, String actionUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.status().toUpperCase(Locale.ROOT)).append("\n\n");
        sb.append(m.title()).append("\n\n");
        sb.append(m.intro()).append("\n\n");
        if (m.detail() != null) sb.append(m.detail()).append("\n\n");
        int width = 0;
        for (String[] r : m.rows()) width = Math.max(width, r[0].length());
        for (String[] r : m.rows()) {
            sb.append(String.format(Locale.ROOT, "%-" + (width + 2) + "s%s%n", r[0] + ":", r[1]));
        }
        if (!m.rows().isEmpty()) sb.append('\n');
        sb.append(m.actionLabel()).append(": ").append(actionUrl).append("\n\n");
        sb.append("--\nSent by Docker Manager at ")
                .append(baseUrl())
                .append(". Choose which emails you get under Settings, Notifications.\n");
        return sb.toString();
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Label/value pairs; pairs with an empty value are left out. */
    private static List<String[]> rows(String... pairs) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null && !pairs[i + 1].isBlank()) {
                rows.add(new String[] {pairs[i], pairs[i + 1]});
            }
        }
        return rows;
    }

    private String baseUrl() {
        String b = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl;
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private String displayUrl() {
        return baseUrl().replaceFirst("^https?://", "");
    }

    private String link(String path) {
        return baseUrl() + (path == null || path.isEmpty() ? "/" : path);
    }

    private static String str(Map<String, Object> ctx, String key, String fallback) {
        Object v = ctx == null ? null : ctx.get(key);
        String s = v == null ? "" : String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String shortSha(String sha) {
        return sha == null || sha.isBlank() ? "" : sha.substring(0, Math.min(8, sha.length()));
    }

    private static String trigger(String t) {
        return switch (t.toUpperCase(Locale.ROOT)) {
            case "WEBHOOK" -> "A push (webhook)";
            case "POLLING" -> "A new commit (polling)";
            case "MANUAL" -> "Someone in Docker Manager";
            case "" -> "";
            default -> t.charAt(0) + t.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    private static String formatTimestamp(Object timestamp) {
        if (timestamp instanceof Instant instant) return DATE_FORMAT.format(instant);
        if (timestamp instanceof Long millis)
            return DATE_FORMAT.format(Instant.ofEpochMilli(millis));
        return DATE_FORMAT.format(Instant.now());
    }

    /** Every value can be user-controlled (names, branches, error output), so all are escaped. */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
