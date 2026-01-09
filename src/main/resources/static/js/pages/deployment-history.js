// Deployment History Page
import { state } from '../state.js';
import {
    listOperations, getOperation, getOperationSnapshots, getOperationLogs,
    listComposeDeployments, getProjectHistory, getProjectNames,
    getRollbackPoints, validateRollback, executeRollback, rollbackComposeDeployment,
    getOperationDiff
} from '../api/deployments.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { showConfirm } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions, formatDate } from '../components/data-table.js';
import { renderDiffViewer, setupDiffViewerToggle } from '../components/diff-viewer.js';

let operations = [];
let composeDeployments = [];
let pagination = { page: 0, size: 20, totalPages: 0, totalElements: 0 };
let composePagination = { page: 0, size: 20, totalPages: 0, totalElements: 0 };
let filters = { type: '', status: '' };
let currentTab = 'operations';

const OPERATION_TYPES = [
    'CONTAINER_CREATE', 'CONTAINER_START', 'CONTAINER_STOP', 'CONTAINER_RESTART',
    'CONTAINER_DELETE', 'CONTAINER_PAUSE', 'CONTAINER_UNPAUSE',
    'IMAGE_PULL', 'IMAGE_DELETE', 'COMPOSE_UP', 'COMPOSE_DOWN',
    'VOLUME_CREATE', 'VOLUME_DELETE', 'NETWORK_CREATE', 'NETWORK_DELETE'
];

