package com.wannaverse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.Pipeline;
import com.wannaverse.persistence.PipelineStep;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs pipeline steps in throwaway containers.
 *
 * <p>The workspace (the cloned repository plus anything steps write) lives in a Docker volume on
 * the target host, mounted at /workspace in every step container. It used to be a bind mount of a
 * folder inside Docker Manager's own container; the Docker daemon resolves bind paths on the host,
 * so steps saw an empty folder. The volume is filled from, and read back into, the local copy
 * through the archive API, which works for local and remote hosts alike.
 */
@Service
public class StepExecutorService {
    private static final Logger log = LoggerFactory.getLogger(StepExecutorService.class);
    private static final String HELPER_IMAGE = "busybox:stable";
    private static final String DEFAULT_SHELL_IMAGE = "alpine:3";
    private static final String DEFAULT_JAVA_IMAGE = "eclipse-temurin:21-jre";

    private final DockerService dockerService;
    private final GitService gitService;
    private final ComposeRunner composeRunner;
    private final DockerRegistryService registryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.pipeline.workspace-dir:#{systemProperties['java.io.tmpdir']}/pipelines}")
    private String workspaceBaseDir;

    public StepExecutorService(
            DockerService dockerService,
            GitService gitService,
            ComposeRunner composeRunner,
            DockerRegistryService registryService) {
        this.dockerService = dockerService;
        this.gitService = gitService;
        this.composeRunner = composeRunner;
        this.registryService = registryService;
    }

    public record StepResult(boolean success, int exitCode, String logs, String error) {}

    /** A pipeline run's workspace: a local folder and the matching volume on the Docker host. */
    public final class Workspace implements AutoCloseable {
        public final Path local;
        final String volume;
        final DockerClient client;

        /** True when the volume may have changes the local folder does not have. */
        boolean volumeAhead;

        Workspace(Path local, String volume, DockerClient client) {
            this.local = local;
            this.volume = volume;
            this.client = client;
        }

        @Override
        public void close() {
            try {
                client.removeVolumeCmd(volume).exec();
            } catch (Exception e) {
                log.warn("Could not remove workspace volume {}: {}", volume, e.getMessage());
            }
            deleteDir(local);
        }
    }

    public Workspace prepareWorkspace(String dockerHostUrl, String pipelineId, String executionId)
            throws IOException {
        Path local = Path.of(workspaceBaseDir, pipelineId, executionId);
        deleteDir(local);
        Files.createDirectories(local);
        DockerClient client = dockerService.createClientCached(dockerHostUrl);
        String volume = "dm-pipeline-" + executionId;
        client.createVolumeCmd()
                .withName(volume)
                .withLabels(Map.of("com.wannaverse.pipeline", pipelineId))
                .exec();
        return new Workspace(local, volume, client);
    }

    /** Clones the pipeline's repository into the workspace at the given branch or commit. */
    public String cloneRepository(
            GitRepository repo, String branch, String commit, Workspace ws, Consumer<String> out)
            throws Exception {
        Path checkout = gitService.checkoutForPipeline(repo, branch, commit, ws.local);
        String sha = gitService.getCurrentCommitSha(checkout);
        out.accept("Checked out " + (sha == null ? "?" : sha.substring(0, 7)));
        pushToVolume(ws);
        return sha;
    }

