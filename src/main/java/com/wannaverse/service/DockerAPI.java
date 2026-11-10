package com.wannaverse.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DockerAPI {
    private final DockerClient dockerClient;

    public DockerAPI(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public boolean ping() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Container> listAllContainers() {
        return dockerClient.listContainersCmd().withShowAll(true).exec();
    }

    public List<Container> listRunningContainers() {
        return dockerClient.listContainersCmd().withShowAll(false).exec();
    }

    public InspectContainerResponse inspectContainer(String containerId) {
        return dockerClient.inspectContainerCmd(containerId).exec();
    }

    public String getContainerLogs(String containerId, int tail, boolean timestamps) {
        StringBuilder logs = new StringBuilder();
        try {
            dockerClient
                    .logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tail)
                    .withTimestamps(timestamps)
                    .exec(
                            new ResultCallback.Adapter<Frame>() {
                                @Override
                                public void onNext(Frame frame) {
                                    logs.append(new String(frame.getPayload()));
                                }
                            })
                    .awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return logs.toString();
    }

    public HealthState getContainerHealth(String containerId) {
        InspectContainerResponse inspection = inspectContainer(containerId);

        if (inspection.getState() != null && inspection.getState().getHealth() != null) {
            return inspection.getState().getHealth();
        }

        return null;
    }

    public CreateContainerResponse createContainer(
            String imageName,
            String containerName,
            List<String> environmentVariables,
            Map<Integer, Integer> portBindings,
            List<String> volumeBindings,
            String networkName) {
        return createContainer(
                imageName,
                containerName,
                environmentVariables,
                portBindings,
                volumeBindings,
                networkName,
                null,
                null);
    }

    public CreateContainerResponse createContainer(
            String imageName,
            String containerName,
            List<String> environmentVariables,
            Map<Integer, Integer> portBindings,
            List<String> volumeBindings,
            String networkName,
            List<String> extraHosts) {
        return createContainer(
                imageName,
                containerName,
                environmentVariables,
                portBindings,
                volumeBindings,
                networkName,
                extraHosts,
                null);
    }

    public CreateContainerResponse createContainer(
            String imageName,
            String containerName,
            List<String> environmentVariables,
            Map<Integer, Integer> portBindings,
            List<String> volumeBindings,
            String networkName,
            List<String> extraHosts,
            String user) {
        var cmd = dockerClient.createContainerCmd(imageName).withName(containerName);

        if (environmentVariables != null && !environmentVariables.isEmpty()) {
            cmd.withEnv(environmentVariables);
        }

        if (user != null && !user.isBlank()) {
            cmd.withUser(user);
        }

        HostConfig hostConfig = HostConfig.newHostConfig();

        if (portBindings != null && !portBindings.isEmpty()) {
            Ports bindings = new Ports();
            List<ExposedPort> exposedPorts = new java.util.ArrayList<>();

            portBindings.forEach(
                    (containerPort, hostPort) -> {
                        ExposedPort exposed = ExposedPort.tcp(containerPort);
                        exposedPorts.add(exposed);
                        bindings.bind(exposed, Ports.Binding.bindPort(hostPort));
                    });

            hostConfig.withPortBindings(bindings);
            cmd.withExposedPorts(exposedPorts);
        }

        if (volumeBindings != null && !volumeBindings.isEmpty()) {
            hostConfig.withBinds(volumeBindings.stream().map(Bind::parse).toArray(Bind[]::new));
        }

        if (networkName != null) {
            hostConfig.withNetworkMode(networkName);
        }

        if (extraHosts != null && !extraHosts.isEmpty()) {
            hostConfig.withExtraHosts(extraHosts.toArray(new String[0]));
        }

        cmd.withHostConfig(hostConfig);

        return cmd.exec();
    }

    public void startContainer(String containerId) {
        dockerClient.startContainerCmd(containerId).exec();
    }

    public void stopContainer(String containerId) {
        dockerClient.stopContainerCmd(containerId).exec();
    }

    public void stopContainer(String containerId, int timeoutSeconds) {
        dockerClient.stopContainerCmd(containerId).withTimeout(timeoutSeconds).exec();
    }

    public void restartContainer(String containerId) {
        dockerClient.restartContainerCmd(containerId).exec();
    }

    public void pauseContainer(String containerId) {
        dockerClient.pauseContainerCmd(containerId).exec();
    }

    public void unpauseContainer(String containerId) {
        dockerClient.unpauseContainerCmd(containerId).exec();
    }

    public void killContainer(String containerId) {
        dockerClient.killContainerCmd(containerId).exec();
    }

    public void killContainer(String containerId, String signal) {
        dockerClient.killContainerCmd(containerId).withSignal(signal).exec();
    }

    public void removeContainer(String containerId) {
        dockerClient.removeContainerCmd(containerId).exec();
    }

    public void forceRemoveContainer(String containerId) {
        dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
    }

    public void renameContainer(String containerId, String newName) {
        dockerClient.renameContainerCmd(containerId).withName(newName).exec();
    }

    public TopContainerResponse getContainerProcesses(String containerId) {
        return dockerClient.topContainerCmd(containerId).exec();
    }

    public String execCommand(String containerId, String... command) {
        ExecCreateCmdResponse execCreate =
                dockerClient
                        .execCreateCmd(containerId)
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .withCmd(command)
                        .exec();

        StringBuilder output = new StringBuilder();

        try {
            dockerClient
                    .execStartCmd(execCreate.getId())
                    .exec(
                            new ResultCallback.Adapter<Frame>() {
                                @Override
                                public void onNext(Frame frame) {
                                    output.append(new String(frame.getPayload()));
                                }
                            })
                    .awaitCompletion(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return output.toString();
    }

    /** Output and exit code of a command run inside a container. */
    public record ExecResult(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * Runs a command in a container and returns its exit code as well as its output. (The plain
     * {@link #execCommand} only returns output, so failures such as an invalid nginx config went
     * unnoticed.)
     */
    public ExecResult exec(String containerId, String... command) {
        ExecCreateCmdResponse execCreate =
                dockerClient
                        .execCreateCmd(containerId)
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .withCmd(command)
                        .exec();

        StringBuilder output = new StringBuilder();
        try {
            dockerClient
                    .execStartCmd(execCreate.getId())
                    .exec(
                            new ResultCallback.Adapter<Frame>() {
                                @Override
                                public void onNext(Frame frame) {
                                    output.append(
                                            new String(
                                                    frame.getPayload(),
                                                    java.nio.charset.StandardCharsets.UTF_8));
                                }
                            })
                    .awaitCompletion(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Long exit = dockerClient.inspectExecCmd(execCreate.getId()).exec().getExitCodeLong();
        return new ExecResult(exit == null ? -1 : exit.intValue(), output.toString());
    }

    /**
     * Writes files into a container (running or stopped) through the archive API, so contents never
     * pass through a shell command line. Keys are absolute paths inside the container. Missing
     * parent directories are created.
     */
    public void putFiles(String containerId, Map<String, byte[]> files, int mode) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (var tar =
                new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(buffer)) {
            tar.setLongFileMode(
                    org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
                            .LONGFILE_POSIX);
            java.util.Set<String> dirs = new java.util.TreeSet<>();
            for (String path : files.keySet()) {
                String rel = path.replaceFirst("^/+", "");
                int idx = rel.indexOf('/');
                while (idx > 0) {
                    dirs.add(rel.substring(0, idx + 1));
                    idx = rel.indexOf('/', idx + 1);
                }
            }
            for (String dir : dirs) {
                var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(dir);
                entry.setMode(040755);
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
            }
            for (var file : files.entrySet()) {
                String rel = file.getKey().replaceFirst("^/+", "");
                var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(rel);
                entry.setSize(file.getValue().length);
                entry.setMode(0100000 | mode);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
            tar.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build archive: " + e.getMessage(), e);
        }

        dockerClient
                .copyArchiveToContainerCmd(containerId)
                .withRemotePath("/")
                .withTarInputStream(new java.io.ByteArrayInputStream(buffer.toByteArray()))
                .exec();
    }

    public long pruneBuildCache() {
        Long reclaimed = dockerClient.pruneCmd(PruneType.BUILD).exec().getSpaceReclaimed();
        return reclaimed == null ? 0 : reclaimed;
    }

    public void pruneNetworks() {
        dockerClient.pruneCmd(PruneType.NETWORKS).exec();
    }

    public List<InspectVolumeResponse> listUnusedVolumes() {
        ListVolumesResponse response =
                dockerClient.listVolumesCmd().withDanglingFilter(true).exec();
        return response.getVolumes() == null ? List.of() : response.getVolumes();
    }

    public DockerClient client() {
        return dockerClient;
    }

    /**
     * Top-level images, as `docker images` shows them. (This used to pass showAll, which also
     * returns intermediate build layers and inflated the image count.)
     */
    public List<Image> listImages() {
        return dockerClient.listImagesCmd().exec();
    }

    public List<Image> listDanglingImages() {
        return dockerClient.listImagesCmd().withDanglingFilter(true).exec();
    }

    public InspectImageResponse inspectImage(String imageId) {
        return dockerClient.inspectImageCmd(imageId).exec();
    }

    public void pullImage(String imageName) throws InterruptedException {
        dockerClient.pullImageCmd(imageName).exec(new PullImageResultCallback()).awaitCompletion();
    }

    public void pullImage(String imageName, AuthConfig authConfig) throws InterruptedException {
        if (authConfig != null) {
            dockerClient
                    .pullImageCmd(imageName)
                    .withAuthConfig(authConfig)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
        } else {
            pullImage(imageName);
        }
    }

    public void removeImage(String imageId) {
        dockerClient.removeImageCmd(imageId).exec();
    }

    public void forceRemoveImage(String imageId) {
        dockerClient.removeImageCmd(imageId).withForce(true).withNoPrune(false).exec();
    }

    public ListVolumesResponse listVolumes() {
        return dockerClient.listVolumesCmd().exec();
    }

    public CreateVolumeResponse createVolume(String volumeName) {
        return dockerClient.createVolumeCmd().withName(volumeName).exec();
    }

    public void removeVolume(String volumeName) {
        dockerClient.removeVolumeCmd(volumeName).exec();
    }

    public List<Network> listNetworks() {
        return dockerClient.listNetworksCmd().exec();
    }

    public Network inspectNetwork(String networkId) {
        return dockerClient.inspectNetworkCmd().withNetworkId(networkId).exec();
    }

    public CreateNetworkResponse createNetwork(String networkName) {
        return dockerClient.createNetworkCmd().withName(networkName).exec();
    }

    public void removeNetwork(String networkId) {
        dockerClient.removeNetworkCmd(networkId).exec();
    }

    public void connectContainerToNetwork(String networkId, String containerId) {
        dockerClient
                .connectToNetworkCmd()
                .withNetworkId(networkId)
                .withContainerId(containerId)
                .exec();
    }

    public void disconnectContainerFromNetwork(String networkId, String containerId) {
        dockerClient
                .disconnectFromNetworkCmd()
                .withNetworkId(networkId)
                .withContainerId(containerId)
                .exec();
    }

    public boolean isRunningAsRoot(String containerId) {
        InspectContainerResponse inspection = inspectContainer(containerId);
        String user = inspection.getConfig().getUser();
        return user == null || user.isEmpty() || user.equals("0") || user.equals("root");
    }

    public boolean isPrivileged(String containerId) {
        InspectContainerResponse inspection = inspectContainer(containerId);
        return Boolean.TRUE.equals(inspection.getHostConfig().getPrivileged());
    }

    public void closeDockerClient() throws IOException {
        dockerClient.close();
    }

    public Info getHostInfo() {
        return dockerClient.infoCmd().exec();
    }

    public Statistics getContainerStats(String containerId) {
        final Statistics[] result = new Statistics[1];

        try {
            dockerClient
                    .statsCmd(containerId)
                    .withNoStream(true)
                    .exec(
                            new ResultCallback.Adapter<Statistics>() {
                                @Override
                                public void onNext(Statistics stats) {
                                    result[0] = stats;
                                }
                            })
                    .awaitCompletion(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return result[0];
    }
}