const STATUS_TYPES = ['PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'ROLLED_BACK'];

export function render() {
    return `
        <div class="section-header">
            <h2 class="section-title">Deployment History</h2>
            <md-filled-button id="refresh-history-btn">
                <span class="material-symbols-outlined" slot="icon">refresh</span>
                Refresh
            </md-filled-button>
        </div>

        <div class="tabs-container">
            <md-tabs id="history-tabs">
                <md-primary-tab id="tab-operations" aria-controls="panel-operations">
                    <span class="material-symbols-outlined" slot="icon">history</span>
                    All Operations
                </md-primary-tab>
                <md-primary-tab id="tab-compose" aria-controls="panel-compose">
                    <span class="material-symbols-outlined" slot="icon">layers</span>
                    Compose Deployments
                </md-primary-tab>
            </md-tabs>
        </div>

        <div id="panel-operations" role="tabpanel" aria-labelledby="tab-operations">
            <div class="card" style="margin-bottom: 16px;">
                <div class="card-content">
                    <div class="filter-grid">
                        <md-filled-select id="filter-type" label="Operation Type">
                            <md-select-option value="" selected>All Types</md-select-option>
                            ${OPERATION_TYPES.map(t => `<md-select-option value="${t}">${formatOperationType(t)}</md-select-option>`).join('')}
                        </md-filled-select>
                        <md-filled-select id="filter-status" label="Status">
                            <md-select-option value="" selected>All Status</md-select-option>
                            ${STATUS_TYPES.map(s => `<md-select-option value="${s}">${s}</md-select-option>`).join('')}
                        </md-filled-select>
                        <md-filled-button id="apply-filters-btn">Apply</md-filled-button>
                        <md-text-button id="clear-filters-btn">Clear</md-text-button>
                    </div>
                </div>
            </div>

            <div class="card">
                <div class="card-content" id="operations-table">
                    <div class="loading-container">
                        <md-circular-progress indeterminate></md-circular-progress>
                    </div>
                </div>
            </div>

            <div class="pagination" id="operations-pagination">
                <md-text-button id="ops-prev-page" disabled>
                    <span class="material-symbols-outlined" slot="icon">chevron_left</span>
                    Previous
                </md-text-button>
                <span id="ops-page-info">Page 1 of 1</span>
                <md-text-button id="ops-next-page" disabled>
                    Next
                    <span class="material-symbols-outlined" slot="icon">chevron_right</span>
                </md-text-button>
            </div>
        </div>

        <div id="panel-compose" role="tabpanel" aria-labelledby="tab-compose" class="hidden">
            <div class="card">
                <div class="card-content" id="compose-table">
                    <div class="loading-container">
                        <md-circular-progress indeterminate></md-circular-progress>
                    </div>
                </div>
            </div>

            <div class="pagination" id="compose-pagination">
                <md-text-button id="compose-prev-page" disabled>
                    <span class="material-symbols-outlined" slot="icon">chevron_left</span>
                    Previous
                </md-text-button>
                <span id="compose-page-info">Page 1 of 1</span>
                <md-text-button id="compose-next-page" disabled>
                    Next
                    <span class="material-symbols-outlined" slot="icon">chevron_right</span>
                </md-text-button>
            </div>
        </div>
    `;
}

export async function init() {
    document.getElementById('refresh-history-btn')?.addEventListener('click', refreshData);
    document.getElementById('apply-filters-btn')?.addEventListener('click', applyFilters);
    document.getElementById('clear-filters-btn')?.addEventListener('click', clearFilters);

    // Tab switching
    const tabs = document.getElementById('history-tabs');
    tabs?.addEventListener('change', (e) => {
        const activeTab = tabs.activeTab;
        if (activeTab?.id === 'tab-operations') {
            switchTab('operations');
        } else if (activeTab?.id === 'tab-compose') {
            switchTab('compose');
        }
    });

    // Pagination
    document.getElementById('ops-prev-page')?.addEventListener('click', () => changePage('operations', -1));
    document.getElementById('ops-next-page')?.addEventListener('click', () => changePage('operations', 1));
    document.getElementById('compose-prev-page')?.addEventListener('click', () => changePage('compose', -1));
    document.getElementById('compose-next-page')?.addEventListener('click', () => changePage('compose', 1));

    setupTableActions('operations-table', {
        details: showOperationDetails,
        diff: showOperationDiff,
        rollback: handleRollback,
    });

    setupTableActions('compose-table', {
        details: showComposeDetails,
        history: showProjectHistory,
    });

    await loadData();
}

function switchTab(tab) {
    currentTab = tab;
    const operationsPanel = document.getElementById('panel-operations');
    const composePanel = document.getElementById('panel-compose');

    if (tab === 'operations') {
        operationsPanel?.classList.remove('hidden');
        composePanel?.classList.add('hidden');
    } else {
        operationsPanel?.classList.add('hidden');
        composePanel?.classList.remove('hidden');
        if (composeDeployments.length === 0) {
            loadComposeDeployments();
        }
    }
}

async function refreshData() {
    if (currentTab === 'operations') {
        await loadData();
    } else {
        await loadComposeDeployments();
    }
}

async function loadData() {
    if (!state.currentHostId) {
        showEmptyHost();
        return;
    }

    try {
        const params = {
            page: pagination.page,
            size: pagination.size,
            ...filters,
        };

        Object.keys(params).forEach(k => {
            if (params[k] === '' || params[k] === null || params[k] === undefined) {
                delete params[k];
            }
        });

        const result = await listOperations(state.currentHostId, params);
        operations = result.content || [];
        pagination.totalElements = result.totalElements || 0;
        pagination.totalPages = result.totalPages || 1;

        renderOperationsTable();
        updatePagination('operations');
    } catch (error) {
        console.error('Failed to load operations:', error);
        showToast('Failed to load deployment history', 'error');
    }
}

async function loadComposeDeployments() {
    if (!state.currentHostId) return;

    try {
        const result = await listComposeDeployments(state.currentHostId, {
            page: composePagination.page,
            size: composePagination.size,
        });
        composeDeployments = result.content || [];
        composePagination.totalElements = result.totalElements || 0;
        composePagination.totalPages = result.totalPages || 1;

        renderComposeTable();
        updatePagination('compose');
    } catch (error) {
        console.error('Failed to load compose deployments:', error);
        showToast('Failed to load compose deployments', 'error');
    }
}

function renderOperationsTable() {
    const container = document.getElementById('operations-table');

    const columns = [
        {
            key: 'createdAt',
            label: 'Time',
            render: (ts) => formatDate(ts)
        },
        {
            key: 'operationType',
            label: 'Operation',
            render: formatOperationType
        },
        { key: 'resourceName', label: 'Resource' },
        {
            key: 'status',
            label: 'Status',
            render: renderStatusBadge
        },
        { key: 'username', label: 'User' },
        {
            key: 'durationMs',
            label: 'Duration',
            render: (ms) => ms ? `${ms}ms` : '-'
        },
        {
            key: 'commitSha',
            label: 'Commit',
            render: (sha) => sha ? `<code>${sha.substring(0, 7)}</code>` : '-',
            mono: true
        },
    ];

    const actions = (op) => {
        const items = [
            { icon: 'info', title: 'Details', action: 'details' },
            { icon: 'difference', title: 'View Diff', action: 'diff' },
        ];

        if (op.rollbackAvailable && op.status !== 'ROLLED_BACK') {
            items.push({ icon: 'undo', title: 'Rollback', action: 'rollback', color: 'var(--md-sys-color-tertiary)' });
        }

        return renderActions(items);
    };

    container.innerHTML = renderTable({
        columns,
        data: operations,
        actions,
        emptyMessage: 'No operations found',
        emptyIcon: 'history',
    });
}

function renderComposeTable() {
    const container = document.getElementById('compose-table');

    const columns = [
        {
            key: 'createdAt',
            label: 'Time',
            render: (ts) => formatDate(ts)
        },
        { key: 'projectName', label: 'Project' },
        {
            key: 'version',
            label: 'Version',
            render: (v) => `v${v}`
        },
        {
            key: 'status',
            label: 'Status',
            render: renderDeploymentStatusBadge
        },
        { key: 'username', label: 'User' },
        {
            key: 'commitSha',
            label: 'Commit',
            render: (sha) => sha ? `<code>${sha.substring(0, 7)}</code>` : '-',
            mono: true
        },
        { key: 'gitRepositoryName', label: 'Repository' },
    ];

    const actions = (d) => renderActions([
        { icon: 'info', title: 'Details', action: 'details' },
        { icon: 'history', title: 'Version History', action: 'history' },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: composeDeployments,
        actions,
        emptyMessage: 'No compose deployments found',
        emptyIcon: 'layers',
    });
}

function formatOperationType(type) {
    if (!type) return '-';
    return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
}

function renderStatusBadge(status) {
    const classes = {
        PENDING: 'status-badge paused',
        RUNNING: 'status-badge running',
        SUCCESS: 'status-badge running',
        FAILED: 'status-badge stopped',
        ROLLED_BACK: 'status-badge exited',
    };
    return `<span class="${classes[status] || 'status-badge'}">${status}</span>`;
}

function renderDeploymentStatusBadge(status) {
    const classes = {
        PENDING: 'status-badge paused',
        DEPLOYING: 'status-badge running',
        ACTIVE: 'status-badge running',
        STOPPED: 'status-badge exited',
        FAILED: 'status-badge stopped',
        ROLLED_BACK: 'status-badge exited',
    };
    return `<span class="${classes[status] || 'status-badge'}">${status}</span>`;
}

function updatePagination(type) {
    const p = type === 'operations' ? pagination : composePagination;
    const prefix = type === 'operations' ? 'ops' : 'compose';

    const pageInfo = document.getElementById(`${prefix}-page-info`);
    const prevBtn = document.getElementById(`${prefix}-prev-page`);
    const nextBtn = document.getElementById(`${prefix}-next-page`);

    if (pageInfo) {
        pageInfo.textContent = `Page ${p.page + 1} of ${Math.max(1, p.totalPages)} (${p.totalElements} total)`;
    }
    if (prevBtn) prevBtn.disabled = p.page === 0;
    if (nextBtn) nextBtn.disabled = p.page >= p.totalPages - 1;
}

function changePage(type, delta) {
    const p = type === 'operations' ? pagination : composePagination;
    const newPage = p.page + delta;

    if (newPage >= 0 && newPage < p.totalPages) {
        p.page = newPage;
        if (type === 'operations') {
            loadData();
        } else {
            loadComposeDeployments();
        }
    }
}

function applyFilters() {
    filters.type = document.getElementById('filter-type')?.value || '';
    filters.status = document.getElementById('filter-status')?.value || '';
    pagination.page = 0;
    loadData();
}

function clearFilters() {
    document.getElementById('filter-type').value = '';
    document.getElementById('filter-status').value = '';
    filters = { type: '', status: '' };
    pagination.page = 0;
    loadData();
}

async function showOperationDetails(id) {
    try {
        const op = await getOperation(id);
        const snapshots = await getOperationSnapshots(id);

        const beforeSnapshot = snapshots.find(s => s.snapshotType === 'BEFORE');
        const afterSnapshot = snapshots.find(s => s.snapshotType === 'AFTER');

        const content = `
            <div class="info-grid" style="margin-bottom: 24px;">
                <span class="info-label">ID:</span><span class="info-value mono">${op.id}</span>
                <span class="info-label">Type:</span><span class="info-value">${formatOperationType(op.operationType)}</span>
                <span class="info-label">Resource:</span><span class="info-value">${op.resourceName || '-'}</span>
                <span class="info-label">Resource ID:</span><span class="info-value mono">${op.resourceId || '-'}</span>
                <span class="info-label">Status:</span><span class="info-value">${renderStatusBadge(op.status)}</span>
                <span class="info-label">User:</span><span class="info-value">${op.username || '-'}</span>
                <span class="info-label">Started:</span><span class="info-value">${formatDate(op.createdAt)}</span>
                <span class="info-label">Completed:</span><span class="info-value">${op.completedAt ? formatDate(op.completedAt) : '-'}</span>
                <span class="info-label">Duration:</span><span class="info-value">${op.durationMs ? op.durationMs + 'ms' : '-'}</span>
                ${op.commitSha ? `<span class="info-label">Commit:</span><span class="info-value mono">${op.commitSha}</span>` : ''}
                ${op.errorMessage ? `<span class="info-label">Error:</span><span class="info-value" style="color: var(--status-stopped);">${op.errorMessage}</span>` : ''}
            </div>

            ${snapshots.length > 0 ? `
                <h4 style="margin-bottom: 12px;">State Snapshots</h4>
                <div style="display: flex; gap: 12px; margin-bottom: 16px;">
                    ${beforeSnapshot ? `<span class="status-badge paused">Before Snapshot</span>` : ''}
                    ${afterSnapshot ? `<span class="status-badge running">After Snapshot</span>` : ''}
                </div>
            ` : ''}

            ${op.logs ? `
                <h4 style="margin-bottom: 12px;">Logs</h4>
                <pre class="log-output">${op.logs}</pre>
            ` : ''}
        `;

        openModal('Operation Details', content, `
            ${op.rollbackAvailable && op.status !== 'ROLLED_BACK' ? `
                <md-text-button onclick="window.rollbackOperation('${op.id}')">
                    <span class="material-symbols-outlined" slot="icon">undo</span>
                    Rollback
                </md-text-button>
            ` : ''}
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `);

        // Expose rollback function globally for the button
        window.rollbackOperation = (opId) => {
            closeModal();
            handleRollback(opId);
        };
    } catch (error) {
        showToast('Failed to load operation details', 'error');
    }
}

async function showOperationDiff(id) {
    try {
        const diff = await getOperationDiff(id);

        const content = renderDiffViewer(diff, { defaultView: 'unified' });

        openModal('Operation Diff', content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `, { fullWidth: true });

        setTimeout(() => {
            const dialog = document.getElementById('app-dialog');
            if (dialog) {
                setupDiffViewerToggle(dialog);
            }
        }, 100);
    } catch (error) {
        showToast('Failed to load diff: ' + error.message, 'error');
    }
}

async function handleRollback(operationId) {
    try {
        const validation = await validateRollback(operationId);

        if (!validation.canRollback) {
            showToast('Cannot rollback: ' + validation.reason, 'error');
            return;
        }

        const confirmed = await showConfirm({
            title: 'Confirm Rollback',
            message: 'Are you sure you want to rollback this operation?',
            confirmText: 'Rollback',
            type: 'warning',
            details: '<p>This will restore the resource to its previous state.</p>',
        });

        if (!confirmed) return;

        const result = await executeRollback(operationId);

        if (result.success) {
            showToast('Rollback completed successfully', 'success');
            await loadData();
        } else {
            showToast('Rollback failed: ' + result.message, 'error');
        }
    } catch (error) {
        showToast('Rollback failed: ' + error.message, 'error');
    }
}

async function showComposeDetails(id) {
    try {
        const deployment = composeDeployments.find(d => d.id === id);
        if (!deployment) {
            showToast('Deployment not found', 'error');
            return;
        }

        const content = `
            <div class="info-grid" style="margin-bottom: 24px;">
                <span class="info-label">Project:</span><span class="info-value">${deployment.projectName}</span>
                <span class="info-label">Version:</span><span class="info-value">v${deployment.version}</span>
                <span class="info-label">Status:</span><span class="info-value">${renderDeploymentStatusBadge(deployment.status)}</span>
                <span class="info-label">User:</span><span class="info-value">${deployment.username || '-'}</span>
                <span class="info-label">Created:</span><span class="info-value">${formatDate(deployment.createdAt)}</span>
                ${deployment.commitSha ? `<span class="info-label">Commit:</span><span class="info-value mono">${deployment.commitSha}</span>` : ''}
                ${deployment.gitRepositoryName ? `<span class="info-label">Repository:</span><span class="info-value">${deployment.gitRepositoryName}</span>` : ''}
            </div>

            <h4 style="margin-bottom: 12px;">Compose Content</h4>
            <pre class="log-output" style="max-height: 300px; overflow: auto;">${deployment.composeContent || 'No content'}</pre>

            ${deployment.logs ? `
                <h4 style="margin: 16px 0 12px;">Deployment Logs</h4>
                <pre class="log-output">${deployment.logs}</pre>
            ` : ''}
        `;

        openModal('Compose Deployment Details', content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `, { fullWidth: true });
    } catch (error) {
        showToast('Failed to load deployment details', 'error');
    }
}

async function showProjectHistory(id) {
    try {
        const deployment = composeDeployments.find(d => d.id === id);
        if (!deployment) return;

        const history = await getProjectHistory(state.currentHostId, deployment.projectName);

        const historyRows = history.map(h => `
            <tr>
                <td>v${h.version}</td>
                <td>${renderDeploymentStatusBadge(h.status)}</td>
                <td>${h.username || '-'}</td>
                <td>${formatDate(h.createdAt)}</td>
                <td>${h.commitSha ? `<code>${h.commitSha.substring(0, 7)}</code>` : '-'}</td>
                <td>
                    ${h.status === 'ACTIVE' ? '' : `
                        <md-text-button onclick="window.rollbackToVersion('${deployment.id}', ${h.version})">
                            Restore
                        </md-text-button>
                    `}
                </td>
            </tr>
        `).join('');

        const content = `
            <h4 style="margin-bottom: 16px;">Version History for "${deployment.projectName}"</h4>
            <table class="data-table">
                <thead>
                    <tr>
                        <th>Version</th>
                        <th>Status</th>
                        <th>User</th>
                        <th>Created</th>
                        <th>Commit</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    ${historyRows}
                </tbody>
            </table>
        `;

        openModal('Version History', content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `, { fullWidth: true });

        window.rollbackToVersion = async (deploymentId, version) => {
            const confirmed = await showConfirm({
                title: 'Restore Version',
                message: `Restore to version ${version}?`,
                confirmText: 'Restore',
                type: 'warning',
            });

            if (!confirmed) return;

            try {
                const result = await rollbackComposeDeployment(deploymentId, version);
                if (result.success) {
                    showToast('Restored to v' + version, 'success');
                    closeModal();
                    await loadComposeDeployments();
                } else {
                    showToast('Restore failed: ' + result.message, 'error');
                }
            } catch (error) {
                showToast('Restore failed: ' + error.message, 'error');
            }
        };
    } catch (error) {
        showToast('Failed to load version history', 'error');
    }
}

function showEmptyHost() {
    document.getElementById('operations-table').innerHTML = `
        <div class="empty-state">
            <span class="material-symbols-outlined">dns</span>
            <h3>No host selected</h3>
        </div>
    `;
}

export function cleanup() {
    operations = [];
    composeDeployments = [];
    pagination = { page: 0, size: 20, totalPages: 0, totalElements: 0 };
    composePagination = { page: 0, size: 20, totalPages: 0, totalElements: 0 };
    filters = { type: '', status: '' };
    currentTab = 'operations';
    delete window.rollbackOperation;
    delete window.rollbackToVersion;
}
