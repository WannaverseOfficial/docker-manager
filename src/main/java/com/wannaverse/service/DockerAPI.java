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

        // Set user if specified (e.g., "1000", "1000:1000", "nobody")
        if (user != null && !user.isBlank()) {
            cmd.withUser(user);
        }

        // Build a single HostConfig with all settings combined
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

    public List<Image> listImages() {
        return dockerClient.listImagesCmd().withShowAll(true).exec();
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

    // ==================== Stats & Info ====================

    /** Get Docker daemon/host information. */
    public Info getHostInfo() {
        return dockerClient.infoCmd().exec();
    }

    /** Get container stats (single snapshot). Uses a blocking approach to get one stats reading. */
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
                    .awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return result[0];
    }
}
