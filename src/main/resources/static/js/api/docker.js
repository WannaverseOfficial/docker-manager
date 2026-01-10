// Docker API Module
import { dockerApi } from './client.js';
import { state, setState } from '../state.js';
import { updateConnectionStatus } from '../app.js';
import { normalizeKeys } from '../utils/format.js';

// ==================== Hosts ====================

export async function loadHosts() {
    const hosts = await dockerApi('/hosts');
    setState('hosts', hosts);
    return hosts;
}

export async function addHost(url) {
    const host = await dockerApi('/hosts', {
        method: 'POST',
        body: JSON.stringify({ dockerHostUrl: url }),
    });
    await loadHosts();
    return host;
}

export async function deleteHost(hostId) {
    await dockerApi(`/hosts/${hostId}`, { method: 'DELETE' });
    await loadHosts();
}

export async function checkHostConnection(hostId) {
    updateConnectionStatus('checking');
    try {
        await dockerApi(`/hosts/${hostId}/ping`);
        updateConnectionStatus('connected');
        return true;
    } catch (error) {
        updateConnectionStatus('disconnected');
        return false;
    }
}

// ==================== Containers ====================

export async function listContainers(hostId, all = true) {
    const data = await dockerApi(`/hosts/${hostId}/containers?all=${all}`);
    return normalizeKeys(data);
}

export async function getContainer(hostId, containerId) {
    const data = await dockerApi(`/hosts/${hostId}/containers/${containerId}`);
    return normalizeKeys(data);
}

export async function createContainer(hostId, config) {
    return dockerApi(`/hosts/${hostId}/containers`, {
        method: 'POST',
        body: JSON.stringify(config),
    });
}

export async function startContainer(hostId, containerId) {
    return dockerApi(`/hosts/${hostId}/containers/${containerId}/start`, {
        method: 'POST',
    });
}

export async function stopContainer(hostId, containerId) {
    return dockerApi(`/hosts/${hostId}/containers/${containerId}/stop`, {
        method: 'POST',
    });
}

export async function restartContainer(hostId, containerId) {
    return dockerApi(`/hosts/${hostId}/containers/${containerId}/restart`, {
        method: 'POST',
    });
}

export async function pauseContainer(hostId, containerId) {
    return dockerApi(`/hosts/${hostId}/containers/${containerId}/pause`, {
        method: 'POST',
    });
}

export async function unpauseContainer(hostId, containerId) {
    return dockerApi(`/hosts/${hostId}/containers/${containerId}/unpause`, {
        method: 'POST',
    });
}

export async function removeContainer(hostId, containerId, force = false) {
    return dockerApi(`/hosts/${hostId}/containers/${containerId}?force=${force}`, {
        method: 'DELETE',
    });
}

export async function execContainer(hostId, containerId, command) {
    // Wrap command in shell invocation so users can type normal commands
    // e.g., "ls -la" becomes ["sh", "-c", "ls -la"]
    const commandArray = ['sh', '-c', command];

    return dockerApi(`/hosts/${hostId}/containers/${containerId}/exec`, {
        method: 'POST',
        body: JSON.stringify({ command: commandArray }),
    });
}

export async function getContainerHealth(hostId, containerId) {
    const data = await dockerApi(`/hosts/${hostId}/containers/${containerId}/health`);
    return normalizeKeys(data);
}

export async function getContainerLogs(hostId, containerId, tail = 500, timestamps = false) {
    return dockerApi(`/hosts/${hostId}/containers/${containerId}/logs?tail=${tail}&timestamps=${timestamps}`);
}

export async function checkContainerRoot(hostId, containerId) {
    return dockerApi(`/hosts/${hostId}/containers/${containerId}/security/root`);
}

export async function checkContainerPrivileged(hostId, containerId) {
    return dockerApi(`/hosts/${hostId}/containers/${containerId}/security/privileged`);
}

// ==================== Docker Compose ====================

export async function deployCompose(hostId, composeContent, projectName = null) {
    return dockerApi(`/hosts/${hostId}/compose`, {
        method: 'POST',
        body: JSON.stringify({
            composeContent,
            projectName,
        }),
    });
}

// ==================== Images ====================

export async function listImages(hostId) {
    const data = await dockerApi(`/hosts/${hostId}/images`);
    return normalizeKeys(data);
}

export async function getImage(hostId, imageId) {
    const data = await dockerApi(`/hosts/${hostId}/images/${imageId}`);
    return normalizeKeys(data);
}