    public StepResult executeStep(
            PipelineStep.StepType stepType,
            String stepName,
            String configuration,
            String workingDirectory,
            int timeoutSeconds,
            String environmentVariables,
            Workspace ws,
            Map<String, String> env,
            Consumer<String> out,
            BooleanSupplier cancelled)
            throws Exception {
        out.accept("Starting step: " + stepName);
        Map<String, String> stepEnv = new LinkedHashMap<>(env);
        if (environmentVariables != null && !environmentVariables.isBlank()) {
            try {
                JsonNode envNode = objectMapper.readTree(environmentVariables);
                envNode.fields()
                        .forEachRemaining(e -> stepEnv.put(e.getKey(), e.getValue().asText()));
            } catch (Exception e) {
                out.accept("Warning: could not read the step's environment variables");
            }
        }
        JsonNode config =
                configuration == null || configuration.isBlank()
                        ? objectMapper.createObjectNode()
                        : objectMapper.readTree(configuration);
        int timeout = timeoutSeconds > 0 ? timeoutSeconds : 3600;
        String workDir = containerWorkDir(workingDirectory);

        return switch (stepType) {
            case SHELL -> {
                String script = text(config, "script", "");
                String shell = text(config, "shell", "/bin/sh");
                String image = text(config, "image", DEFAULT_SHELL_IMAGE);
                yield runContainer(
                        ws,
                        image,
                        List.of(shell, "-c", script),
                        workDir,
                        stepEnv,
                        timeout,
                        out,
                        cancelled);
            }
            case JAR -> {
                String jar = text(config, "jarPath", null);
                if (jar == null) yield new StepResult(false, 1, "", "Set the path of the JAR file");
                List<String> cmd = new ArrayList<>(List.of("java"));
                cmd.addAll(splitArgs(text(config, "jvmOpts", "")));
                cmd.add("-jar");
                cmd.add(jar);
                JsonNode args = config.get("args");
                if (args != null && args.isArray()) args.forEach(a -> cmd.add(a.asText()));
                else if (args != null) cmd.addAll(splitArgs(args.asText()));
                String image = text(config, "image", DEFAULT_JAVA_IMAGE);
                yield runContainer(ws, image, cmd, workDir, stepEnv, timeout, out, cancelled);
            }
            case CUSTOM_IMAGE -> {
                String image = text(config, "image", null);
                if (image == null) yield new StepResult(false, 1, "", "Set the image to run");
                String command = text(config, "command", "");
                List<String> cmd =
                        command.isBlank() ? List.of() : List.of("/bin/sh", "-c", command);
                yield runContainer(ws, image, cmd, workDir, stepEnv, timeout, out, cancelled);
            }
            case DOCKERFILE -> {
                pullFromVolume(ws);
                yield buildImage(ws, config, workingDirectory, out, cancelled);
            }
            case DOCKER_COMPOSE -> {
                pullFromVolume(ws);
                yield runCompose(ws, config, workingDirectory, out);
            }
        };
    }

    // --- container steps ---------------------------------------------------------------------

    private StepResult runContainer(
            Workspace ws,
            String image,
            List<String> cmd,
            String workDir,
            Map<String, String> env,
            int timeoutSeconds,
            Consumer<String> out,
            BooleanSupplier cancelled)
            throws Exception {
        DockerClient client = ws.client;
        out.accept("Image: " + image);
        pull(client, image, out);

        List<String> envList = new ArrayList<>();
        env.forEach((k, v) -> envList.add(k + "=" + v));

        var create =
                client.createContainerCmd(image)
                        .withWorkingDir(workDir)
                        .withEnv(envList)
                        .withLabels(Map.of("com.wannaverse.pipeline-step", "true"))
                        .withHostConfig(
                                HostConfig.newHostConfig()
                                        .withBinds(new Bind(ws.volume, new Volume("/workspace"))));
        if (!cmd.isEmpty()) create.withCmd(cmd);
        CreateContainerResponse container = create.exec();
        String id = container.getId();
        ws.volumeAhead = true;

        StringBuilder logs = new StringBuilder();
        try {
            client.startContainerCmd(id).exec();
            var follow =
                    client.logContainerCmd(id)
                            .withStdOut(true)
                            .withStdErr(true)
                            .withFollowStream(true)
                            .exec(
                                    new ResultCallback.Adapter<Frame>() {
                                        private final StringBuilder partial = new StringBuilder();

                                        @Override
                                        public void onNext(Frame frame) {
                                            partial.append(
                                                    new String(
                                                            frame.getPayload(),
                                                            StandardCharsets.UTF_8));
                                            int nl;
                                            while ((nl = partial.indexOf("\n")) >= 0) {
                                                String line =
                                                        partial.substring(0, nl).stripTrailing();
                                                partial.delete(0, nl + 1);
                                                emit(line);
                                            }
                                        }

                                        @Override
                                        public void onComplete() {
                                            if (!partial.isEmpty()) emit(partial.toString());
                                            super.onComplete();
                                        }

                                        private void emit(String line) {
                                            if (logs.length() < 1_000_000)
                                                logs.append(line).append('\n');
                                            out.accept(line);
                                        }
                                    });

            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            Integer exit = null;
            while (exit == null) {
                if (cancelled.getAsBoolean()) {
                    out.accept("Stopping: the run was cancelled");
                    kill(client, id);
                    return new StepResult(false, 130, logs.toString(), "Cancelled");
                }
                if (System.currentTimeMillis() > deadline) {
                    out.accept(
                            "Stopping: the step took longer than " + timeoutSeconds + " seconds");
                    kill(client, id);
                    return new StepResult(false, 124, logs.toString(), "Timed out");
                }
                var state = client.inspectContainerCmd(id).exec().getState();
                if (!Boolean.TRUE.equals(state.getRunning())) {
                    Long code = state.getExitCodeLong();
                    exit = code == null ? -1 : code.intValue();
                } else {
                    Thread.sleep(500);
                }
            }
            follow.awaitCompletion(10, TimeUnit.SECONDS);
            out.accept("Exited with code " + exit);
            return new StepResult(
                    exit == 0, exit, logs.toString(), exit == 0 ? null : "Exit code " + exit);
        } finally {
            try {
                client.removeContainerCmd(id).withForce(true).exec();
            } catch (Exception e) {
                log.warn("Could not remove step container {}", id);
            }
        }
    }

