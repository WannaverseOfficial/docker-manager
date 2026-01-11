// Pipelines API Client
import { apiCall } from './client.js';

const API_BASE = '/api/pipelines';

// ==================== Pipelines ====================

export async function listPipelines() {
    return apiCall(API_BASE);
}

export async function listPipelinesByHost(hostId) {
    return apiCall(`${API_BASE}/hosts/${hostId}`);
}

export async function getPipeline(id) {
    return apiCall(`${API_BASE}/${id}`);
}

export async function createPipeline(request) {
    return apiCall(API_BASE, {
        method: 'POST',
        body: JSON.stringify(request),
    });
}

export async function updatePipeline(id, request) {
    return apiCall(`${API_BASE}/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
    });
}

export async function deletePipeline(id) {
    return apiCall(`${API_BASE}/${id}`, {
        method: 'DELETE',
    });
}

export async function duplicatePipeline(id, name) {
    return apiCall(`${API_BASE}/${id}/duplicate`, {
        method: 'POST',
        body: JSON.stringify({ name }),
    });
}

export async function togglePipelineEnabled(id, enabled) {
    return apiCall(`${API_BASE}/${id}/enabled`, {
        method: 'PATCH',
        body: JSON.stringify({ enabled }),
    });
}

// ==================== Executions ====================

export async function triggerExecution(pipelineId, commit, branch) {
    return apiCall(`${API_BASE}/${pipelineId}/trigger`, {
        method: 'POST',
        body: JSON.stringify({ commit, branch }),
    });
}

export async function listExecutions(pipelineId) {
    return apiCall(`${API_BASE}/${pipelineId}/executions`);
}

export async function getExecution(executionId) {
    return apiCall(`${API_BASE}/executions/${executionId}`);
}

export async function cancelExecution(executionId) {
    return apiCall(`${API_BASE}/executions/${executionId}/cancel`, {
        method: 'POST',
    });
}

export async function getExecutionLogs(executionId) {
    return apiCall(`${API_BASE}/executions/${executionId}/logs`);
}

export function streamExecutionLogs(executionId, onLog, onComplete, onError) {
    const eventSource = new EventSource(`${API_BASE}/executions/${executionId}/logs/stream`);

    eventSource.addEventListener('log', (event) => {
        onLog(event.data);
    });

    eventSource.addEventListener('complete', () => {
        eventSource.close();
        if (onComplete) onComplete();
    });

    eventSource.onerror = (error) => {
        eventSource.close();
        if (onError) onError(error);
    };

    return eventSource;
}
