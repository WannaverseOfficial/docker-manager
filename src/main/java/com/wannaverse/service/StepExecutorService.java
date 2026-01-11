package com.wannaverse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.wannaverse.persistence.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class StepExecutorService {
    private static final Logger log = LoggerFactory.getLogger(StepExecutorService.class);

    private final DockerService dockerService;
    private final GitService gitService;
    private final ObjectMapper objectMapper;

    @Value("${app.pipeline.workspace-dir:#{systemProperties['java.io.tmpdir']}/pipelines}")
    private String workspaceBaseDir;

    public StepExecutorService(
            DockerService dockerService, GitService gitService, ObjectMapper objectMapper) {
        this.dockerService = dockerService;
        this.gitService = gitService;
        this.objectMapper = objectMapper;
    }

    public StepResult executeStep(
            PipelineStep step,
            StepExecution stepExecution,
            DockerHost dockerHost,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        log.info("Executing step: {} (type: {})", step.getName(), step.getStepType());
        logConsumer.accept("Starting step: " + step.getName());

        // Parse environment variables from step config
        Map<String, String> stepEnv = new HashMap<>(env);
        if (step.getEnvironmentVariables() != null && !step.getEnvironmentVariables().isEmpty()) {
            try {
                JsonNode envNode = objectMapper.readTree(step.getEnvironmentVariables());
                envNode.fields()
                        .forEachRemaining(
                                entry -> stepEnv.put(entry.getKey(), entry.getValue().asText()));
            } catch (Exception e) {
                log.warn("Failed to parse step environment variables", e);
            }
        }

        return switch (step.getStepType()) {
            case SHELL -> executeShellStep(step, dockerHost, workspacePath, stepEnv, logConsumer);
            case JAR -> executeJarStep(step, dockerHost, workspacePath, stepEnv, logConsumer);
            case DOCKERFILE ->
                    executeDockerfileStep(step, dockerHost, workspacePath, stepEnv, logConsumer);
            case DOCKER_COMPOSE ->
                    executeDockerComposeStep(step, dockerHost, workspacePath, stepEnv, logConsumer);
            case CUSTOM_IMAGE ->
                    executeCustomImageStep(step, dockerHost, workspacePath, stepEnv, logConsumer);
        };
    }

    private StepResult executeShellStep(
            PipelineStep step,
            DockerHost dockerHost,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        JsonNode config = objectMapper.readTree(step.getConfiguration());
        String script = config.get("script").asText();
        String shell = config.has("shell") ? config.get("shell").asText() : "/bin/sh";

        logConsumer.accept("Executing shell script with " + shell);

        DockerClient client = dockerService.createClientCached(dockerHost.getDockerHostUrl());

        String image = config.has("image") ? config.get("image").asText() : "alpine:latest";

        try {
            client.pullImageCmd(image).start().awaitCompletion(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            logConsumer.accept(
                    "Warning: Could not pull image " + image + ", using local if available");
        }

        List<String> envList = new ArrayList<>();
        env.forEach((k, v) -> envList.add(k + "=" + v));

        CreateContainerResponse container =
                client.createContainerCmd(image)
                        .withCmd(shell, "-c", script)
                        .withWorkingDir("/workspace")
                        .withEnv(envList)
                        .withHostConfig(
                                HostConfig.newHostConfig()
                                        .withBinds(
                                                new Bind(
                                                        workspacePath.toString(),
                                                        new Volume("/workspace"))))
                        .exec();

        String containerId = container.getId();
        logConsumer.accept("Created container: " + containerId.substring(0, 12));

        try {
            client.startContainerCmd(containerId).exec();

            int exitCode =
                    client.waitContainerCmd(containerId)
                            .start()
                            .awaitStatusCode(step.getTimeoutSeconds(), TimeUnit.SECONDS);

            StringBuilder logs = new StringBuilder();
            client.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .exec(
                            new com.github.dockerjava.api.async.ResultCallback.Adapter<
                                    com.github.dockerjava.api.model.Frame>() {
                                @Override
                                public void onNext(com.github.dockerjava.api.model.Frame frame) {
                                    String line = new String(frame.getPayload()).trim();
                                    logs.append(line).append("\n");
                                    logConsumer.accept(line);
                                }
                            })
                    .awaitCompletion(30, TimeUnit.SECONDS);

            return new StepResult(exitCode == 0, exitCode, logs.toString(), null);

        } finally {
            try {
                client.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                log.warn("Failed to remove container {}", containerId, e);
            }
        }
    }

    private StepResult executeJarStep(
            PipelineStep step,
            DockerHost dockerHost,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        JsonNode config = objectMapper.readTree(step.getConfiguration());
        String jarPath = config.get("jarPath").asText();
        String jvmOpts = config.has("jvmOpts") ? config.get("jvmOpts").asText() : "";
        List<String> args = new ArrayList<>();
        if (config.has("args")) {
            config.get("args").forEach(arg -> args.add(arg.asText()));
        }

        logConsumer.accept("Executing JAR: " + jarPath);

        StringBuilder cmd = new StringBuilder("java ");
        if (!jvmOpts.isEmpty()) {
            cmd.append(jvmOpts).append(" ");
        }
        cmd.append("-jar ").append(jarPath);
        for (String arg : args) {
            cmd.append(" ").append(arg);
        }

        String image = config.has("image") ? config.get("image").asText() : "openjdk:17-slim";

        DockerClient client = dockerService.createClientCached(dockerHost.getDockerHostUrl());

        try {
            client.pullImageCmd(image).start().awaitCompletion(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            logConsumer.accept("Warning: Could not pull image " + image);
        }

        List<String> envList = new ArrayList<>();
        env.forEach((k, v) -> envList.add(k + "=" + v));

        CreateContainerResponse container =
                client.createContainerCmd(image)
                        .withCmd("/bin/sh", "-c", cmd.toString())
                        .withWorkingDir("/workspace")
                        .withEnv(envList)
                        .withHostConfig(
                                HostConfig.newHostConfig()
                                        .withBinds(
                                                new Bind(
                                                        workspacePath.toString(),
                                                        new Volume("/workspace"))))
                        .exec();

        String containerId = container.getId();

        try {
            client.startContainerCmd(containerId).exec();

            int exitCode =
                    client.waitContainerCmd(containerId)
                            .start()
                            .awaitStatusCode(step.getTimeoutSeconds(), TimeUnit.SECONDS);

            StringBuilder logs = new StringBuilder();
            client.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .exec(
                            new com.github.dockerjava.api.async.ResultCallback.Adapter<
                                    com.github.dockerjava.api.model.Frame>() {
                                @Override
                                public void onNext(com.github.dockerjava.api.model.Frame frame) {
                                    String line = new String(frame.getPayload()).trim();
                                    logs.append(line).append("\n");
                                    logConsumer.accept(line);
                                }
                            })
                    .awaitCompletion(30, TimeUnit.SECONDS);

            return new StepResult(exitCode == 0, exitCode, logs.toString(), null);

        } finally {
            try {
                client.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                log.warn("Failed to remove container {}", containerId, e);
            }
        }
    }

    private StepResult executeDockerfileStep(
            PipelineStep step,
            DockerHost dockerHost,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        JsonNode config = objectMapper.readTree(step.getConfiguration());
        String dockerfile =
                config.has("dockerfile") ? config.get("dockerfile").asText() : "Dockerfile";
        String imageName = config.get("imageName").asText();

        logConsumer.accept("Building Docker image from " + dockerfile);

        DockerClient client = dockerService.createClientCached(dockerHost.getDockerHostUrl());

        File dockerfileFile = workspacePath.resolve(dockerfile).toFile();
        if (!dockerfileFile.exists()) {
            return new StepResult(false, 1, "", "Dockerfile not found: " + dockerfile);
        }

        StringBuilder logs = new StringBuilder();

        try {
            Set<String> tags = new HashSet<>();
            tags.add(imageName);

            String imageId =
                    client.buildImageCmd()
                            .withDockerfile(dockerfileFile)
                            .withBaseDirectory(workspacePath.toFile())
                            .withTags(tags)
                            .exec(
                                    new com.github.dockerjava.api.command
                                            .BuildImageResultCallback() {
                                        @Override
                                        public void onNext(
                                                com.github.dockerjava.api.model.BuildResponseItem
                                                        item) {
                                            super.onNext(item);
                                            if (item.getStream() != null) {
                                                String line = item.getStream().trim();
                                                if (!line.isEmpty()) {
                                                    logs.append(line).append("\n");
                                                    logConsumer.accept(line);
                                                }
                                            }
                                            if (item.getErrorDetail() != null) {
                                                String error = item.getErrorDetail().getMessage();
                                                logs.append("ERROR: ").append(error).append("\n");
                                                logConsumer.accept("ERROR: " + error);
                                            }
                                        }
                                    })
                            .awaitImageId();

            logConsumer.accept("Image built successfully: " + imageId);
            return new StepResult(true, 0, logs.toString(), null);

        } catch (Exception e) {
            logConsumer.accept("Build failed: " + e.getMessage());
            return new StepResult(false, 1, logs.toString(), e.getMessage());
        }
    }

    private StepResult executeDockerComposeStep(
            PipelineStep step,
            DockerHost dockerHost,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        JsonNode config = objectMapper.readTree(step.getConfiguration());
        String composeFile =
                config.has("composeFile")
                        ? config.get("composeFile").asText()
                        : "docker-compose.yml";
        String command = config.has("command") ? config.get("command").asText() : "up -d";

        logConsumer.accept("Running docker-compose " + command);

        File composeFileObj = workspacePath.resolve(composeFile).toFile();
        if (!composeFileObj.exists()) {
            return new StepResult(false, 1, "", "docker-compose.yml not found: " + composeFile);
        }

        String dockerHostStr = dockerHostUrl(dockerHost);
        ProcessBuilder pb =
                new ProcessBuilder(
                        "docker",
                        "-H",
                        dockerHostStr,
                        "compose",
                        "-f",
                        composeFileObj.getAbsolutePath());
        pb.command().addAll(Arrays.asList(command.split("\\s+")));
        pb.directory(workspacePath.toFile());
        pb.environment().put("DOCKER_HOST", dockerHostStr);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);

        StringBuilder logs = new StringBuilder();
        Process process = pb.start();

        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logs.append(line).append("\n");
                logConsumer.accept(line);
            }
        }

        int exitCode = process.waitFor();
        return new StepResult(exitCode == 0, exitCode, logs.toString(), null);
    }

    private StepResult executeCustomImageStep(
            PipelineStep step,
            DockerHost dockerHost,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        JsonNode config = objectMapper.readTree(step.getConfiguration());
        String image = config.get("image").asText();
        String command = config.get("command").asText();

        logConsumer.accept("Running command in image: " + image);

        DockerClient client = dockerService.createClientCached(dockerHost.getDockerHostUrl());

        try {
            client.pullImageCmd(image).start().awaitCompletion(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            logConsumer.accept("Warning: Could not pull image " + image);
        }

        List<String> envList = new ArrayList<>();
        env.forEach((k, v) -> envList.add(k + "=" + v));

        // Parse volumes from config
        List<Bind> binds = new ArrayList<>();
        binds.add(new Bind(workspacePath.toString(), new Volume("/workspace")));

        if (config.has("volumes")) {
            config.get("volumes")
                    .forEach(
                            vol -> {
                                String volStr = vol.asText();
                                String[] parts = volStr.split(":");
                                if (parts.length >= 2) {
                                    binds.add(new Bind(parts[0], new Volume(parts[1])));
                                }
                            });
        }

        CreateContainerResponse container =
                client.createContainerCmd(image)
                        .withCmd("/bin/sh", "-c", command)
                        .withWorkingDir("/workspace")
                        .withEnv(envList)
                        .withHostConfig(HostConfig.newHostConfig().withBinds(binds))
                        .exec();

        String containerId = container.getId();

        try {
            client.startContainerCmd(containerId).exec();

            int exitCode =
                    client.waitContainerCmd(containerId)
                            .start()
                            .awaitStatusCode(step.getTimeoutSeconds(), TimeUnit.SECONDS);

            StringBuilder logs = new StringBuilder();
            client.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .exec(
                            new com.github.dockerjava.api.async.ResultCallback.Adapter<
                                    com.github.dockerjava.api.model.Frame>() {
                                @Override
                                public void onNext(com.github.dockerjava.api.model.Frame frame) {
                                    String line = new String(frame.getPayload()).trim();
                                    logs.append(line).append("\n");
                                    logConsumer.accept(line);
                                }
                            })
                    .awaitCompletion(30, TimeUnit.SECONDS);

            return new StepResult(exitCode == 0, exitCode, logs.toString(), null);

        } finally {
            try {
                client.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                log.warn("Failed to remove container {}", containerId, e);
            }
        }
    }

    private String dockerHostUrl(DockerHost host) {
        String url = host.getDockerHostUrl();
        if (url.startsWith("unix://") && !url.startsWith("unix:///")) {
            url = "unix:///" + url.substring(7);
        }
        return url;
    }

    public Path prepareWorkspace(Pipeline pipeline, String executionId) throws IOException {
        Path workspaceDir = Path.of(workspaceBaseDir, pipeline.getId(), executionId);
        Files.createDirectories(workspaceDir);
        return workspaceDir;
    }

    public Path prepareWorkspace(String pipelineId, String pipelineName, String executionId)
            throws IOException {
        Path workspaceDir = Path.of(workspaceBaseDir, pipelineId, executionId);
        Files.createDirectories(workspaceDir);
        log.info("Prepared workspace for pipeline '{}': {}", pipelineName, workspaceDir);
        return workspaceDir;
    }

    public Path cloneRepository(Pipeline pipeline, Path workspacePath) throws Exception {
        if (pipeline.getGitRepository() != null) {
            return gitService.cloneOrPullRepositoryTo(pipeline.getGitRepository(), workspacePath);
        }
        return workspacePath;
    }

    public Path cloneRepository(String repoUrl, String branch, Path workspacePath)
            throws Exception {
        return gitService.cloneOrPullRepositoryTo(repoUrl, branch, workspacePath);
    }

    public StepResult executeStep(
            PipelineStep.StepType stepType,
            String stepName,
            String configuration,
            String workingDirectory,
            int timeoutSeconds,
            String environmentVariables,
            String dockerHostUrl,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        log.info("Executing step: {} (type: {})", stepName, stepType);
        log.info("Step - calling logConsumer");
        logConsumer.accept("Starting step: " + stepName);
        log.info("Step - logConsumer returned, parsing env vars");

        Map<String, String> stepEnv = new HashMap<>(env);
        if (environmentVariables != null && !environmentVariables.isEmpty()) {
            try {
                JsonNode envNode = objectMapper.readTree(environmentVariables);
                envNode.fields()
                        .forEachRemaining(
                                entry -> stepEnv.put(entry.getKey(), entry.getValue().asText()));
            } catch (Exception e) {
                log.warn("Failed to parse step environment variables", e);
            }
        }

        log.info("Step - dispatching to {} executor", stepType);
        StepResult result =
                switch (stepType) {
                    case SHELL ->
                            executeShellStepWithConfig(
                                    configuration,
                                    timeoutSeconds,
                                    dockerHostUrl,
                                    workspacePath,
                                    stepEnv,
                                    logConsumer);
                    case JAR ->
                            executeJarStepWithConfig(
                                    configuration,
                                    timeoutSeconds,
                                    dockerHostUrl,
                                    workspacePath,
                                    stepEnv,
                                    logConsumer);
                    case DOCKERFILE ->
                            executeDockerfileStepWithConfig(
                                    configuration,
                                    dockerHostUrl,
                                    workspacePath,
                                    stepEnv,
                                    logConsumer);
                    case DOCKER_COMPOSE ->
                            executeDockerComposeStepWithConfig(
                                    configuration,
                                    dockerHostUrl,
                                    workspacePath,
                                    stepEnv,
                                    logConsumer);
                    case CUSTOM_IMAGE ->
                            executeCustomImageStepWithConfig(
                                    configuration,
                                    timeoutSeconds,
                                    dockerHostUrl,
                                    workspacePath,
                                    stepEnv,
                                    logConsumer);
                };
        log.info("Step - executor returned, success={}", result.success());
        return result;
    }

    private StepResult executeShellStepWithConfig(
            String configuration,
            int timeoutSeconds,
            String dockerHostUrl,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        JsonNode config = objectMapper.readTree(configuration);
        String script = config.get("script").asText();
        String shell = config.has("shell") ? config.get("shell").asText() : "/bin/sh";

        logConsumer.accept(
                "Executing: "
                        + script.substring(0, Math.min(80, script.length()))
                        + (script.length() > 80 ? "..." : ""));
        logConsumer.accept("Connecting to Docker: " + dockerHostUrl);

        DockerClient client = dockerService.createClientCached(dockerHostUrl);
        logConsumer.accept("Docker connection established");

        String image = config.has("image") ? config.get("image").asText() : "alpine:latest";
        logConsumer.accept("Using image: " + image);

        logConsumer.accept("Pulling image...");
        try {
            client.pullImageCmd(image).start().awaitCompletion(5, TimeUnit.MINUTES);
            logConsumer.accept("Image ready");
        } catch (Exception e) {
            logConsumer.accept("Warning: Could not pull image, using local: " + e.getMessage());
        }

        List<String> envList = new ArrayList<>();
        env.forEach((k, v) -> envList.add(k + "=" + v));

        logConsumer.accept("Creating container...");
        CreateContainerResponse container =
                client.createContainerCmd(image)
                        .withCmd(shell, "-c", script)
                        .withWorkingDir("/workspace")
                        .withEnv(envList)
                        .withHostConfig(
                                HostConfig.newHostConfig()
                                        .withBinds(
                                                new Bind(
                                                        workspacePath.toString(),
                                                        new Volume("/workspace"))))
                        .exec();

        String containerId = container.getId();
        logConsumer.accept("Container: " + containerId.substring(0, 12));

        try {
            logConsumer.accept("Starting container...");
            client.startContainerCmd(containerId).exec();
            logConsumer.accept("Running...");
            int exitCode =
                    client.waitContainerCmd(containerId)
                            .start()
                            .awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);

            logConsumer.accept("Container exited with code: " + exitCode);
            logConsumer.accept("--- Output ---");

            StringBuilder logs = new StringBuilder();
            client.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .exec(
                            new com.github.dockerjava.api.async.ResultCallback.Adapter<
                                    com.github.dockerjava.api.model.Frame>() {
                                @Override
                                public void onNext(com.github.dockerjava.api.model.Frame frame) {
                                    String line = new String(frame.getPayload()).trim();
                                    logs.append(line).append("\n");
                                    logConsumer.accept(line);
                                }
                            })
                    .awaitCompletion(30, TimeUnit.SECONDS);

            logConsumer.accept("--- End Output ---");

            return new StepResult(
                    exitCode == 0,
                    exitCode,
                    logs.toString(),
                    exitCode != 0 ? "Exit code: " + exitCode : null);
        } finally {
            try {
                client.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                log.warn("Failed to remove container: {}", containerId);
            }
        }
    }

    private StepResult executeJarStepWithConfig(
            String configuration,
            int timeoutSeconds,
            String dockerHostUrl,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {
        JsonNode config = objectMapper.readTree(configuration);
        String jarPath = config.get("jarPath").asText();
        String jvmOpts = config.has("jvmOpts") ? config.get("jvmOpts").asText() : "";
        String args = config.has("args") ? config.get("args").toString() : "";

        String script = "java " + jvmOpts + " -jar " + jarPath + " " + args;
        String newConfig =
                "{\"script\":\""
                        + script.replace("\"", "\\\"")
                        + "\",\"image\":\"openjdk:17-alpine\"}";
        return executeShellStepWithConfig(
                newConfig, timeoutSeconds, dockerHostUrl, workspacePath, env, logConsumer);
    }

    private StepResult executeDockerfileStepWithConfig(
            String configuration,
            String dockerHostUrl,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        JsonNode config = objectMapper.readTree(configuration);
        String dockerfile =
                config.has("dockerfile") ? config.get("dockerfile").asText() : "Dockerfile";
        String imageName = config.get("imageName").asText();

        logConsumer.accept("Building Docker image: " + imageName);

        DockerClient client = dockerService.createClientCached(dockerHostUrl);

        Path dockerfilePath = workspacePath.resolve(dockerfile);
        if (!Files.exists(dockerfilePath)) {
            return new StepResult(false, 1, "", "Dockerfile not found: " + dockerfile);
        }

        StringBuilder logs = new StringBuilder();
        try {
            String imageId =
                    client.buildImageCmd(dockerfilePath.toFile())
                            .withTags(Set.of(imageName))
                            .exec(
                                    new com.github.dockerjava.api.command
                                            .BuildImageResultCallback() {
                                        @Override
                                        public void onNext(
                                                com.github.dockerjava.api.model.BuildResponseItem
                                                        item) {
                                            if (item.getStream() != null) {
                                                String line = item.getStream().trim();
                                                logs.append(line).append("\n");
                                                logConsumer.accept(line);
                                            }
                                            super.onNext(item);
                                        }
                                    })
                            .awaitImageId();

            logConsumer.accept("Built image: " + imageId);
            return new StepResult(true, 0, logs.toString(), null);
        } catch (Exception e) {
            return new StepResult(false, 1, logs.toString(), e.getMessage());
        }
    }

    private StepResult executeDockerComposeStepWithConfig(
            String configuration,
            String dockerHostUrl,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        JsonNode config = objectMapper.readTree(configuration);
        String composeFile =
                config.has("composeFile")
                        ? config.get("composeFile").asText()
                        : "docker-compose.yml";
        String command = config.has("command") ? config.get("command").asText() : "up -d";

        String script = "docker-compose -f " + composeFile + " " + command;
        String newConfig =
                "{\"script\":\""
                        + script.replace("\"", "\\\"")
                        + "\",\"image\":\"docker/compose:latest\"}";
        return executeShellStepWithConfig(
                newConfig, 3600, dockerHostUrl, workspacePath, env, logConsumer);
    }

    private StepResult executeCustomImageStepWithConfig(
            String configuration,
            int timeoutSeconds,
            String dockerHostUrl,
            Path workspacePath,
            Map<String, String> env,
            Consumer<String> logConsumer)
            throws Exception {

        JsonNode config = objectMapper.readTree(configuration);
        String image = config.get("image").asText();
        String command = config.has("command") ? config.get("command").asText() : "";

        String newConfig =
                "{\"script\":\""
                        + command.replace("\"", "\\\"")
                        + "\",\"image\":\""
                        + image
                        + "\"}";
        return executeShellStepWithConfig(
                newConfig, timeoutSeconds, dockerHostUrl, workspacePath, env, logConsumer);
    }

    public void cleanupWorkspace(Path workspacePath) {
        try {
            if (workspacePath != null && Files.exists(workspacePath)) {
                // Delete recursively
                Files.walk(workspacePath)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup workspace: {}", workspacePath, e);
        }
    }

    public record StepResult(boolean success, int exitCode, String logs, String error) {}
}