    private StepResult buildImage(
            Workspace ws,
            JsonNode config,
            String workingDirectory,
            Consumer<String> out,
            BooleanSupplier cancelled) {
        String imageName = text(config, "imageName", null);
        if (imageName == null) return new StepResult(false, 1, "", "Set the image name to build");
        Path context =
                GitService.resolveInside(ws.local, text(config, "context", workingDirectory), ".");
        Path dockerfile =
                GitService.resolveInside(ws.local, text(config, "dockerfile", null), "Dockerfile");
        if (!Files.isRegularFile(dockerfile)) {
            return new StepResult(
                    false, 1, "", "Dockerfile not found: " + ws.local.relativize(dockerfile));
        }
        StringBuilder logs = new StringBuilder();
        try {
            BuildImageResultCallback callback =
                    new BuildImageResultCallback() {
                        @Override
                        public void onNext(BuildResponseItem item) {
                            if (item.getStream() != null) {
                                item.getStream()
                                        .stripTrailing()
                                        .lines()
                                        .forEach(
                                                l -> {
                                                    logs.append(l).append('\n');
                                                    out.accept(l);
                                                });
                            }
                            if (item.getErrorDetail() != null)
                                out.accept("ERROR: " + item.getErrorDetail().getMessage());
                            if (cancelled.getAsBoolean()) {
                                try {
                                    close();
                                } catch (IOException ignored) {
                                }
                            }
                            super.onNext(item);
                        }
                    };
            String imageId =
                    ws.client
                            .buildImageCmd()
                            .withBaseDirectory(context.toFile())
                            .withDockerfile(dockerfile.toFile())
                            .withTags(Set.of(imageName))
                            .withPull(true)
                            .exec(callback)
                            .awaitImageId();
            out.accept("Built image " + imageName + " (" + imageId + ")");
            return new StepResult(true, 0, logs.toString(), null);
        } catch (Exception e) {
            return new StepResult(false, 1, logs.toString(), e.getMessage());
        }
    }

    private StepResult runCompose(
            Workspace ws, JsonNode config, String workingDirectory, Consumer<String> out) {
        String file = text(config, "composeFile", "docker-compose.yml");
        Path composeFile = GitService.resolveInside(ws.local, file, "docker-compose.yml");
        if (!Files.isRegularFile(composeFile)) {
            return new StepResult(false, 1, "", "Compose file not found: " + file);
        }
        String project =
                StackService.projectNameFor(text(config, "projectName", "pipeline-" + ws.volume));
        List<String> args = splitArgs(text(config, "command", "up -d"));
        String host = dockerHostOf(ws);
        var result =
                composeRunner.run(
                        host,
                        composeFile.getParent(),
                        composeFile.getFileName().toString(),
                        null,
                        project,
                        args,
                        out);
        return new StepResult(
                result.ok(),
                result.exitCode(),
                result.tail(500),
                result.ok() ? null : "docker compose failed");
    }

