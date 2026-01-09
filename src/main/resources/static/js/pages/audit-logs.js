// Audit Logs Page (Admin Only)
import { isAdmin } from '../state.js';
import { getAuditLogs, getAuditLog, getDistinctActions, getResourceTypes } from '../api/audit.js';
import { listUsers } from '../api/users.js';
import { showToast } from '../components/toast.js';
import { openModal } from '../components/modal.js';
import { renderTable, setupTableActions } from '../components/data-table.js';

let logs = [];
let pagination = { page: 0, size: 50, totalPages: 0, totalElements: 0 };
let filters = { userId: '', action: '', resourceType: '', startDate: '', endDate: '', search: '' };
let users = [];
let actions = [];
let resourceTypes = [];

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
            <h2 class="section-title">Audit Logs</h2>
            <md-filled-button id="refresh-logs-btn">
                <span class="material-symbols-outlined" slot="icon">refresh</span>
                Refresh
            </md-filled-button>
        </div>

        <div class="card" style="margin-bottom: 16px;">
            <div class="card-content">
                <div class="filter-grid">
                    <md-filled-select id="filter-user" label="User">
                        <md-select-option value="" selected>All Users</md-select-option>
                    </md-filled-select>
                    <md-filled-select id="filter-action" label="Action">
                        <md-select-option value="" selected>All Actions</md-select-option>
                    </md-filled-select>
                    <md-filled-select id="filter-resource" label="Resource Type">
                        <md-select-option value="" selected>All Types</md-select-option>
                    </md-filled-select>
                    <md-filled-text-field id="filter-search" label="Search" type="text" placeholder="Search details..."></md-filled-text-field>
                </div>
                <div class="filter-grid" style="margin-top: 12px;">
                    <md-filled-text-field id="filter-start" label="Start Date" type="datetime-local"></md-filled-text-field>
                    <md-filled-text-field id="filter-end" label="End Date" type="datetime-local"></md-filled-text-field>
                    <md-filled-button id="apply-filters-btn">Apply Filters</md-filled-button>
                    <md-text-button id="clear-filters-btn">Clear</md-text-button>
                </div>
            </div>
        </div>

        <div class="card">
            <div class="card-content" id="audit-table">
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

    setupTableActions('audit-table', {
        view: showLogDetails,
    });

    await loadFilterOptions();
    await loadData();
}

// Load filter dropdown options
async function loadFilterOptions() {
    try {
        [users, actions, resourceTypes] = await Promise.all([
            listUsers().catch(() => []),
            getDistinctActions().catch(() => []),
            getResourceTypes().catch(() => []),
        ]);

        // Populate user dropdown
        const userSelect = document.getElementById('filter-user');
        if (userSelect) {
            users.forEach(u => {
                const option = document.createElement('md-select-option');
                option.value = u.id;
                option.textContent = u.username;
                userSelect.appendChild(option);
            });
        }

        // Populate action dropdown
        const actionSelect = document.getElementById('filter-action');
        if (actionSelect) {
            actions.forEach(a => {
                const option = document.createElement('md-select-option');
                option.value = a;
                option.textContent = a;
                actionSelect.appendChild(option);
            });
        }

        // Populate resource type dropdown
        const resourceSelect = document.getElementById('filter-resource');
        if (resourceSelect) {
            resourceTypes.forEach(r => {
                const option = document.createElement('md-select-option');
                option.value = r;
                option.textContent = r;
                resourceSelect.appendChild(option);
            });
        }
    } catch (error) {
        console.error('Failed to load filter options:', error);
    }
}

// Load audit logs
async function loadData() {
    try {
        const params = {
            page: pagination.page,
            size: pagination.size,
            ...filters,
        };

        // Remove empty filters
        Object.keys(params).forEach(k => {
            if (params[k] === '' || params[k] === null || params[k] === undefined) {
                delete params[k];
            }
        });

        const result = await getAuditLogs(params);
        logs = result.content || [];
        pagination.totalElements = result.totalElements || 0;
        pagination.totalPages = result.totalPages || 1;

        renderLogsTable();
        updatePagination();
    } catch (error) {
        console.error('Failed to load audit logs:', error);
        showToast('Failed to load audit logs', 'error');
    }
}

