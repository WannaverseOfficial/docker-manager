// Git Repositories API Module
import { gitApi, GIT_API } from './client.js';
import { state } from '../state.js';

// ==================== Repositories ====================

export async function listRepositories() {
    return gitApi('/repositories');
}

export async function getRepository(id) {
    return gitApi(`/repositories/${id}`);
}

export async function createRepository(data) {
    return gitApi('/repositories', {
        method: 'POST',
        body: JSON.stringify(data),
    });
}

export async function updateRepository(id, data) {
    return gitApi(`/repositories/${id}`, {
        method: 'PUT',
        body: JSON.stringify(data),
    });
}

export async function deleteRepository(id) {
    return gitApi(`/repositories/${id}`, {
        method: 'DELETE',
    });
}

export async function regenerateWebhook(id) {
    return gitApi(`/repositories/${id}/regenerate-webhook`, {
        method: 'POST',
    });
}

// ==================== Deployments ====================

export async function deployRepository(id, commitSha = null) {
    const body = commitSha ? { commitSha } : undefined;
    return gitApi(`/repositories/${id}/deploy`, {
        method: 'POST',
        body: body ? JSON.stringify(body) : undefined,
    });
}

export async function pollRepository(id) {
    return gitApi(`/repositories/${id}/poll`, {
        method: 'POST',
    });
}

// ==================== Drift Detection ====================

export async function checkGitDrift(id) {
    return gitApi(`/repositories/${id}/check-drift`, {
        method: 'POST',
    });
}

export async function getAllDriftStatus() {
    return gitApi('/drift');
}

// ==================== Jobs ====================

export async function listJobs() {
    return gitApi('/jobs');
}

export async function getJob(id) {
    return gitApi(`/jobs/${id}`);
}

export async function getJobLogs(id) {
    return gitApi(`/jobs/${id}/logs`);
}

export async function cancelJob(id) {
    return gitApi(`/jobs/${id}/cancel`, {
        method: 'POST',
    });
}

// Stream job logs using Server-Sent Events
export function streamJobLogs(jobId, callbacks) {
    const { onLog, onComplete, onError } = callbacks;

    const eventSource = new EventSource(
        `${GIT_API}/jobs/${jobId}/logs/stream`,
        { withCredentials: false }
    );

    // Need to add auth header - SSE doesn't support headers natively
    // We'll use the token in the URL or rely on cookies
    // For now, this endpoint should be adjusted to not require auth
    // or use a different mechanism

    eventSource.addEventListener('log', (event) => {
        if (onLog) onLog(event.data);
    });

    eventSource.addEventListener('complete', (event) => {
        if (onComplete) onComplete();
        eventSource.close();
    });

    eventSource.onerror = (error) => {
        if (onError) onError(error);
        eventSource.close();
    };

    return eventSource;
}

// ==================== Repository by Host ====================

export async function listRepositoriesByHost(hostId) {
    return gitApi(`/hosts/${hostId}/repositories`);
}
