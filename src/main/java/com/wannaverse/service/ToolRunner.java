package com.wannaverse.service;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short-lived tool container (a scanner, a backup helper...) on a Docker host and collects
 * its output. The container is labelled as a Docker Manager helper (hidden from lists) and always
 * removed afterwards.
 */
@Component
public class ToolRunner {
    private static final Logger log = LoggerFactory.getLogger(ToolRunner.class);

    /** What a tool printed. Stdout and stderr are kept apart: tools often print JSON to stdout. */
    public record Result(int exitCode, String stdout, String stderr, String containerId) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** Settings for one run. */
    public record Spec(
            String image,
            List<String> command,
            Map<String, String> env,
            HostConfig hostConfig,
            List<String> entrypoint,
            long timeoutSeconds,
            /** Keep the container afterwards (to copy files out of it); remove it yourself. */
            boolean keep) {}

    public Result run(DockerAPI api, Spec spec) {
        ensureImage(api, spec.image());
        var create =
                api.client()
                        .createContainerCmd(spec.image())
                        .withName("dm-tool-" + UUID.randomUUID().toString().substring(0, 8))
                        .withLabels(
                                Map.of(
                                        VolumeBrowserService.HELPER_LABEL,
                                        "tool",
                                        "docker_bench_security",
                                        ""))
                        .withHostConfig(
                                spec.hostConfig() == null
                                        ? HostConfig.newHostConfig()
                                        : spec.hostConfig());
        if (spec.command() != null) create.withCmd(spec.command());
        if (spec.entrypoint() != null) create.withEntrypoint(spec.entrypoint());
        if (spec.env() != null && !spec.env().isEmpty()) {
            create.withEnv(
                    spec.env().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList());
        }
        String id = create.exec().getId();
        try {
            api.client().startContainerCmd(id).exec();
            Integer exit;
            try {
                exit =
                        api.client()
                                .waitContainerCmd(id)
                                .start()
                                .awaitStatusCode(spec.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                // Timed out (or the wait failed): stop it and report what it printed so far.
                try {
                    api.client().killContainerCmd(id).exec();
                } catch (Exception ignored) {
                }
                exit = -1;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try {
                api.client()
                        .logContainerCmd(id)
                        .withStdOut(true)
                        .withStdErr(true)
                        .exec(
                                new ResultCallback.Adapter<Frame>() {
                                    @Override
                                    public void onNext(Frame frame) {
                                        byte[] p = frame.getPayload();
                                        if (frame.getStreamType() == StreamType.STDERR) {
                                            err.write(p, 0, p.length);
                                        } else {
                                            out.write(p, 0, p.length);
                                        }
                                    }
                                })
                        .awaitCompletion(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Result(
                    exit == null ? -1 : exit,
                    out.toString(java.nio.charset.StandardCharsets.UTF_8),
                    err.toString(java.nio.charset.StandardCharsets.UTF_8),
                    id);
        } finally {
            if (!spec.keep()) remove(api, id);
        }
    }

    public void remove(DockerAPI api, String containerId) {
        try {
            api.client()
                    .removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        } catch (Exception e) {
            log.debug("Could not remove tool container {}: {}", containerId, e.getMessage());
        }
    }

    static void ensureImage(DockerAPI api, String image) {
        try {
            api.inspectImage(image);
        } catch (NotFoundException e) {
            try {
                api.pullImage(image);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pulling " + image);
            }
        }
    }
}
