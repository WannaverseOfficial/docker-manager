package com.wannaverse.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.wannaverse.persistence.DockerHost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DockerService {
    private static final Logger log = LoggerFactory.getLogger(DockerService.class);
    private static final Map<String, DockerClient> DOCKER_CLIENT_CACHE = new ConcurrentHashMap<>();

    private final HostConnections connections;

    public DockerService(@Lazy HostConnections connections) {
        this.connections = connections;
    }

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

    /** A client for a saved host (TLS and SSH settings are looked up), or any plain URL. */
    public DockerClient createClient(String dockerHostUrl) {
        DockerHost host = connections.hostFor(dockerHostUrl);
        if (host == null) {
            host = new DockerHost();
            host.setDockerHostUrl(dockerHostUrl);
        }
        return createClient(host);
    }

    /** A client for a host, saved or not yet saved (when testing a new one). */
    public DockerClient createClient(DockerHost host) {
        String url = connections.effectiveUrl(host);
        Path tls = connections.tlsDir(host);
        return buildClient(url, tls == null ? null : tls.toString());
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

    private static DockerClient buildClient(String dockerHost, String tlsCertPath) {
        var builder =
                DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(dockerHost);
        if (tlsCertPath != null) {
            builder.withDockerTlsVerify(true).withDockerCertPath(tlsCertPath);
        } else {
            // Ignore DOCKER_TLS_VERIFY / DOCKER_CERT_PATH in the environment for hosts that
            // weren't set up with TLS.
            builder.withDockerTlsVerify(false);
        }
        DockerClientConfig config = builder.build();

        DockerHttpClient httpClient =
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .maxConnections(100)
                        .connectionTimeout(Duration.ofSeconds(30))
                        .responseTimeout(Duration.ofSeconds(120))
                        .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }
}
