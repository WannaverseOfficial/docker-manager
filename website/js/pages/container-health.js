// Container Health Monitoring Page
import { state } from '../state.js';
import {
    getActiveIssues,
    getHostEvents,
    getHostSummary,
    resolveEvent,
    getEvent
} from '../api/container-health.js';
import { showToast } from '../components/toast.js';
import { openModal } from '../components/modal.js';
import { renderTable, setupTableActions } from '../components/data-table.js';
import { showConfirm } from '../components/confirm-dialog.js';

let issues = [];
let events = [];
let summary = null;
let pagination = { page: 0, size: 20, totalPages: 0, totalElements: 0 };
let filters = { type: '' };
let refreshInterval = null;

// Event type labels and colors
const eventTypeConfig = {
    CRASH: { label: 'Crash', class: 'stopped', icon: 'dangerous' },
    RESTART_LOOP: { label: 'Restart Loop', class: 'warning', icon: 'autorenew' },
    HEALTH_UNHEALTHY: { label: 'Unhealthy', class: 'warning', icon: 'health_and_safety' },
    OOM_KILLED: { label: 'OOM Killed', class: 'stopped', icon: 'memory' },
};

// Render page
export function render() {
    return `
        <div class="section-header">
            <h2 class="section-title">Container Health</h2>
            <md-filled-button id="refresh-health-btn">
                <span class="material-symbols-outlined" slot="icon">refresh</span>
                Refresh
            </md-filled-button>
        </div>

        <!-- Summary Cards -->
        <div class="stats-grid" id="health-summary">
            <div class="stat-card">
                <div class="stat-icon" style="background: color-mix(in srgb, var(--status-stopped) 20%, transparent);">
                    <span class="material-symbols-outlined" style="color: var(--status-stopped);">error</span>
                </div>
                <div class="stat-content">
                    <div class="stat-value" id="active-issues-count">-</div>
                    <div class="stat-label">Active Issues</div>
                </div>
            </div>
            <div class="stat-card">
                <div class="stat-icon">
                    <span class="material-symbols-outlined">schedule</span>
                </div>
                <div class="stat-content">
                    <div class="stat-value" id="events-24h-count">-</div>
                    <div class="stat-label">Events (24h)</div>
                </div>
            </div>
            <div class="stat-card">
                <div class="stat-icon">
                    <span class="material-symbols-outlined">calendar_month</span>
                </div>
                <div class="stat-content">
                    <div class="stat-value" id="events-7d-count">-</div>
                    <div class="stat-label">Events (7d)</div>
                </div>
            </div>
        </div>

        <!-- Active Issues -->
        <div class="card" style="margin-bottom: 16px;">
            <div class="card-header">
                <h3>Active Issues</h3>
            </div>
            <div class="card-content" id="active-issues-table">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>

        <!-- Event History -->
        <div class="card">
            <div class="card-header">
                <h3>Event History</h3>
                <md-filled-select id="filter-type" label="Event Type" style="width: 200px;">
                    <md-select-option value="" selected>All Types</md-select-option>
                    <md-select-option value="CRASH">Crash</md-select-option>
                    <md-select-option value="RESTART_LOOP">Restart Loop</md-select-option>
                    <md-select-option value="HEALTH_UNHEALTHY">Unhealthy</md-select-option>
                    <md-select-option value="OOM_KILLED">OOM Killed</md-select-option>
                </md-filled-select>
            </div>
            <div class="card-content" id="events-table">
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
    document.getElementById('refresh-health-btn')?.addEventListener('click', loadAll);
    document.getElementById('filter-type')?.addEventListener('change', onFilterChange);
    document.getElementById('prev-page')?.addEventListener('click', prevPage);
    document.getElementById('next-page')?.addEventListener('click', nextPage);

    setupTableActions('active-issues-table', {
        resolve: handleResolve,
        view: showEventDetails,
    });

    setupTableActions('events-table', {
        resolve: handleResolve,
        view: showEventDetails,
    });

    await loadAll();

    // Auto-refresh every 30 seconds
    refreshInterval = setInterval(loadAll, 30000);
}

// Load all data
async function loadAll() {
    await Promise.all([
        loadSummary(),
        loadActiveIssues(),
        loadEvents(),
    ]);
}

// Load summary stats
async function loadSummary() {
    try {
        const hostId = state.currentHostId;
        if (!hostId) {
            document.getElementById('active-issues-count').textContent = '-';
            document.getElementById('events-24h-count').textContent = '-';
            document.getElementById('events-7d-count').textContent = '-';
            return;
        }

        summary = await getHostSummary(hostId);

        document.getElementById('active-issues-count').textContent = summary.activeIssues || 0;
        document.getElementById('events-24h-count').textContent = summary.totalEvents24h || 0;
        document.getElementById('events-7d-count').textContent = summary.totalEvents7d || 0;
    } catch (error) {
        console.error('Failed to load summary:', error);
    }
}

// Load active issues
async function loadActiveIssues() {
    try {
        issues = await getActiveIssues();
        renderActiveIssuesTable();
    } catch (error) {
        console.error('Failed to load active issues:', error);
        issues = [];
        renderActiveIssuesTable();
        showToast('Failed to load active issues', 'error');
    }
}

// Load event history
async function loadEvents() {
    try {
        const hostId = state.currentHostId;
        if (!hostId) {
            events = [];
            renderEventsTable();
            updatePagination();
            return;
        }

        const result = await getHostEvents(hostId, {
            type: filters.type || null,
            page: pagination.page,
            size: pagination.size,
        });

        events = result.content || [];
        pagination.totalElements = result.totalElements || 0;
        pagination.totalPages = result.totalPages || 1;

        renderEventsTable();
        updatePagination();
    } catch (error) {
        console.error('Failed to load events:', error);
        events = [];
        renderEventsTable();
        showToast('Failed to load event history', 'error');
    }
}

// Render active issues table
function renderActiveIssuesTable() {
    const container = document.getElementById('active-issues-table');

    const columns = [
        {
            key: 'eventType',
            label: 'Type',
            render: (type) => renderEventTypeBadge(type),
        },
        { key: 'containerName', label: 'Container' },
        {
            key: 'containerId',
            label: 'ID',
            render: (id) => `<code>${truncate(id, 12)}</code>`,
        },
        {
            key: 'exitCode',
            label: 'Exit Code',
            render: (code) => code !== null ? `<code>${code}</code>` : '-',
        },
        {
            key: 'restartCount',
            label: 'Restarts',
            render: (count) => count !== null ? count : '-',
        },
        {
            key: 'detectedAt',
            label: 'Detected',
            render: (ts) => formatTimestamp(ts),
        },
    ];

    const actionsCol = (issue) => `
        <md-icon-button class="action-btn" data-action="view" title="View Details">
            <span class="material-symbols-outlined">visibility</span>
        </md-icon-button>
        <md-icon-button class="action-btn" data-action="resolve" title="Resolve">
            <span class="material-symbols-outlined">check_circle</span>
        </md-icon-button>
    `;

    container.innerHTML = renderTable({
        columns,
        data: issues,
        actions: actionsCol,
        emptyMessage: 'No active issues',
        emptyIcon: 'check_circle',
    });
}

// Render events table
function renderEventsTable() {
    const container = document.getElementById('events-table');

    const columns = [
        {
            key: 'eventType',
            label: 'Type',
            render: (type) => renderEventTypeBadge(type),
        },
        { key: 'containerName', label: 'Container' },
        {
            key: 'exitCode',
            label: 'Exit Code',
            render: (code) => code !== null ? `<code>${code}</code>` : '-',
        },
        {
            key: 'restartCount',
            label: 'Restarts',
            render: (count) => count !== null ? count : '-',
        },
        {
            key: 'detectedAt',
            label: 'Detected',
            render: (ts) => formatTimestamp(ts),
        },
        {
            key: 'resolvedAt',
            label: 'Resolved',
            render: (ts) => ts ? formatTimestamp(ts) : renderStatusBadge(true),
        },
    ];

    const actionsCol = (event) => `
        <md-icon-button class="action-btn" data-action="view" title="View Details">
            <span class="material-symbols-outlined">visibility</span>
        </md-icon-button>
        ${event.active ? `
        <md-icon-button class="action-btn" data-action="resolve" title="Resolve">
            <span class="material-symbols-outlined">check_circle</span>
        </md-icon-button>
        ` : ''}
    `;

    container.innerHTML = renderTable({
        columns,
        data: events,
        actions: actionsCol,
        emptyMessage: 'No events found',
        emptyIcon: 'event',
    });
}

// Render event type badge
function renderEventTypeBadge(type) {
    const config = eventTypeConfig[type] || { label: type, class: 'default', icon: 'info' };
    return `
        <span class="status-badge ${config.class}">
            <span class="material-symbols-outlined" style="font-size: 14px; margin-right: 4px;">${config.icon}</span>
            ${config.label}
        </span>
    `;
}

// Render active status badge
function renderStatusBadge(active) {
    if (active) {
        return '<span class="status-badge warning">Active</span>';
    }
    return '<span class="status-badge running">Resolved</span>';
}

// Format timestamp
function formatTimestamp(epochMillis) {
    if (!epochMillis) return '-';
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

// Filter change handler
function onFilterChange() {
    filters.type = document.getElementById('filter-type')?.value || '';
    pagination.page = 0;
    loadEvents();
}

// Pagination handlers
function prevPage() {
    if (pagination.page > 0) {
        pagination.page--;
        loadEvents();
    }
}

function nextPage() {
    if (pagination.page < pagination.totalPages - 1) {
        pagination.page++;
        loadEvents();
    }
}

// Handle resolve action
async function handleResolve(id) {
    const confirmed = await showConfirm({
        title: 'Resolve Issue',
        message: 'Are you sure you want to mark this issue as resolved?',
        confirmText: 'Resolve',
        type: 'info',
    });

    if (!confirmed) return;

    try {
        await resolveEvent(id);
        showToast('Issue resolved', 'success');
        await loadAll();
    } catch (error) {
        showToast('Failed to resolve issue: ' + error.message, 'error');
    }
}

// Show event details modal
async function showEventDetails(id) {
    try {
        const event = await getEvent(id);

        const content = `
            <div class="detail-grid">
                <div class="detail-row">
                    <span class="detail-label">Event Type</span>
                    <span class="detail-value">${renderEventTypeBadge(event.eventType)}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Container</span>
                    <span class="detail-value">${event.containerName || '-'}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Container ID</span>
                    <span class="detail-value"><code>${event.containerId || '-'}</code></span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Host ID</span>
                    <span class="detail-value"><code>${event.hostId || '-'}</code></span>
                </div>
                ${event.exitCode !== null ? `
                <div class="detail-row">
                    <span class="detail-label">Exit Code</span>
                    <span class="detail-value"><code>${event.exitCode}</code></span>
                </div>
                ` : ''}
                ${event.restartCount !== null ? `
                <div class="detail-row">
                    <span class="detail-label">Restart Count</span>
                    <span class="detail-value">${event.restartCount}</span>
                </div>
                ` : ''}
                ${event.errorMessage ? `
                <div class="detail-row">
                    <span class="detail-label">Error</span>
                    <span class="detail-value" style="color: var(--status-stopped);">${event.errorMessage}</span>
                </div>
                ` : ''}
                <div class="detail-row">
                    <span class="detail-label">Status</span>
                    <span class="detail-value">${renderStatusBadge(event.active)}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Notification Sent</span>
                    <span class="detail-value">${event.notificationSent ? 'Yes' : 'No'}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Detected At</span>
                    <span class="detail-value">${new Date(event.detectedAt).toLocaleString()}</span>
                </div>
                ${event.resolvedAt ? `
                <div class="detail-row">
                    <span class="detail-label">Resolved At</span>
                    <span class="detail-value">${new Date(event.resolvedAt).toLocaleString()}</span>
                </div>
                ` : ''}
            </div>
        `;

        openModal('Event Details', content, `
            ${event.active ? `
            <md-filled-button onclick="window.resolveFromModal('${event.id}')">
                <span class="material-symbols-outlined" slot="icon">check_circle</span>
                Resolve
            </md-filled-button>
            ` : ''}
            <md-text-button onclick="document.getElementById('app-dialog').close()">Close</md-text-button>
        `);

        // Expose resolve function for modal button
        window.resolveFromModal = async (eventId) => {
            await handleResolve(eventId);
            document.getElementById('app-dialog').close();
        };

    } catch (error) {
        showToast('Failed to load event details', 'error');
    }
}

// Cleanup
export function cleanup() {
    if (refreshInterval) {
        clearInterval(refreshInterval);
        refreshInterval = null;
    }
    issues = [];
    events = [];
    summary = null;
    pagination = { page: 0, size: 20, totalPages: 0, totalElements: 0 };
    filters = { type: '' };
    delete window.resolveFromModal;
}
