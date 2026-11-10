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

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DockerService {
    private static final Logger log = LoggerFactory.getLogger(DockerService.class);
    private static final Map<String, DockerClient> DOCKER_CLIENT_CACHE = new ConcurrentHashMap<>();

    public DockerClient createClientCached(String dockerHostUrl) {
        return DOCKER_CLIENT_CACHE.computeIfAbsent(dockerHostUrl, this::createClient);
    }

    /** The host URL a cached client was created for, or null. */
    public String urlOf(DockerClient client) {
        for (var e : DOCKER_CLIENT_CACHE.entrySet()) {
            if (e.getValue() == client) return e.getKey();
        }
        return null;
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
}
