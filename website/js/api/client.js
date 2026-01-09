// Base API Client with Authentication
import { state, setAuthTokens, clearState } from '../state.js';
import { showToast } from '../components/toast.js';

// API Base URLs
const API_BASE = 'http://localhost:8080/api';
export const DOCKER_API = `${API_BASE}/docker`;
export const AUTH_API = `${API_BASE}/auth`;
export const GIT_API = `${API_BASE}/git`;
export const USERS_API = `${API_BASE}/users`;
export const GROUPS_API = `${API_BASE}/groups`;
export const AUDIT_API = `${API_BASE}/audit`;
export const SMTP_API = `${API_BASE}/admin/smtp`;
export const EMAIL_LOGS_API = `${API_BASE}/admin/email-logs`;
export const NOTIFICATIONS_API = `${API_BASE}/notifications`;
export const DEPLOYMENTS_API = `${API_BASE}/deployments`;
export const ROLLBACK_API = `${API_BASE}/rollback`;
export const DIFF_API = `${API_BASE}/diff`;
export const INGRESS_API = `${API_BASE}/ingress`;
export const TEMPLATES_API = `${API_BASE}/templates`;

// Base fetch wrapper with auth handling
export async function apiCall(url, options = {}) {
    const headers = {
        'Content-Type': 'application/json',
        ...options.headers,
    };

    // Add auth header if we have a token
    const token = state?.accessToken;
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    try {
        let response = await fetch(url, {
            ...options,
            headers,
        });

        // Handle 401 - try to refresh token
        if (response.status === 401 && state?.refreshToken) {
            const refreshed = await refreshAccessToken();
            if (refreshed) {
                // Retry with new token
                headers['Authorization'] = `Bearer ${state?.accessToken}`;
                response = await fetch(url, { ...options, headers });
            } else {
                // Refresh failed, redirect to login
                handleAuthFailure();
                throw new Error('Session expired. Please log in again.');
            }
        }

        // Handle 403 - permission denied
        if (response.status === 403) {
            const error = await response.json().catch(() => ({}));
            throw new Error(error.error || 'Permission denied');
        }

        // Handle 204 No Content
        if (response.status === 204) {
            return null;
        }

        // Handle error responses
        if (!response.ok) {
            const error = await response.json().catch(() => ({}));
            throw new Error(error.error || error.message || `Request failed: ${response.status}`);
        }

        // Check if response has content before parsing JSON
        const contentLength = response.headers.get('content-length');
        const contentType = response.headers.get('content-type');

        // Return null for empty responses
        if (contentLength === '0' || (!contentType?.includes('application/json'))) {
            return null;
        }

        // Parse JSON response (with fallback for empty body)
        const text = await response.text();
        if (!text || text.trim() === '') {
            return null;
        }
        return JSON.parse(text);

    } catch (error) {
        if (error.name === 'TypeError' && error.message.includes('fetch')) {
            throw new Error('Network error. Please check your connection.');
        }
        throw error;
    }
}

// Refresh access token
async function refreshAccessToken() {
    try {
        const refreshToken = state?.refreshToken;
        if (!refreshToken) {
            return false;
        }

        const response = await fetch(`${AUTH_API}/refresh`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                refreshToken,
            }),
        });

        if (!response.ok) {
            return false;
        }

        const data = await response.json();
        setAuthTokens(data.accessToken, data.refreshToken);
        return true;

    } catch (error) {
        console.error('Token refresh failed:', error);
        return false;
    }
}

// Handle auth failure
function handleAuthFailure() {
    clearState();
    window.location.reload();
}

// Docker API shorthand
export async function dockerApi(endpoint, options = {}) {
    return apiCall(`${DOCKER_API}${endpoint}`, options);
}

// Git API shorthand
export async function gitApi(endpoint, options = {}) {
    return apiCall(`${GIT_API}${endpoint}`, options);
}

// Auth API shorthand (no auth header needed for some endpoints)
export async function authApi(endpoint, options = {}) {
    const headers = {
        'Content-Type': 'application/json',
        ...options.headers,
    };

    // Only add auth header if we have a token and it's not login/refresh
    const token = state?.accessToken;
    if (token && !endpoint.includes('/login') && !endpoint.includes('/refresh')) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    const response = await fetch(`${AUTH_API}${endpoint}`, {
        ...options,
        headers,
    });

    if (response.status === 204) {
        return null;
    }

    const data = await response.json().catch(() => null);

    if (!response.ok) {
        throw new Error(data?.error || data?.message || `Request failed: ${response.status}`);
    }

    return data;
}

// Users API shorthand
export async function usersApi(endpoint, options = {}) {
    return apiCall(`${USERS_API}${endpoint}`, options);
}

// Groups API shorthand
export async function groupsApi(endpoint, options = {}) {
    return apiCall(`${GROUPS_API}${endpoint}`, options);
}

// Audit API shorthand
export async function auditApi(endpoint, options = {}) {
    return apiCall(`${AUDIT_API}${endpoint}`, options);
}

// SMTP API shorthand (admin only)
export async function smtpApi(endpoint, options = {}) {
    return apiCall(`${SMTP_API}${endpoint}`, options);
}

// Email Logs API shorthand (admin only)
export async function emailLogsApi(endpoint, options = {}) {
    return apiCall(`${EMAIL_LOGS_API}${endpoint}`, options);
}

// Notifications API shorthand
export async function notificationsApi(endpoint, options = {}) {
    return apiCall(`${NOTIFICATIONS_API}${endpoint}`, options);
}

// Deployments API shorthand
export async function deploymentsApi(endpoint, options = {}) {
    return apiCall(`${DEPLOYMENTS_API}${endpoint}`, options);
}

// Rollback API shorthand
export async function rollbackApi(endpoint, options = {}) {
    return apiCall(`${ROLLBACK_API}${endpoint}`, options);
}

// Diff API shorthand
export async function diffApi(endpoint, options = {}) {
    return apiCall(`${DIFF_API}${endpoint}`, options);
}

// Ingress API shorthand
export async function ingressApi(endpoint, options = {}) {
    return apiCall(`${INGRESS_API}${endpoint}`, options);
}
