// Templates API
import { apiCall, TEMPLATES_API } from './client.js';

function normalizeKeys(obj) {
    if (Array.isArray(obj)) {
        return obj.map(normalizeKeys);
    }
    if (obj !== null && typeof obj === 'object') {
        return Object.fromEntries(
            Object.entries(obj).map(([key, value]) => [
                key.charAt(0).toLowerCase() + key.slice(1),
                normalizeKeys(value),
            ])
        );
    }
    return obj;
}

export async function listTemplates(options = {}) {
    const params = new URLSearchParams();
    if (options.category) params.append('category', options.category);
    if (options.type) params.append('type', options.type);
    if (options.search) params.append('search', options.search);
    if (options.systemOnly) params.append('systemOnly', 'true');
    if (options.userOnly) params.append('userOnly', 'true');

    const queryString = params.toString();
    const url = queryString ? `${TEMPLATES_API}?${queryString}` : TEMPLATES_API;

    const data = await apiCall(url);
    return normalizeKeys(data);
}

export async function getTemplate(id) {
    const data = await apiCall(`${TEMPLATES_API}/${id}`);
    return normalizeKeys(data);
}

export async function getCategories() {
    return apiCall(`${TEMPLATES_API}/categories`);
}

export async function getTemplateStats() {
    return apiCall(`${TEMPLATES_API}/stats`);
}

export async function createTemplate(template) {
    const data = await apiCall(TEMPLATES_API, {
        method: 'POST',
        body: JSON.stringify(template),
    });
    return normalizeKeys(data);
}

export async function updateTemplate(id, template) {
    const data = await apiCall(`${TEMPLATES_API}/${id}`, {
        method: 'PUT',
        body: JSON.stringify(template),
    });
    return normalizeKeys(data);
}

export async function deleteTemplate(id) {
    return apiCall(`${TEMPLATES_API}/${id}`, {
        method: 'DELETE',
    });
}

export async function duplicateTemplate(id, name) {
    const params = name ? `?name=${encodeURIComponent(name)}` : '';
    const data = await apiCall(`${TEMPLATES_API}/${id}/duplicate${params}`, {
        method: 'POST',
    });
    return normalizeKeys(data);
}

export async function reseedTemplates() {
    return apiCall(`${TEMPLATES_API}/reseed`, {
        method: 'POST',
    });
}
