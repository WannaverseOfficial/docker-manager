package com.wannaverse.security;

import java.util.Set;
import java.util.regex.Pattern;

public final class InputValidator {

    private InputValidator() {}

    private static final Pattern DOCKER_HOST_UNIX =
            Pattern.compile("^unix:///[a-zA-Z0-9/_.-]+\\.sock$");
    private static final Pattern DOCKER_HOST_TCP =
            Pattern.compile("^tcp://[a-zA-Z0-9.-]+(:[0-9]{1,5})?$");
    private static final Pattern DOCKER_HOST_SSH =
            Pattern.compile("^ssh://[a-zA-Z0-9._@-]+(:[0-9]{1,5})?(/.*)?$");

    private static final Pattern CONTAINER_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$");
    private static final Pattern IMAGE_NAME =
            Pattern.compile("^[a-zA-Z0-9._/-]+(:[a-zA-Z0-9._-]+)?(@sha256:[a-f0-9]+)?$");

    private static final Pattern HOSTNAME =
            Pattern.compile(
                    "^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$");

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

    private static final Pattern DANGEROUS_PATTERNS =
            Pattern.compile(
                    ".*("
                            + "\\|"
                            + "|&&"
                            + "|\\|\\|"
                            + "|;"
                            + "|`"
                            + "|\\$\\("
                            + "|>"
                            + "|<"
                            + "|\\.\\./"
                            + ").*");

    public static boolean isValidDockerHostUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return DOCKER_HOST_UNIX.matcher(url).matches()
                || DOCKER_HOST_TCP.matcher(url).matches()
                || DOCKER_HOST_SSH.matcher(url).matches();
    }

    public static boolean isValidContainerName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            return false;
        }
        return CONTAINER_NAME.matcher(name).matches();
    }

    public static boolean isValidImageName(String name) {
        if (name == null || name.isBlank() || name.length() > 256) {
            return false;
        }
        return IMAGE_NAME.matcher(name).matches();
    }

    public static boolean isValidHostname(String hostname) {
        if (hostname == null || hostname.isBlank() || hostname.length() > 253) {
            return false;
        }
        return HOSTNAME.matcher(hostname).matches();
    }

    public static boolean isCommandSafe(String[] command) {
        if (command == null || command.length == 0) {
            return false;
        }

        String cmd = command[0].toLowerCase();

        if (BLOCKED_COMMANDS.contains(cmd)) {
            return false;
        }

        for (String arg : command) {
            if (DANGEROUS_PATTERNS.matcher(arg).matches()) {
                return false;
            }
        }

        return true;
    }

    public static String sanitizeProjectName(String name) {
        if (name == null) {
            return "project";
        }
        return name.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
    }

    public static boolean isValidEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 254) {
            return false;
        }
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    public static boolean isValidPathPrefix(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return path.startsWith("/")
                && !path.contains("..")
                && !path.contains("?")
                && !path.contains("#")
                && path.matches("^/[a-zA-Z0-9/_-]*$");
    }
}
