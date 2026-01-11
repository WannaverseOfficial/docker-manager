package com.wannaverse.service;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.wannaverse.persistence.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ContainerHealthMonitorService {
    private static final Logger log = LoggerFactory.getLogger(ContainerHealthMonitorService.class);

    private final DockerHostRepository hostRepository;
    private final ContainerHealthEventRepository eventRepository;
    private final ContainerMonitorStateRepository stateRepository;
    private final DockerService dockerService;
    private final NotificationService notificationService;

    @Value("${docker.health.monitor.enabled:true}")
    private boolean monitoringEnabled;

    @Value("${docker.health.monitor.restart-loop-threshold:3}")
    private int restartLoopThreshold;

    @Value("${docker.health.monitor.restart-loop-window:300000}")
    private long restartLoopWindowMs;

    @Value("${docker.health.monitor.notification-cooldown:3600000}")
    private long notificationCooldownMs;

    public ContainerHealthMonitorService(
            DockerHostRepository hostRepository,
            ContainerHealthEventRepository eventRepository,
            ContainerMonitorStateRepository stateRepository,
            DockerService dockerService,
            NotificationService notificationService) {
        this.hostRepository = hostRepository;
        this.eventRepository = eventRepository;
        this.stateRepository = stateRepository;
        this.dockerService = dockerService;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedRateString = "${docker.health.monitor.interval:30000}")
    public void monitorContainerHealth() {
        if (!monitoringEnabled) {
            return;
        }

        List<DockerHost> hosts = hostRepository.findAll();
        for (DockerHost host : hosts) {
            try {
                monitorHost(host);
            } catch (Exception e) {
                log.warn("Failed to monitor host {}: {}", host.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void monitorHost(DockerHost host) {
        DockerAPI api;
        try {
            api =
                    dockerService.dockerAPI(
                            dockerService.createClientCached(host.getDockerHostUrl()));
            if (!api.ping()) {
                log.debug("Host {} is not reachable, skipping health check", host.getId());
                return;
            }
        } catch (Exception e) {
            log.debug("Cannot connect to host {}: {}", host.getId(), e.getMessage());
            return;
        }

        List<Container> containers = api.listAllContainers();
        List<String> activeContainerIds =
                containers.stream().map(Container::getId).collect(Collectors.toList());

        if (!activeContainerIds.isEmpty()) {
            stateRepository.deleteStaleEntries(host.getId(), activeContainerIds);
        }

        for (Container container : containers) {
            try {
                checkContainer(host, api, container);
            } catch (Exception e) {
                log.warn(
                        "Failed to check container {} on host {}: {}",
                        container.getId(),
                        host.getId(),
                        e.getMessage());
            }
        }

        resolveOldEvents(host, activeContainerIds);
    }

    private void checkContainer(DockerHost host, DockerAPI api, Container container) {
        String containerId = container.getId();
        String containerName = getContainerName(container);

        // Get or create monitor state
        ContainerMonitorState state =
                stateRepository
                        .findByDockerHostIdAndContainerId(host.getId(), containerId)
                        .orElseGet(
                                () -> {
                                    ContainerMonitorState newState = new ContainerMonitorState();
                                    newState.setDockerHost(host);
                                    newState.setContainerId(containerId);
                                    newState.setContainerName(containerName);
                                    return newState;
                                });

        InspectContainerResponse inspect;
        try {
            inspect = api.inspectContainer(containerId);
        } catch (Exception e) {
            log.debug("Cannot inspect container {}: {}", containerId, e.getMessage());
            return;
        }

        InspectContainerResponse.ContainerState containerState = inspect.getState();
        if (containerState == null) {
            return;
        }

        String currentStatus = containerState.getStatus();
        Integer currentRestartCount =
                inspect.getRestartCount() != null ? inspect.getRestartCount() : 0;

        if (Boolean.TRUE.equals(containerState.getOOMKilled())) {
            handleOomKill(host, containerId, containerName, state);
        }

        if ("exited".equalsIgnoreCase(currentStatus)) {
            Integer exitCode = containerState.getExitCode();
            if (exitCode != null && exitCode != 0) {
                if ("running".equalsIgnoreCase(state.getLastKnownStatus())) {
                    handleCrash(host, containerId, containerName, exitCode, state);
                }
            }
        }

        if (state.getLastRestartCount() != null
                && currentRestartCount > state.getLastRestartCount()) {
            long now = System.currentTimeMillis();

            if (state.getLastRestartCountChange() == 0) {
                state.setLastRestartCountChange(now);
                state.setRestartCountAtWindowStart(state.getLastRestartCount());
            }

            long timeSinceWindowStart = now - state.getLastRestartCountChange();
            if (timeSinceWindowStart > restartLoopWindowMs) {
                state.setLastRestartCountChange(now);
                state.setRestartCountAtWindowStart(state.getLastRestartCount());
            }

            Integer baseline = state.getRestartCountAtWindowStart();
            if (baseline == null) {
                baseline = state.getLastRestartCount();
                state.setRestartCountAtWindowStart(baseline);
            }
            int restartsSinceWindow = currentRestartCount - baseline;

            log.debug(
                    "Container {} restart check: current={}, baseline={}, delta={}, threshold={}",
                    containerName,
                    currentRestartCount,
                    baseline,
                    restartsSinceWindow,
                    restartLoopThreshold);

            if (restartsSinceWindow >= restartLoopThreshold) {
                handleRestartLoop(host, containerId, containerName, currentRestartCount, state);
                state.setLastRestartCountChange(0);
                state.setRestartCountAtWindowStart(null);
            }
        } else if (currentRestartCount.equals(state.getLastRestartCount())) {
            if (state.getLastRestartCountChange() > 0
                    && System.currentTimeMillis() - state.getLastRestartCountChange()
                            > restartLoopWindowMs) {
                state.setLastRestartCountChange(0);
                state.setRestartCountAtWindowStart(null);
            }
        }

        if (containerState.getHealth() != null) {
            String healthStatus = containerState.getHealth().getStatus();
            if ("unhealthy".equalsIgnoreCase(healthStatus)) {
                handleUnhealthy(host, containerId, containerName, state);
            }
        }

        state.setContainerName(containerName);
        state.setLastKnownStatus(currentStatus);
        state.setLastRestartCount(currentRestartCount);
        stateRepository.save(state);
    }

    private void handleCrash(
            DockerHost host,
            String containerId,
            String containerName,
            int exitCode,
            ContainerMonitorState state) {

        log.info(
                "Container crash detected: {} ({}) on host {} with exit code {}",
                containerName,
                containerId,
                host.getId(),
                exitCode);

        ContainerHealthEvent event = new ContainerHealthEvent();
        event.setDockerHost(host);
        event.setContainerId(containerId);
        event.setContainerName(containerName);
        event.setEventType(ContainerHealthEvent.EventType.CRASH);
        event.setExitCode(exitCode);
        event.setErrorMessage("Container exited with code " + exitCode);
        eventRepository.save(event);

        if (state.canNotifyCrash(notificationCooldownMs)) {
            try {
                notificationService.notifyContainerStopped(host, containerId, containerName);
                event.setNotificationSent(true);
                eventRepository.save(event);
                state.markCrashNotified();
            } catch (Exception e) {
                log.warn("Failed to send crash notification: {}", e.getMessage());
            }
        }
    }

    private void handleRestartLoop(
            DockerHost host,
            String containerId,
            String containerName,
            int restartCount,
            ContainerMonitorState state) {

        if (eventRepository
                .findByDockerHostIdAndContainerIdAndEventTypeAndResolvedAtIsNull(
                        host.getId(), containerId, ContainerHealthEvent.EventType.RESTART_LOOP)
                .isPresent()) {
            return;
        }

        log.info(
                "Restart loop detected: {} ({}) on host {} with {} restarts",
                containerName,
                containerId,
                host.getId(),
                restartCount);

        ContainerHealthEvent event = new ContainerHealthEvent();
        event.setDockerHost(host);
        event.setContainerId(containerId);
        event.setContainerName(containerName);
        event.setEventType(ContainerHealthEvent.EventType.RESTART_LOOP);
        event.setRestartCount(restartCount);
        event.setErrorMessage(
                "Container has restarted "
                        + restartLoopThreshold
                        + "+ times within "
                        + (restartLoopWindowMs / 60000)
                        + " minutes");
        eventRepository.save(event);

        if (state.canNotifyRestartLoop(notificationCooldownMs)) {
            try {
                notificationService.notifyContainerRestartLoop(
                        host, containerId, containerName, restartCount);
                event.setNotificationSent(true);
                eventRepository.save(event);
                state.markRestartLoopNotified();
            } catch (Exception e) {
                log.warn("Failed to send restart loop notification: {}", e.getMessage());
            }
        }
    }

    private void handleOomKill(
            DockerHost host,
            String containerId,
            String containerName,
            ContainerMonitorState state) {

        long recentWindow = 60000;
        List<ContainerHealthEvent> recentEvents =
                eventRepository.findRecentEvents(
                        host.getId(),
                        containerId,
                        ContainerHealthEvent.EventType.OOM_KILLED,
                        System.currentTimeMillis() - recentWindow);

        if (!recentEvents.isEmpty()) {
            return;
        }

        log.info("OOM kill detected: {} ({}) on host {}", containerName, containerId, host.getId());

        ContainerHealthEvent event = new ContainerHealthEvent();
        event.setDockerHost(host);
        event.setContainerId(containerId);
        event.setContainerName(containerName);
        event.setEventType(ContainerHealthEvent.EventType.OOM_KILLED);
        event.setErrorMessage("Container was killed due to out of memory");
        eventRepository.save(event);

        if (state.canNotifyCrash(notificationCooldownMs)) {
            try {
                notificationService.notifyContainerOomKilled(host, containerId, containerName);
                event.setNotificationSent(true);
                eventRepository.save(event);
                state.markCrashNotified();
            } catch (Exception e) {
                log.warn("Failed to send OOM notification: {}", e.getMessage());
            }
        }
    }

    private void handleUnhealthy(
            DockerHost host,
            String containerId,
            String containerName,
            ContainerMonitorState state) {

        if (eventRepository
                .findByDockerHostIdAndContainerIdAndEventTypeAndResolvedAtIsNull(
                        host.getId(), containerId, ContainerHealthEvent.EventType.HEALTH_UNHEALTHY)
                .isPresent()) {
            return;
        }

        log.info(
                "Unhealthy container detected: {} ({}) on host {}",
                containerName,
                containerId,
                host.getId());

        ContainerHealthEvent event = new ContainerHealthEvent();
        event.setDockerHost(host);
        event.setContainerId(containerId);
        event.setContainerName(containerName);
        event.setEventType(ContainerHealthEvent.EventType.HEALTH_UNHEALTHY);
        event.setErrorMessage("Container health check is failing");
        eventRepository.save(event);

        if (state.canNotifyHealth(notificationCooldownMs)) {
            try {
                notificationService.notifyContainerHealthCheckFailed(
                        host, containerId, containerName);
                event.setNotificationSent(true);
                eventRepository.save(event);
                state.markHealthNotified();
            } catch (Exception e) {
                log.warn("Failed to send health notification: {}", e.getMessage());
            }
        }
    }

    private void resolveOldEvents(DockerHost host, List<String> activeContainerIds) {
        List<ContainerHealthEvent> activeEvents =
                eventRepository.findByDockerHostIdAndResolvedAtIsNullOrderByDetectedAtDesc(
                        host.getId());

        for (ContainerHealthEvent event : activeEvents) {
            if (!activeContainerIds.contains(event.getContainerId())) {
                event.resolve();
                eventRepository.save(event);
                log.debug("Resolved event {} - container no longer exists", event.getId());
                continue;
            }

            if (event.getEventType() == ContainerHealthEvent.EventType.RESTART_LOOP) {
                ContainerMonitorState state =
                        stateRepository
                                .findByDockerHostIdAndContainerId(
                                        host.getId(), event.getContainerId())
                                .orElse(null);

                if (state != null && state.getLastRestartCountChange() > 0) {
                    long timeSinceLastRestart =
                            System.currentTimeMillis() - state.getLastRestartCountChange();
                    if (timeSinceLastRestart > restartLoopWindowMs * 2) {
                        event.resolve();
                        eventRepository.save(event);
                        log.debug(
                                "Resolved restart loop event {} - container is stable",
                                event.getId());
                    }
                }
            }
        }
    }

    private String getContainerName(Container container) {
        if (container.getNames() != null && container.getNames().length > 0) {
            String name = container.getNames()[0];
            return name.startsWith("/") ? name.substring(1) : name;
        }
        return container.getId().substring(0, 12);
    }
}
