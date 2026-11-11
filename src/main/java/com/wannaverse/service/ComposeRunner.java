package com.wannaverse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs `docker compose` against a Docker host. The Docker CLI and the Compose plugin are part of
 * the image (they were missing before, so every compose and git-compose deployment failed with
 * "Cannot run program docker").
 */
@Component
public class ComposeRunner {
    private static final Logger log = LoggerFactory.getLogger(ComposeRunner.class);
    private static final long TIMEOUT_MINUTES = 30;
    private static final int MAX_LINES = 5_000;
    private static final int MAX_LINE_LENGTH = 4_000;

    private final DockerRegistryService registryService;

    public ComposeRunner(
            @org.springframework.context.annotation.Lazy DockerRegistryService registryService) {
        this.registryService = registryService;
    }

    public record Result(int exitCode, List<String> output) {
        public boolean ok() {
            return exitCode == 0;
        }

        public String tail(int lines) {
            int from = Math.max(0, output.size() - lines);
            return String.join("\n", output.subList(from, output.size()));
        }
    }

    /**
     * @param dockerHostUrl the host to run against (unix://, tcp:// or ssh://)
     * @param workDir directory holding the compose file; relative paths in it resolve from here
     * @param composeFile compose file name inside workDir
     * @param envFile .env file inside workDir, or null
     * @param project compose project name
     * @param args the compose subcommand and its options, e.g. "up", "-d"
     * @param onLine receives each output line as it is printed (may be null)
     */
    public Result run(
            String dockerHostUrl,
            Path workDir,
            String composeFile,
            String envFile,
            String project,
            List<String> args,
            Consumer<String> onLine) {
        return run(dockerHostUrl, workDir, composeFile, envFile, project, null, args, onLine);
    }

    /**
     * @param projectDir the folder relative paths in the compose file resolve from (on the Docker
     *     host), or null for the compose file's own folder. Imported stacks keep their original
     *     folder, so ./data still means the data they were using.
     */
    public Result run(
            String dockerHostUrl,
            Path workDir,
            String composeFile,
            String envFile,
            String project,
            String projectDir,
            List<String> args,
            Consumer<String> onLine) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("compose");
        command.add("--ansi");
        command.add("never");
        command.add("--progress");
        command.add("plain");
        command.add("-p");
        command.add(project);
        if (composeFile != null) {
            command.add("-f");
            command.add(composeFile);
        }
        if (envFile != null) {
            command.add("--env-file");
            command.add(envFile);
        }
        if (projectDir != null && !projectDir.isBlank()) {
            command.add("--project-directory");
            command.add(projectDir);
        }
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.environment().put("DOCKER_HOST", normalizeHost(dockerHostUrl));
        // Never let a compose file read the manager's own secrets through variable substitution.
        pb.environment().remove("JWT_ACCESS_SECRET");
        pb.environment().remove("JWT_REFRESH_SECRET");
        pb.environment().remove("ENCRYPTION_KEY");
        pb.redirectErrorStream(true);
        // Compose must never wait for input (an interactive prompt would hang the job forever).
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));

        Path dockerConfig = null;
        try {
            dockerConfig = writeRegistryLogins();
            if (dockerConfig != null)
                pb.environment().put("DOCKER_CONFIG", dockerConfig.toString());
            return runProcess(pb, project, onLine);
        } finally {
            deleteQuietly(dockerConfig);
        }
    }

    /**
     * Compose reads registry logins from the Docker CLI's config.json, not from Docker Manager, so
     * private images in a stack wouldn't pull. Writes the saved logins to a private temporary
     * folder for this one run.
     */
    private Path writeRegistryLogins() {
        String json;
        try {
            json = registryService == null ? null : registryService.dockerConfigJson();
        } catch (Exception e) {
            log.warn("Could not prepare registry logins for compose: {}", e.getMessage());
            return null;
        }
        if (json == null) return null;
        try {
            Path dir;
            Path file;
            try {
                var ownerOnly =
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rwx------"));
                dir = java.nio.file.Files.createTempDirectory("dm-compose-", ownerOnly);
            } catch (UnsupportedOperationException notPosix) {
                dir = java.nio.file.Files.createTempDirectory("dm-compose-");
            }
            file = dir.resolve("config.json");
            java.nio.file.Files.writeString(file, json, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            log.warn("Could not write registry logins for compose: {}", e.getMessage());
            return null;
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var files = java.nio.file.Files.list(dir)) {
            for (Path f : files.toList()) java.nio.file.Files.deleteIfExists(f);
            java.nio.file.Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }

    private Result runProcess(ProcessBuilder pb, String project, Consumer<String> onLine) {
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.error("Could not run docker compose: {}", e.getMessage());
            String msg = "Could not run docker compose: " + e.getMessage();
            output.add(msg);
            if (onLine != null) onLine.accept(msg);
            return new Result(-1, List.copyOf(output));
        }

        // Read on a separate thread so the timeout below is enforced even when compose prints
        // nothing for a long time.
        Thread reader =
                Thread.ofVirtual()
                        .name("compose-output-" + project)
                        .start(
                                () -> {
                                    try (BufferedReader r =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            process.getInputStream(),
                                                            StandardCharsets.UTF_8))) {
                                        String line;
                                        while ((line = r.readLine()) != null) {
                                            if (line.length() > MAX_LINE_LENGTH) {
                                                line = line.substring(0, MAX_LINE_LENGTH) + "...";
                                            }
                                            synchronized (output) {
                                                output.add(line);
                                                if (output.size() > MAX_LINES) output.removeFirst();
                                            }
                                            if (onLine != null) onLine.accept(line);
                                        }
                                    } catch (IOException ignored) {
                                        // The process was killed or its output closed.
                                    }
                                });
        try {
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                reader.join(5_000);
                String msg = "Timed out after " + TIMEOUT_MINUTES + " minutes";
                output.add(msg);
                if (onLine != null) onLine.accept(msg);
                return new Result(-1, List.copyOf(output));
            }
            reader.join(10_000);
            return new Result(process.exitValue(), List.copyOf(output));
        } catch (InterruptedException e) {
            // Cancelled: stop compose too, rather than leaving it running unattended.
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            output.add("Cancelled");
            return new Result(-1, List.copyOf(output));
        }
    }

    private static File nullDevice() {
        return new File(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                        ? "NUL"
                        : "/dev/null");
    }

    static String normalizeHost(String url) {
        if (url != null && url.startsWith("unix://") && !url.startsWith("unix:///")) {
            return "unix:///" + url.substring(7);
        }
        return url;
    }
}
