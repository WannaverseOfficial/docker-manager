package com.wannaverse.security;

import java.util.Set;
import java.util.regex.Pattern;

/** Utility class for validating user inputs to prevent injection attacks. */
public final class InputValidator {

    private InputValidator() {}

    // Docker host URL patterns
    private static final Pattern DOCKER_HOST_UNIX =
            Pattern.compile("^unix:///[a-zA-Z0-9/_.-]+\\.sock$");
    private static final Pattern DOCKER_HOST_TCP =
            Pattern.compile("^tcp://[a-zA-Z0-9.-]+(:[0-9]{1,5})?$");
    private static final Pattern DOCKER_HOST_SSH =
            Pattern.compile("^ssh://[a-zA-Z0-9._@-]+(:[0-9]{1,5})?(/.*)?$");

    // Container and image name patterns (Docker naming rules)
    private static final Pattern CONTAINER_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$");
    private static final Pattern IMAGE_NAME =
            Pattern.compile("^[a-zA-Z0-9._/-]+(:[a-zA-Z0-9._-]+)?(@sha256:[a-f0-9]+)?$");

    // Hostname pattern for ingress routes
    private static final Pattern HOSTNAME =
            Pattern.compile(
                    "^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$");

    // Dangerous commands that should never be executed via exec
    private static final Set<String> BLOCKED_COMMANDS =
            Set.of(
                    "rm",
                    "rmdir",
                    "mkfs",
                    "dd",
                    "fdisk",
                    "parted",
                    "mount",
                    "umount",
                    "shutdown",
                    "reboot",
                    "halt",
                    "poweroff",
                    "init",
                    "systemctl",
                    "service",
                    "chmod",
                    "chown",
                    "chroot",
                    "su",
                    "sudo",
                    "passwd",
                    "useradd",
                    "userdel",
                    "groupadd",
                    "groupdel",
                    "visudo",
                    "crontab",
                    "at",
                    "nc",
                    "netcat",
                    "ncat",
                    "curl",
                    "wget",
                    "ssh",
                    "scp",
                    "sftp",
                    "ftp",
                    "telnet",
                    "python",
                    "python3",
                    "perl",
                    "ruby",
                    "php",
                    "node",
                    "bash",
                    "sh",
                    "zsh",
                    "fish",
                    "dash",
                    "ksh",
                    "csh",
                    "tcsh");

    // Dangerous patterns in command arguments
    private static final Pattern DANGEROUS_PATTERNS =
            Pattern.compile(
                    ".*("
                            + "\\|" // pipe
                            + "|&&" // and
                            + "|\\|\\|" // or
                            + "|;" // semicolon
                            + "|`" // backticks
                            + "|\\$\\(" // command substitution
                            + "|>" // redirect
                            + "|<" // redirect
                            + "|\\.\\./" // path traversal
                            + ").*");

    /**
     * Validates a Docker host URL.
     *
     * @param url the Docker host URL to validate
     * @return true if the URL is valid
     */
    public static boolean isValidDockerHostUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return DOCKER_HOST_UNIX.matcher(url).matches()
                || DOCKER_HOST_TCP.matcher(url).matches()
                || DOCKER_HOST_SSH.matcher(url).matches();
    }

    /**
     * Validates a Docker container name.
     *
     * @param name the container name to validate
     * @return true if the name is valid
     */
    public static boolean isValidContainerName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            return false;
        }
        return CONTAINER_NAME.matcher(name).matches();
    }

    /**
     * Validates a Docker image name.
     *
     * @param name the image name to validate
     * @return true if the name is valid
     */
    public static boolean isValidImageName(String name) {
        if (name == null || name.isBlank() || name.length() > 256) {
            return false;
        }
        return IMAGE_NAME.matcher(name).matches();
    }

    /**
     * Validates a hostname for ingress routes.
     *
     * @param hostname the hostname to validate
     * @return true if the hostname is valid
     */
    public static boolean isValidHostname(String hostname) {
        if (hostname == null || hostname.isBlank() || hostname.length() > 253) {
            return false;
        }
        return HOSTNAME.matcher(hostname).matches();
    }

    /**
     * Validates a command to be executed in a container. Blocks dangerous commands and patterns.
     *
     * @param command the command array to validate
     * @return true if the command appears safe
     */
    public static boolean isCommandSafe(String[] command) {
        if (command == null || command.length == 0) {
            return false;
        }

        // Check the command name (first element)
        String cmd = command[0].toLowerCase();

        // Block if it's a shell command
        if (BLOCKED_COMMANDS.contains(cmd)) {
            return false;
        }

        // Check all arguments for dangerous patterns
        for (String arg : command) {
            if (DANGEROUS_PATTERNS.matcher(arg).matches()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Sanitizes a project name for docker-compose.
     *
     * @param name the project name
     * @return sanitized name safe for docker-compose
     */
    public static String sanitizeProjectName(String name) {
        if (name == null) {
            return "project";
        }
        return name.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
    }

    /**
     * Validates an email address format.
     *
     * @param email the email to validate
     * @return true if the email format is valid
     */
    public static boolean isValidEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 254) {
            return false;
        }
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    /**
     * Validates a path prefix for ingress routes.
     *
     * @param path the path prefix
     * @return true if the path is valid
     */
    public static boolean isValidPathPrefix(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        // Must start with /, no path traversal, no query strings
        return path.startsWith("/")
                && !path.contains("..")
                && !path.contains("?")
                && !path.contains("#")
                && path.matches("^/[a-zA-Z0-9/_-]*$");
    }
}
