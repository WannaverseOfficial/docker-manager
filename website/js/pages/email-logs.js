// Email Logs Page (Admin Only)
import { isAdmin } from '../state.js';
import { getEmailLogs, getEmailLog, getEmailStats, getEmailStatuses, getAvailableEvents } from '../api/notifications.js';
import { showToast } from '../components/toast.js';
import { openModal } from '../components/modal.js';
import { renderTable, setupTableActions } from '../components/data-table.js';

let logs = [];
let pagination = { page: 0, size: 50, totalPages: 0, totalElements: 0 };
let filters = { status: '', eventType: '' };
let stats = null;
let statuses = [];
let eventTypes = [];

// Render page
export function render() {
    if (!isAdmin()) {
        return `
            <div class="empty-state">
                <span class="material-symbols-outlined">lock</span>
                <h3>Access Denied</h3>
                <p>You need admin privileges to access this page.</p>
            </div>
        `;
    }

    return `
        <div class="section-header">
            <h2 class="section-title">Email Logs</h2>
            <md-filled-button id="refresh-logs-btn">
                <span class="material-symbols-outlined" slot="icon">refresh</span>
                Refresh
            </md-filled-button>
        </div>

        <div class="stats-grid" id="stats-cards">
            <div class="stat-card">
                <div class="stat-icon sent">
                    <span class="material-symbols-outlined">mark_email_read</span>
                </div>
                <div class="stat-content">
                    <span class="stat-value" id="stat-sent-24h">-</span>
                    <span class="stat-label">Sent (24h)</span>
                </div>
            </div>
            <div class="stat-card">
                <div class="stat-icon failed">
                    <span class="material-symbols-outlined">mark_email_unread</span>
                </div>
                <div class="stat-content">
                    <span class="stat-value" id="stat-failed-24h">-</span>
                    <span class="stat-label">Failed (24h)</span>
                </div>
            </div>
            <div class="stat-card">
                <div class="stat-icon pending">
                    <span class="material-symbols-outlined">schedule_send</span>
                </div>
                <div class="stat-content">
                    <span class="stat-value" id="stat-pending">-</span>
                    <span class="stat-label">Pending</span>
                </div>
            </div>
            <div class="stat-card">
                <div class="stat-icon total">
                    <span class="material-symbols-outlined">mail</span>
                </div>
                <div class="stat-content">
                    <span class="stat-value" id="stat-total">-</span>
                    <span class="stat-label">Total Emails</span>
                </div>
            </div>
        </div>

        <div class="card" style="margin-bottom: 16px;">
            <div class="card-content">
                <div class="filter-grid">
                    <md-filled-select id="filter-status" label="Status">
                        <md-select-option value="" selected>All Statuses</md-select-option>
                    </md-filled-select>
                    <md-filled-select id="filter-event" label="Event Type">
                        <md-select-option value="" selected>All Events</md-select-option>
                    </md-filled-select>
                    <md-filled-button id="apply-filters-btn">Apply</md-filled-button>
                    <md-text-button id="clear-filters-btn">Clear</md-text-button>
                </div>
            </div>
        </div>

        <div class="card">
            <div class="card-content" id="logs-table">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>

        <div class="pagination" id="pagination-controls">
            <md-text-button id="prev-page" disabled>
                <span class="material-symbols-outlined" slot="icon">chevron_left</span>
                Previous
            </md-text-button>
            <span id="page-info">Page 1 of 1</span>
            <md-text-button id="next-page" disabled>
                Next
                <span class="material-symbols-outlined" slot="icon">chevron_right</span>
            </md-text-button>
        </div>
    `;
}

// Initialize
export async function init() {
    if (!isAdmin()) return;

    document.getElementById('refresh-logs-btn')?.addEventListener('click', loadData);
    document.getElementById('apply-filters-btn')?.addEventListener('click', applyFilters);
    document.getElementById('clear-filters-btn')?.addEventListener('click', clearFilters);
    document.getElementById('prev-page')?.addEventListener('click', prevPage);
    document.getElementById('next-page')?.addEventListener('click', nextPage);

    setupTableActions('logs-table', {
        view: showLogDetails,
    });

    await loadFilterOptions();
    await loadData();
}

// Load filter dropdown options
async function loadFilterOptions() {
    try {
        [statuses, eventTypes] = await Promise.all([
            getEmailStatuses().catch(() => ['PENDING', 'SENT', 'FAILED']),
            getAvailableEvents().catch(() => []),
        ]);

        // Populate status dropdown
        const statusSelect = document.getElementById('filter-status');
        if (statusSelect) {
            statuses.forEach(s => {
                const option = document.createElement('md-select-option');
                option.value = s;
                option.textContent = formatStatus(s);
                statusSelect.appendChild(option);
            });
        }

        // Populate event type dropdown
        const eventSelect = document.getElementById('filter-event');
        if (eventSelect) {
            eventTypes.forEach(e => {
                const option = document.createElement('md-select-option');
                option.value = e.name;
                option.textContent = e.displayName;
                eventSelect.appendChild(option);
            });
        }
    } catch (error) {
        console.error('Failed to load filter options:', error);
    }
}

// Load email logs and stats
async function loadData() {
    try {
        const params = {
            page: pagination.page,
            size: pagination.size,
        };

        if (filters.status) params.status = filters.status;
        if (filters.eventType) params.eventType = filters.eventType;

        const [logsResult, statsResult] = await Promise.all([
            getEmailLogs(params),
            getEmailStats(),
        ]);

        logs = logsResult.content || [];
        pagination.totalElements = logsResult.totalElements || 0;
        pagination.totalPages = logsResult.totalPages || 1;
        stats = statsResult;

        renderStats();
        renderLogsTable();
        updatePagination();

    } catch (error) {
        console.error('Failed to load email logs:', error);
        showToast('Failed to load email logs', 'error');
    }
}

