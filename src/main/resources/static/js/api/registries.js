// Docker Registries API Module
import { apiCall } from './client.js';

const REGISTRIES_API = '/api/registries';

// Get all registries
export async function listRegistries() {
    return apiCall(REGISTRIES_API);
}

// Get a specific registry
export async function getRegistry(id) {
    return apiCall(`${REGISTRIES_API}/${id}`);
}

// Create a new registry
export async function createRegistry(registry) {
    return apiCall(REGISTRIES_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(registry),
    });
}

// Update a registry
export async function updateRegistry(id, registry) {
    return apiCall(`${REGISTRIES_API}/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(registry),
    });
}

// Delete a registry
export async function deleteRegistry(id) {
    return apiCall(`${REGISTRIES_API}/${id}`, {
        method: 'DELETE',
    });
}

// Test registry connection
export async function testRegistry(id) {
    return apiCall(`${REGISTRIES_API}/${id}/test`, {
        method: 'POST',
    });
}

// Set registry as default
export async function setDefaultRegistry(id) {
    return apiCall(`${REGISTRIES_API}/${id}/set-default`, {
        method: 'POST',
    });
}