    // --- volume sync -------------------------------------------------------------------------

    /** Copies the local workspace into the volume. */
    private void pushToVolume(Workspace ws) {
        String helper = helper(ws);
        try {
            ws.client
                    .copyArchiveToContainerCmd(helper)
                    .withHostResource(ws.local.toString())
                    .withDirChildrenOnly(true)
                    .withRemotePath("/workspace")
                    .exec();
        } finally {
            removeQuietly(ws.client, helper);
        }
    }

    /** Copies the volume (with changes made by earlier steps) back into the local workspace. */
    private void pullFromVolume(Workspace ws) throws IOException {
        if (!ws.volumeAhead) return;
        String helper = helper(ws);
        Path base = ws.local.toAbsolutePath().normalize();
        try (InputStream in = ws.client.copyArchiveFromContainerCmd(helper, "/workspace/.").exec();
                TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                // The archive's top folder is "." (or "workspace"); strip it.
                int slash = name.indexOf('/');
                String rel = slash >= 0 ? name.substring(slash + 1) : "";
                if (rel.isEmpty()) continue;
                Path target = base.resolve(rel).normalize();
                if (!target.startsWith(base)) continue; // never write outside the workspace
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else if (entry.isFile()) {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            removeQuietly(ws.client, helper);
        }
        ws.volumeAhead = false;
    }

    private String helper(Workspace ws) {
        pull(ws.client, HELPER_IMAGE, l -> {});
        return ws.client
                .createContainerCmd(HELPER_IMAGE)
                .withCmd("true")
                .withHostConfig(
                        HostConfig.newHostConfig()
                                .withBinds(new Bind(ws.volume, new Volume("/workspace"))))
                .exec()
                .getId();
    }

    private String dockerHostOf(Workspace ws) {
        // ComposeRunner needs the host URL; the cache key of the client is that URL.
        return dockerService.urlOf(ws.client);
    }

    // --- helpers -----------------------------------------------------------------------------

    private void pull(DockerClient client, String image, Consumer<String> out) {
        try {
            client.inspectImageCmd(image).exec();
            return;
        } catch (Exception ignored) {
            // not present
        }
        out.accept("Pulling " + image);
        try {
            var cmd = client.pullImageCmd(ContainerSpecService.withDefaultTag(image));
            var auth = registryService.getAuthConfig(image);
            if (auth != null) cmd.withAuthConfig(auth);
            cmd.start().awaitCompletion(15, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            out.accept("Could not pull " + image + ": " + e.getMessage());
        }
    }

    private static void kill(DockerClient client, String id) {
        try {
            client.killContainerCmd(id).exec();
        } catch (Exception ignored) {
        }
    }

    private static void removeQuietly(DockerClient client, String id) {
        try {
            client.removeContainerCmd(id).withForce(true).exec();
        } catch (Exception ignored) {
        }
    }

    private static String containerWorkDir(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) return "/workspace";
        String w = workingDirectory.trim().replace('\\', '/');
        if (w.startsWith("/")) return w;
        if (w.contains("..")) return "/workspace";
        return "/workspace/" + w;
    }

    private static String text(JsonNode config, String field, String fallback) {
        JsonNode n = config.get(field);
        return n == null || n.isNull() || n.asText().isBlank() ? fallback : n.asText();
    }

    /** Splits a command line on whitespace, honouring single and double quotes. */
    static List<String> splitArgs(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) return out;
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean any = false;
        for (char c : line.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                any = true;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0 || any) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || any) out.add(cur.toString());
        return out;
    }

    private static void deleteDir(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) return;
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", dir, e.getMessage());
        }
    }

    // Kept for callers that only need the pipeline (e.g. previews).
    public static boolean hasRepository(Pipeline pipeline) {
        return pipeline.getGitRepository() != null;
    }
}