// Render logs table
function renderLogsTable() {
    const container = document.getElementById('audit-table');

    const columns = [
        {
            key: 'timestamp',
            label: 'Time',
            render: (ts) => formatTimestamp(ts)
        },
        { key: 'username', label: 'User' },
        { key: 'action', label: 'Action' },
        { key: 'resourceType', label: 'Resource' },
        {
            key: 'resourceId',
            label: 'Resource ID',
            render: (id) => id ? `<code>${truncate(id, 12)}</code>` : '-'
        },
        {
            key: 'success',
            label: 'Status',
            render: (success) => renderSuccessBadge(success)
        },
        { key: 'ipAddress', label: 'IP Address' },
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
        emptyMessage: 'No audit logs found',
        emptyIcon: 'history',
    });
}

// Render success/failure badge
function renderSuccessBadge(success) {
    if (success) {
        return '<span class="status-badge running">Success</span>';
    }
    return '<span class="status-badge stopped">Failed</span>';
}

// Format timestamp
function formatTimestamp(epochMillis) {
    const date = new Date(epochMillis);
    return date.toLocaleString('en-US', {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
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
    filters.userId = document.getElementById('filter-user')?.value || '';
    filters.action = document.getElementById('filter-action')?.value || '';
    filters.resourceType = document.getElementById('filter-resource')?.value || '';
    filters.search = document.getElementById('filter-search')?.value || '';

    const startDate = document.getElementById('filter-start')?.value;
    const endDate = document.getElementById('filter-end')?.value;

    filters.startDate = startDate ? new Date(startDate).toISOString() : '';
    filters.endDate = endDate ? new Date(endDate).toISOString() : '';

    pagination.page = 0;
    loadData();
}

// Clear filters
function clearFilters() {
    document.getElementById('filter-user').value = '';
    document.getElementById('filter-action').value = '';
    document.getElementById('filter-resource').value = '';
    document.getElementById('filter-search').value = '';
    document.getElementById('filter-start').value = '';
    document.getElementById('filter-end').value = '';

    filters = { userId: '', action: '', resourceType: '', startDate: '', endDate: '', search: '' };
    pagination.page = 0;
    loadData();
}

// Show log details modal
async function showLogDetails(id) {
    try {
        const log = await getAuditLog(id);

        let detailsJson = '-';
        if (log.details) {
            try {
                const parsed = JSON.parse(log.details);
                detailsJson = `<pre class="json-preview">${JSON.stringify(parsed, null, 2)}</pre>`;
            } catch {
                detailsJson = `<pre class="json-preview">${log.details}</pre>`;
            }
        }

        const content = `
            <div class="detail-grid">
                <div class="detail-row">
                    <span class="detail-label">Time</span>
                    <span class="detail-value">${new Date(log.timestamp).toLocaleString()}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">User</span>
                    <span class="detail-value">${log.username || '-'} <code style="margin-left: 8px;">${log.userId || '-'}</code></span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Action</span>
                    <span class="detail-value">${log.action}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Resource Type</span>
                    <span class="detail-value">${log.resourceType}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Resource ID</span>
                    <span class="detail-value"><code>${log.resourceId || '-'}</code></span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">Status</span>
                    <span class="detail-value">${renderSuccessBadge(log.success)}</span>
                </div>
                <div class="detail-row">
                    <span class="detail-label">IP Address</span>
                    <span class="detail-value">${log.ipAddress || '-'}</span>
                </div>
                ${log.errorMessage ? `
                <div class="detail-row">
                    <span class="detail-label">Error</span>
                    <span class="detail-value" style="color: var(--status-stopped);">${log.errorMessage}</span>
                </div>
                ` : ''}
                <div class="detail-row" style="flex-direction: column; align-items: flex-start;">
                    <span class="detail-label">Details</span>
                    <div style="width: 100%; margin-top: 8px;">${detailsJson}</div>
                </div>
            </div>
        `;

        openModal('Audit Log Details', content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `);
    } catch (error) {
        showToast('Failed to load log details', 'error');
    }
}

export function cleanup() {
    logs = [];
    users = [];
    actions = [];
    resourceTypes = [];
    pagination = { page: 0, size: 50, totalPages: 0, totalElements: 0 };
    filters = { userId: '', action: '', resourceType: '', startDate: '', endDate: '', search: '' };
}
