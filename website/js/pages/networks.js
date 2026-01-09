// Networks Page
import { state } from '../state.js';
import { listNetworks, createNetwork, removeNetwork, getNetwork, connectToNetwork, disconnectFromNetwork, listContainers } from '../api/docker.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions } from '../components/data-table.js';

let networks = [];
let networkContainerCounts = new Map();
const PROTECTED_NETWORKS = ['bridge', 'host', 'none'];

// Render page
export function render() {
    return `
        <div class="section-header">
            <h2 class="section-title">Networks</h2>
            <md-filled-button id="create-network-btn">
                <span class="material-symbols-outlined" slot="icon">add</span>
                Create Network
            </md-filled-button>
        </div>
        <div class="card">
            <div class="card-content" id="networks-table">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>
    `;
}

// Initialize
export async function init() {
    document.getElementById('create-network-btn')?.addEventListener('click', showCreateModal);

    setupTableActions('networks-table', {
        details: showDetailsModal,
        delete: handleDelete,
    });

    await loadData();
}

// Load data
async function loadData() {
    if (!state.currentHostId) {
        showEmptyHost();
        return;
    }

    try {
        const [networkList, containers] = await Promise.all([
            listNetworks(state.currentHostId),
            listContainers(state.currentHostId, true).catch(() => [])
        ]);

        networks = networkList;

        // Build map of network ID/name -> container count
        networkContainerCounts.clear();
        containers.forEach(container => {
            if (container.networkSettings?.networks) {
                Object.keys(container.networkSettings.networks).forEach(netName => {
                    const count = networkContainerCounts.get(netName) || 0;
                    networkContainerCounts.set(netName, count + 1);
                });
            }
        });

        renderNetworksTable();
    } catch (error) {
        console.error('Failed to load networks:', error);
        showToast('Failed to load networks', 'error');
    }
}

// Render table
function renderNetworksTable() {
    const container = document.getElementById('networks-table');

    const columns = [
        { key: 'name', label: 'Name' },
        { key: 'driver', label: 'Driver' },
        { key: 'scope', label: 'Scope' },
        {
            key: 'name',
            label: 'Status',
            render: (name) => {
                const count = networkContainerCounts.get(name) || 0;
                if (count > 0) {
                    return `<span class="status-badge running">${count} container${count > 1 ? 's' : ''}</span>`;
                }
                return '<span class="status-badge exited">Unused</span>';
            }
        },
        {
            key: 'id',
            label: 'ID',
            render: (id) => id?.substring(0, 12),
            mono: true
        },
    ];

    const actions = (net) => {
        const isProtected = PROTECTED_NETWORKS.includes(net.name);
        return renderActions([
            { icon: 'info', title: 'Details', action: 'details' },
            {
                icon: 'delete',
                title: 'Delete',
                action: 'delete',
                color: 'var(--status-stopped)',
                disabled: isProtected,
                hidden: isProtected
            },
        ]);
    };

    container.innerHTML = renderTable({
        columns,
        data: networks,
        actions,
        emptyMessage: 'No networks found',
        emptyIcon: 'hub',
    });
}

// Show create modal
function showCreateModal() {
    const content = `
        <form id="create-network-form" class="dialog-form">
            <md-filled-text-field
                id="network-name"
                label="Network Name"
                placeholder="my-network"
                required>
            </md-filled-text-field>
        </form>
    `;

    openModal('Create Network', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="create-submit">Create</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('create-submit')?.addEventListener('click', handleCreate);
    }, 100);
}

// Handle create
async function handleCreate() {
    const name = document.getElementById('network-name')?.value?.trim();
    const btn = document.getElementById('create-submit');

    if (!name) {
        showToast('Please enter a network name', 'error');
        return;
    }

    if (btn) {
        btn.disabled = true;
        btn.textContent = 'Creating...';
    }

    try {
        await createNetwork(state.currentHostId, name);
        closeModal();
        showToast('Network created', 'success');
        setTimeout(() => loadData().catch(() => {}), 300);
    } catch (error) {
        showToast('Failed to create network: ' + error.message, 'error');
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = 'Create';
        }
    }
}

