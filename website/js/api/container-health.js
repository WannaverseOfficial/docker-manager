// Container Health API Module
import { apiCall } from './client.js';

const CONTAINER_HEALTH_API = 'http://localhost:8080/api/container-health';

// Get all active issues across all hosts
export async function getActiveIssues() {
    return apiCall(`${CONTAINER_HEALTH_API}/active`);
}

// Get count of active issues
export async function getActiveIssueCount() {
    return apiCall(`${CONTAINER_HEALTH_API}/active/count`);
}

// Get events for a specific host (paginated)
export async function getHostEvents(hostId, options = {}) {
    const { type = null, page = 0, size = 20 } = options;
    let url = `${CONTAINER_HEALTH_API}/hosts/${hostId}/events?page=${page}&size=${size}`;
    if (type) {
        url += `&type=${type}`;
    }
    return apiCall(url);
}

// Get active issues for a specific host
export async function getHostActiveIssues(hostId) {
    return apiCall(`${CONTAINER_HEALTH_API}/hosts/${hostId}/active`);
}

// Get a specific event by ID
export async function getEvent(eventId) {
    return apiCall(`${CONTAINER_HEALTH_API}/events/${eventId}`);
}

// Resolve/acknowledge an event
export async function resolveEvent(eventId) {
    return apiCall(`${CONTAINER_HEALTH_API}/events/${eventId}/resolve`, {
        method: 'POST',
    });
}

// Get summary statistics for a host
export async function getHostSummary(hostId) {
    return apiCall(`${CONTAINER_HEALTH_API}/hosts/${hostId}/summary`);
}

// Get health event history for a specific container
export async function getContainerHistory(hostId, containerId) {
    return apiCall(`${CONTAINER_HEALTH_API}/hosts/${hostId}/containers/${containerId}/history`);
}
