// Notifications API Module
import { smtpApi, emailLogsApi, notificationsApi } from './client.js';

// ==================== SMTP Configuration (Admin Only) ====================

/**
 * Get current SMTP configuration
 * @returns {Promise<Object|null>} SMTP configuration or null if not configured
 */
export async function getSmtpConfig() {
    return smtpApi('');
}

/**
 * Update SMTP configuration
 * @param {Object} config - SMTP configuration
 * @param {string} config.host - SMTP server host
 * @param {number} config.port - SMTP server port
 * @param {string} [config.username] - SMTP username
 * @param {string} [config.password] - SMTP password (only sent when changing)
 * @param {string} config.fromAddress - From email address
 * @param {string} [config.fromName] - From display name
 * @param {string} config.securityType - Security type (NONE, STARTTLS, SSL_TLS)
 * @param {boolean} config.enabled - Whether SMTP is enabled
 */
export async function updateSmtpConfig(config) {
    return smtpApi('', {
        method: 'PUT',
        body: JSON.stringify(config),
    });
}

/**
 * Delete SMTP configuration
 */
export async function deleteSmtpConfig() {
    return smtpApi('', {
        method: 'DELETE',
    });
}

/**
 * Send a test email
 * @param {string} recipientEmail - Email address to send test to
 */
export async function sendTestEmail(recipientEmail) {
    return smtpApi('/test', {
        method: 'POST',
        body: JSON.stringify({ recipientEmail }),
    });
}

/**
 * Check if SMTP is configured and enabled
 * @returns {Promise<{configured: boolean}>}
 */
export async function getSmtpStatus() {
    return smtpApi('/status');
}

// ==================== Notification Preferences (All Users) ====================

/**
 * Get current user's notification preferences
 * @returns {Promise<{userId: string, preferences: Object}>}
 */
export async function getNotificationPreferences() {
    return notificationsApi('/preferences');
}

/**
 * Update notification preferences
 * @param {Object} preferences - Map of event type to enabled status
 * @example
 * updateNotificationPreferences({
 *   'DEPLOYMENT_STARTED': true,
 *   'DEPLOYMENT_FAILED': true,
 *   'USER_LOGIN': false
 * })
 */
export async function updateNotificationPreferences(preferences) {
    return notificationsApi('/preferences', {
        method: 'PUT',
        body: JSON.stringify(preferences),
    });
}

/**
 * Get all available notification event types
 * @returns {Promise<Array<{name: string, displayName: string, category: string}>>}
 */
export async function getAvailableEvents() {
    return notificationsApi('/available-events');
}

/**
 * Get all notification categories
 * @returns {Promise<string[]>}
 */
export async function getNotificationCategories() {
    return notificationsApi('/categories');
}

// ==================== Email Logs (Admin Only) ====================

/**
 * Get paginated email logs with optional filters
 * @param {Object} params - Query parameters
 * @param {number} [params.page=0] - Page number (0-indexed)
 * @param {number} [params.size=50] - Page size
 * @param {string} [params.status] - Filter by status (PENDING, SENT, FAILED)
 * @param {string} [params.eventType] - Filter by event type
 */
export async function getEmailLogs(params = {}) {
    const queryParams = new URLSearchParams();

    if (params.page !== undefined) queryParams.set('page', params.page);
    if (params.size !== undefined) queryParams.set('size', params.size);
    if (params.status) queryParams.set('status', params.status);
    if (params.eventType) queryParams.set('eventType', params.eventType);

    const queryString = queryParams.toString();
    return emailLogsApi(queryString ? `?${queryString}` : '');
}

/**
 * Get a single email log entry by ID
 * @param {string} id - Email log ID
 */
export async function getEmailLog(id) {
    return emailLogsApi(`/${id}`);
}

/**
 * Get email statistics
 * @returns {Promise<{last24Hours: {sent, failed, pending}, last7Days: {sent, failed}, total: number}>}
 */
export async function getEmailStats() {
    return emailLogsApi('/stats');
}

/**
 * Get all email statuses for filter dropdown
 * @returns {Promise<string[]>}
 */
export async function getEmailStatuses() {
    return emailLogsApi('/statuses');
}