export async function pullImage(hostId, imageName) {
    return dockerApi(`/hosts/${hostId}/images/pull?imageName=${encodeURIComponent(imageName)}`, {
        method: 'POST',
    });
}

export async function removeImage(hostId, imageId, force = false) {
    return dockerApi(`/hosts/${hostId}/images/${imageId}?force=${force}`, {
        method: 'DELETE',
    });
}

export async function getDanglingImages(hostId) {
    const data = await dockerApi(`/hosts/${hostId}/images?dangling=true`);
    return normalizeKeys(data);
}

export async function cleanupDanglingImages(hostId) {
    return dockerApi(`/hosts/${hostId}/images/dangling`, {
        method: 'DELETE',
    });
}

// ==================== Volumes ====================

export async function listVolumes(hostId) {
    const response = await dockerApi(`/hosts/${hostId}/volumes`);
    // Volumes endpoint returns { volumes: [...], warnings: [...] }
    const normalized = normalizeKeys(response);
    return normalized.volumes || [];
}

export async function createVolume(hostId, name) {
    return dockerApi(`/hosts/${hostId}/volumes?volumeName=${encodeURIComponent(name)}`, {
        method: 'POST',
    });
}

export async function removeVolume(hostId, name) {
    return dockerApi(`/hosts/${hostId}/volumes/${encodeURIComponent(name)}`, {
        method: 'DELETE',
    });
}

// ==================== Networks ====================

export async function listNetworks(hostId) {
    const data = await dockerApi(`/hosts/${hostId}/networks`);
    return normalizeKeys(data);
}

export async function getNetwork(hostId, networkId) {
    const data = await dockerApi(`/hosts/${hostId}/networks/${networkId}`);
    return normalizeKeys(data);
}

export async function createNetwork(hostId, name) {
    return dockerApi(`/hosts/${hostId}/networks?networkName=${encodeURIComponent(name)}`, {
        method: 'POST',
    });
}

export async function removeNetwork(hostId, networkId) {
    return dockerApi(`/hosts/${hostId}/networks/${networkId}`, {
        method: 'DELETE',
    });
}

export async function connectToNetwork(hostId, networkId, containerId) {
    return dockerApi(`/hosts/${hostId}/networks/${networkId}/connect?containerId=${containerId}`, {
        method: 'POST',
    });
}

export async function disconnectFromNetwork(hostId, networkId, containerId) {
    return dockerApi(`/hosts/${hostId}/networks/${networkId}/disconnect?containerId=${containerId}`, {
        method: 'POST',
    });
}

// ==================== Dashboard Stats ====================

export async function getDashboardStats(hostId) {
    const [containers, images, volumes, networks] = await Promise.all([
        listContainers(hostId, true),
        listImages(hostId),
        listVolumes(hostId),
        listNetworks(hostId),
    ]);

    const runningContainers = containers.filter(c => c.state === 'running').length;

    return {
        totalContainers: containers.length,
        runningContainers,
        totalImages: images.length,
        totalVolumes: volumes.length,
        totalNetworks: networks.length,
        containers: containers.slice(0, 5), // Recent 5
    };
}

// ==================== Host Info & Container Stats ====================

export async function getHostInfo(hostId) {
    const data = await dockerApi(`/hosts/${hostId}/info`);
    return normalizeKeys(data);
}

export async function getContainerStats(hostId) {
    const data = await dockerApi(`/hosts/${hostId}/stats`);
    return normalizeKeys(data);
}

export async function getSingleContainerStats(hostId, containerId) {
    const data = await dockerApi(`/hosts/${hostId}/containers/${containerId}/stats`);
    return normalizeKeys(data);
}

// ==================== Drift Detection ====================

export async function getHostDrift(hostId) {
    const data = await dockerApi(`/hosts/${hostId}/drift`);
    return normalizeKeys(data);
}

export async function getContainerDrift(hostId, containerId) {
    const data = await dockerApi(`/hosts/${hostId}/containers/${containerId}/drift`);
    return normalizeKeys(data);
}

export async function checkHostDrift(hostId) {
    const data = await dockerApi(`/hosts/${hostId}/drift/check`, {
        method: 'POST',
    });
    return normalizeKeys(data);
}

export async function checkContainerDrift(hostId, containerId) {
    const data = await dockerApi(`/hosts/${hostId}/containers/${containerId}/drift/check`, {
        method: 'POST',
    });
    return normalizeKeys(data);
}