// Handle delete
async function handleDelete(id) {
    const net = networks.find(n => n.id === id);
    if (!net || PROTECTED_NETWORKS.includes(net.name)) {
        showToast('Cannot delete built-in network', 'error');
        return;
    }

    const confirmed = await confirmDelete('Network', `<p><strong>${net.name}</strong></p>`);
    if (!confirmed) return;

    try {
        await removeNetwork(state.currentHostId, id);
        showToast('Network deleted', 'success');
        setTimeout(() => loadData().catch(() => {}), 300);
    } catch (error) {
        showToast('Failed to delete network: ' + error.message, 'error');
    }
}

// Show details modal
async function showDetailsModal(id) {
    try {
        const details = await getNetwork(state.currentHostId, id);
        const containers = await listContainers(state.currentHostId, true);

        // Get connected containers
        const connectedIds = Object.keys(details.containers || {});
        const connectedContainers = containers.filter(c => connectedIds.includes(c.id));

        const containersList = connectedContainers.length > 0
            ? connectedContainers.map(c => `
                <div style="display: flex; justify-content: space-between; align-items: center; padding: 8px; background: var(--md-sys-color-surface-container); border-radius: 8px; margin-bottom: 8px;">
                    <span>${c.names?.[0]?.replace(/^\//, '') || c.id.substring(0, 12)}</span>
                    <md-text-button data-disconnect="${c.id}">Disconnect</md-text-button>
                </div>
            `).join('')
            : '<p style="color: var(--md-sys-color-on-surface-variant);">No containers connected</p>';

        // Get available containers to connect
        const availableContainers = containers.filter(c =>
            c.state === 'running' && !connectedIds.includes(c.id)
        );

        const connectOptions = availableContainers.map(c =>
            `<md-select-option value="${c.id}">${c.names?.[0]?.replace(/^\//, '') || c.id.substring(0, 12)}</md-select-option>`
        ).join('');

        const content = `
            <div class="info-grid" style="margin-bottom: 24px;">
                <span class="info-label">Name:</span><span class="info-value">${details.name}</span>
                <span class="info-label">ID:</span><span class="info-value mono">${details.id?.substring(0, 12)}</span>
                <span class="info-label">Driver:</span><span class="info-value">${details.driver}</span>
                <span class="info-label">Scope:</span><span class="info-value">${details.scope}</span>
                <span class="info-label">Subnet:</span><span class="info-value">${details.ipam?.config?.[0]?.subnet || 'N/A'}</span>
                <span class="info-label">Gateway:</span><span class="info-value">${details.ipam?.config?.[0]?.gateway || 'N/A'}</span>
            </div>

            <h4 style="margin-bottom: 12px;">Connected Containers</h4>
            <div id="connected-containers">${containersList}</div>

            ${availableContainers.length > 0 ? `
                <div style="margin-top: 16px; display: flex; gap: 8px;">
                    <md-filled-select id="connect-container" label="Connect Container" style="flex: 1;">
                        ${connectOptions}
                    </md-filled-select>
                    <md-filled-button id="connect-btn">Connect</md-filled-button>
                </div>
            ` : ''}
        `;

        openModal('Network Details', content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `);

        // Setup connect/disconnect handlers
        setTimeout(() => {
            document.getElementById('connect-btn')?.addEventListener('click', async () => {
                const containerId = document.getElementById('connect-container')?.value;
                if (!containerId) return;

                try {
                    await connectToNetwork(state.currentHostId, id, containerId);
                    showToast('Container connected', 'success');
                    closeModal();
                    showDetailsModal(id); // Refresh
                } catch (e) {
                    showToast('Failed: ' + e.message, 'error');
                }
            });

            document.querySelectorAll('[data-disconnect]').forEach(btn => {
                btn.addEventListener('click', async () => {
                    const containerId = btn.dataset.disconnect;
                    try {
                        await disconnectFromNetwork(state.currentHostId, id, containerId);
                        showToast('Container disconnected', 'success');
                        closeModal();
                        showDetailsModal(id); // Refresh
                    } catch (e) {
                        showToast('Failed: ' + e.message, 'error');
                    }
                });
            });
        }, 100);

    } catch (error) {
        showToast('Failed to load network details', 'error');
    }
}

function showEmptyHost() {
    document.getElementById('networks-table').innerHTML = `
        <div class="empty-state">
            <span class="material-symbols-outlined">dns</span>
            <h3>No host selected</h3>
        </div>
    `;
}

export function cleanup() {
    networks = [];
    networkContainerCounts.clear();
}