// Render statistics cards
function renderStats() {
    if (!stats) return;

    document.getElementById('stat-sent-24h').textContent = stats.last24Hours?.sent ?? 0;
    document.getElementById('stat-failed-24h').textContent = stats.last24Hours?.failed ?? 0;
    document.getElementById('stat-pending').textContent = stats.last24Hours?.pending ?? 0;
    document.getElementById('stat-total').textContent = stats.total ?? 0;
}

// Render logs table
function renderLogsTable() {
    const container = document.getElementById('logs-table');

    const columns = [
        {
            key: 'createdAt',
            label: 'Time',
            render: (ts) => formatTimestamp(ts)
        },
        { key: 'recipientEmail', label: 'Recipient' },
        { key: 'subject', label: 'Subject', render: (s) => truncate(s, 40) },
        {
            key: 'eventType',
            label: 'Event',
            render: (type) => formatEventType(type)
        },
        {
            key: 'status',
            label: 'Status',
            render: (status) => renderStatusBadge(status)
        },
    ];

    const actionsCol = (log) => `
        <md-icon-button data-action="view" data-id="${log.id}" title="View Details">
            <span class="material-symbols-outlined">visibility</span>
        </md-icon-button>
    `;

    container.innerHTML = renderTable({
        columns,
        data: logs,
        actions: actionsCol,
        emptyMessage: 'No email logs found',
        emptyIcon: 'mail',
    });
}

// Render status badge
function renderStatusBadge(status) {
    const statusClasses = {
        'SENT': 'running',
        'FAILED': 'stopped',
        'PENDING': 'paused',
    };
    const cls = statusClasses[status] || 'unknown';
    return `<span class="status-badge ${cls}">${formatStatus(status)}</span>`;
}

// Format status for display
function formatStatus(status) {
    return status.charAt(0) + status.slice(1).toLowerCase();
}

// Format event type for display
function formatEventType(type) {
    const event = eventTypes.find(e => e.name === type);
    return event ? event.displayName : type.replace(/_/g, ' ').toLowerCase();
}

// Format timestamp
function formatTimestamp(epochMillis) {
    const date = new Date(epochMillis);
    return date.toLocaleString('en-US', {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
    });
}

// Truncate string
function truncate(str, len) {
    if (!str) return '';
    return str.length > len ? str.substring(0, len) + '...' : str;
}

// Update pagination controls
function updatePagination() {
    const pageInfo = document.getElementById('page-info');
    const prevBtn = document.getElementById('prev-page');
    const nextBtn = document.getElementById('next-page');

    const currentPage = pagination.page + 1;
    const totalPages = Math.max(1, pagination.totalPages);

    pageInfo.textContent = `Page ${currentPage} of ${totalPages} (${pagination.totalElements} total)`;

    prevBtn.disabled = pagination.page === 0;
    nextBtn.disabled = pagination.page >= pagination.totalPages - 1;
}

// Pagination handlers
function prevPage() {
    if (pagination.page > 0) {
        pagination.page--;
        loadData();
    }
}

function nextPage() {
    if (pagination.page < pagination.totalPages - 1) {
        pagination.page++;
        loadData();
    }
}

// Apply filters
function applyFilters() {
    filters.status = document.getElementById('filter-status')?.value || '';
    filters.eventType = document.getElementById('filter-event')?.value || '';
    pagination.page = 0;
    loadData();
}

// Clear filters
function clearFilters() {
    document.getElementById('filter-status').value = '';
    document.getElementById('filter-event').value = '';
    filters = { status: '', eventType: '' };
    pagination.page = 0;
    loadData();
}

// Show log details modal
async function showLogDetails(id) {
    try {
        const log = await getEmailLog(id);

        const content = `
            <div class="detail-grid">
                <div class="detail-row">
                    <span class="detail-label">Created</span>
                    <span class="detail-value">${new Date(log.createdAt).toLocaleString()}</span>
                </div>
                ${log.sentAt ? `
                <div class="detail-row">
                    <span class="detail-label">Sent At</span>
                    <span class="detail-value">${new Date(log.sentAt).toLocaleString()}</span>
                </div>
                ` : ''}
                <div class="detail-row">
                    <span class="detail-label">Recipient</span>
                    <span class="detail-value">${log.recipientEmail}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Subject</span>
                    <span class="detail-value">${log.subject}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Event Type</span>
                    <span class="detail-value">${formatEventType(log.eventType)}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Status</span>
                    <span class="detail-value">${renderStatusBadge(log.status)}</span>
                </div>
                ${log.recipientUserId ? `
                <div class="detail-row">
                    <span class="detail-label">User ID</span>
                    <span class="detail-value"><code>${log.recipientUserId}</code></span>
                </div>
                ` : ''}
                ${log.relatedResourceId ? `
                <div class="detail-row">
                    <span class="detail-label">Resource ID</span>
                    <span class="detail-value"><code>${log.relatedResourceId}</code></span>
                </div>
                ` : ''}
                ${log.errorMessage ? `
                <div class="detail-row">
                    <span class="detail-label">Error</span>
                    <span class="detail-value" style="color: var(--status-stopped);">${log.errorMessage}</span>
                </div>
                ` : ''}
            </div>
        `;

        openModal('Email Log Details', content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `);

    } catch (error) {
        showToast('Failed to load log details', 'error');
    }
}

export function cleanup() {
    logs = [];
    stats = null;
    statuses = [];
    eventTypes = [];
    pagination = { page: 0, size: 50, totalPages: 0, totalElements: 0 };
    filters = { status: '', eventType: '' };
}
