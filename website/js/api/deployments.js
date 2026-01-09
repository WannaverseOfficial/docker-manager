// Deployments API Module
import { deploymentsApi, rollbackApi, diffApi } from './client.js';

// ==================== Operations ====================

export async function listOperations(hostId, params = {}) {
    const queryParams = new URLSearchParams();
    if (params.page !== undefined) queryParams.set('page', params.page);
    if (params.size !== undefined) queryParams.set('size', params.size);
    if (params.type) queryParams.set('type', params.type);
    if (params.status) queryParams.set('status', params.status);

    const queryString = queryParams.toString();
    return deploymentsApi(`/hosts/${hostId}/operations${queryString ? '?' + queryString : ''}`);
}

export async function getOperation(operationId) {
    return deploymentsApi(`/operations/${operationId}`);
}

export async function getOperationSnapshots(operationId) {
    return deploymentsApi(`/operations/${operationId}/snapshots`);
}

export async function getOperationLogs(operationId) {
    return deploymentsApi(`/operations/${operationId}/logs`);
}

// ==================== Compose Deployments ====================

export async function listComposeDeployments(hostId, params = {}) {
    const queryParams = new URLSearchParams();
    if (params.page !== undefined) queryParams.set('page', params.page);
    if (params.size !== undefined) queryParams.set('size', params.size);

    const queryString = queryParams.toString();
    return deploymentsApi(`/hosts/${hostId}/compose${queryString ? '?' + queryString : ''}`);
}

export async function getComposeDeployment(deploymentId) {
    return deploymentsApi(`/compose/${deploymentId}`);
}

export async function getProjectHistory(hostId, projectName) {
    return deploymentsApi(`/hosts/${hostId}/compose/project/${encodeURIComponent(projectName)}/history`);
}

export async function getProjectNames(hostId) {
    return deploymentsApi(`/hosts/${hostId}/compose/projects`);
}

export async function getSummary(hostId) {
    return deploymentsApi(`/hosts/${hostId}/summary`);
}

// ==================== Rollback ====================

export async function getRollbackPoints(hostId, resourceType, resourceId) {
    return rollbackApi(`/hosts/${hostId}/points/${resourceType}/${resourceId}`);
}

export async function validateRollback(operationId) {
    return rollbackApi(`/validate/${operationId}`);
}

export async function executeRollback(operationId) {
    return rollbackApi(`/operation/${operationId}`, {
        method: 'POST',
    });
}

export async function rollbackComposeDeployment(deploymentId, version) {
    return rollbackApi(`/compose/${deploymentId}/version/${version}`, {
        method: 'POST',
    });
}

// ==================== Diff ====================

export async function getOperationDiff(operationId) {
    return diffApi(`/operations/${operationId}`);
}

export async function compareComposeDeployments(deploymentId1, deploymentId2) {
    return diffApi(`/compose/${deploymentId1}/${deploymentId2}`);
}

export async function compareSnapshots(snapshotId1, snapshotId2) {
    return diffApi(`/snapshots/${snapshotId1}/${snapshotId2}`);
}

export async function compareComposeVersions(projectName, v1, v2, hostId) {
    return diffApi(`/compose/project/${encodeURIComponent(projectName)}/versions/${v1}/${v2}?hostId=${hostId}`);
}
