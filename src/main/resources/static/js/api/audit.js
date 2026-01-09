// Audit Logs API Module
import { auditApi } from './client.js';

/**
 * Get paginated audit logs with optional filters
 * @param {Object} params - Query parameters
 * @param {number} [params.page=0] - Page number (0-indexed)
 * @param {number} [params.size=50] - Page size
 * @param {string} [params.userId] - Filter by user ID
 * @param {string} [params.action] - Filter by action
 * @param {string} [params.resourceType] - Filter by resource type
 * @param {string} [params.startDate] - Filter by start date (ISO format)
 * @param {string} [params.endDate] - Filter by end date (ISO format)
 * @param {string} [params.search] - Search in details
 */
export async function getAuditLogs(params = {}) {
    const queryParams = new URLSearchParams();

    if (params.page !== undefined) queryParams.set('page', params.page);
    if (params.size !== undefined) queryParams.set('size', params.size);
    if (params.userId) queryParams.set('userId', params.userId);
    if (params.action) queryParams.set('action', params.action);
    if (params.resourceType) queryParams.set('resourceType', params.resourceType);
    if (params.startDate) queryParams.set('startDate', params.startDate);
    if (params.endDate) queryParams.set('endDate', params.endDate);
    if (params.search) queryParams.set('search', params.search);

    const queryString = queryParams.toString();
    return auditApi(queryString ? `?${queryString}` : '');
}

/**
 * Get a single audit log entry by ID
 * @param {string} id - Audit log ID
 */
export async function getAuditLog(id) {
    return auditApi(`/${id}`);
}

/**
 * Get distinct action types for filter dropdown
 */
export async function getDistinctActions() {
    return auditApi('/actions');
}

/**
 * Get all resource types for filter dropdown
 */
export async function getResourceTypes() {
    return auditApi('/resource-types');
}
