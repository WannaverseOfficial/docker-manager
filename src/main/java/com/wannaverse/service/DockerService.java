package com.wannaverse.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DockerService {
    private static final Logger log = LoggerFactory.getLogger(DockerService.class);
    private static final Map<String, DockerClient> DOCKER_CLIENT_CACHE = new ConcurrentHashMap<>();

    public DockerClient createClientCached(String dockerHostUrl) {
        return DOCKER_CLIENT_CACHE.computeIfAbsent(dockerHostUrl, this::createClient);
    }

    public DockerClient createClient(String dockerHostUrl) {
        return DockerService.buildClient(dockerHostUrl);
    }

    public void close(String dockerHostUrl) {
        close(DOCKER_CLIENT_CACHE.remove(dockerHostUrl));
    }

    public DockerAPI dockerAPI(DockerClient dockerClient) {
        return new DockerAPI(dockerClient);
    }

    public void close(DockerClient dockerClient) {
        if (dockerClient != null) {
            try {
                dockerClient.close();
            } catch (IOException _) {
            }
        }
    }

    private static DockerClient buildClient(String dockerHost) {
        DockerClientConfig config =
                DefaultDockerClientConfig.createDefaultConfigBuilder()
                        .withDockerHost(dockerHost)
                        .build();

        DockerHttpClient httpClient =
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .maxConnections(100)
                        .connectionTimeout(Duration.ofSeconds(30))
                        .responseTimeout(Duration.ofSeconds(120))
                        .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    public List<String> deployCompose(
            String dockerHostUrl, String composeContent, String projectName)
            throws IOException, InterruptedException {

        String dockerHost = dockerHostUrl;
        if (dockerHost.startsWith("unix://") && !dockerHost.startsWith("unix:///")) {
            dockerHost = "unix:///" + dockerHost.substring(7);
            log.warn("Fixed Docker host URL format: {}", dockerHost);
        }

        Path tempDir = Files.createTempDirectory("compose-deploy-");
        Path composeFile = tempDir.resolve("docker-compose.yml");

        try {
            Files.writeString(composeFile, composeContent);
            log.info("Written compose file to: {}", composeFile);

            List<String> command = new ArrayList<>();
            command.add("docker");
            command.add("-H");
            command.add(dockerHost);
            command.add("compose");
            command.add("-f");
            command.add(composeFile.toString());

            if (projectName != null && !projectName.isBlank()) {
                command.add("-p");
                command.add(projectName.toLowerCase().replaceAll("[^a-z0-9-]", "-"));
            }

            command.add("up");
            command.add("-d");
            command.add("--build");

            log.info("Running compose command: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(tempDir.toFile());
            pb.environment().put("DOCKER_HOST", dockerHost);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            List<String> output = new ArrayList<>();

            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                    log.debug("Compose output: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String errorMsg = "docker compose failed with exit code: " + exitCode;
                output.add("ERROR: " + errorMsg);
                throw new RuntimeException(errorMsg + "\n" + String.join("\n", output));
            }

            output.add("Deployment completed successfully");
            return output;

        } finally {
            try {
                Files.deleteIfExists(composeFile);
                Files.deleteIfExists(tempDir);
            } catch (IOException e) {
                log.warn("Failed to cleanup temp files: {}", e.getMessage());
            }
        }
    }
}
