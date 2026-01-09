// Docker Hosts Page
import { state, setState } from '../state.js';
import { loadHosts, addHost, deleteHost, checkHostConnection } from '../api/docker.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions } from '../components/data-table.js';
import { renderConnectionStatus } from '../components/status-badge.js';
import { formatDate } from '../utils/format.js';

let hosts = [];

// Render hosts page
export function render() {
    return `
        <div class="section-header">
            <h2 class="section-title">Docker Hosts</h2>
            <md-filled-button id="add-host-btn">
                <span class="material-symbols-outlined" slot="icon">add</span>
                Add Host
            </md-filled-button>
        </div>
        <div class="card">
            <div class="card-content" id="hosts-table-container">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>
    `;
}

// Initialize page
export async function init() {
    // Setup add button
    document.getElementById('add-host-btn')?.addEventListener('click', showAddHostModal);

    // Setup table actions
    setupTableActions('hosts-table-container', {
        ping: handlePing,
        select: handleSelect,
        delete: handleDelete,
    });

    // Load data
    await loadData();
}

// Load hosts data
async function loadData() {
    try {
        hosts = await loadHosts();
        renderHostsTable();
    } catch (error) {
        console.error('Failed to load hosts:', error);
        showToast('Failed to load hosts', 'error');
    }
}

// Render hosts table
function renderHostsTable() {
    const container = document.getElementById('hosts-table-container');

    const columns = [
        { key: 'dockerHostUrl', label: 'URL', truncate: true },
        {
            key: 'id',
            label: 'Status',
            render: (id) => {
                const isSelected = id === state.currentHostId;
                return isSelected ?
                    '<span class="status-badge running">Selected</span>' :
                    '<span class="status-badge exited">Available</span>';
            }
        },
        { key: 'createdAt', label: 'Added', render: formatDate },
    ];

    const actions = (host) => renderActions([
        { icon: 'network_ping', title: 'Test Connection', action: 'ping' },
        {
            icon: 'check_circle',
            title: 'Select Host',
            action: 'select',
            hidden: host.id === state.currentHostId
        },
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: hosts,
        actions,
        emptyMessage: 'No Docker hosts configured',
        emptyIcon: 'dns',
    });
}

// Show add host modal
function showAddHostModal() {
    const content = `
        <form id="add-host-form" class="dialog-form">
            <md-filled-text-field
                id="host-url"
                label="Docker Host URL"
                placeholder="tcp://localhost:2375"
                required>
            </md-filled-text-field>
            <div class="form-hint">
                Examples: tcp://localhost:2375, unix:///var/run/docker.sock
            </div>
        </form>
    `;

    const actions = `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="add-host-submit">Add Host</md-filled-button>
    `;

    openModal('Add Docker Host', content, actions);

    // Setup submit handler
    setTimeout(() => {
        const submitBtn = document.getElementById('add-host-submit');
        submitBtn?.addEventListener('click', handleAddHost);

        const form = document.getElementById('add-host-form');
        form?.addEventListener('submit', (e) => {
            e.preventDefault();
            handleAddHost();
        });
    }, 100);
}

// Handle add host
async function handleAddHost() {
    const urlField = document.getElementById('host-url');
    const url = urlField?.value?.trim();
    const submitBtn = document.getElementById('add-host-submit');

    if (!url) {
        showToast('Please enter a Docker host URL', 'error');
        return;
    }

    submitBtn.disabled = true;

    try {
        await addHost(url);
        closeModal();
        showToast('Host added successfully', 'success');
        await loadData();
    } catch (error) {
        showToast('Failed to add host: ' + error.message, 'error');
    } finally {
        submitBtn.disabled = false;
    }
}

// Handle ping/test connection
async function handlePing(hostId) {
    showToast('Testing connection...', 'info');

    try {
        const connected = await checkHostConnection(hostId);
        if (connected) {
            showToast('Connection successful', 'success');
        } else {
            showToast('Connection failed', 'error');
        }
    } catch (error) {
        showToast('Connection failed: ' + error.message, 'error');
    }
}

// Handle select host
async function handleSelect(hostId) {
    setState('currentHostId', hostId);
    await checkHostConnection(hostId);
    showToast('Host selected', 'success');
    renderHostsTable();
}

// Handle delete host
async function handleDelete(hostId) {
    const host = hosts.find(h => h.id === hostId);
    if (!host) return;

    const confirmed = await confirmDelete('Host', `
        <p><strong>URL:</strong> ${host.dockerHostUrl}</p>
    `);

    if (!confirmed) return;

    try {
        await deleteHost(hostId);
        showToast('Host deleted', 'success');

        // If deleted current host, clear selection
        if (state.currentHostId === hostId) {
            setState('currentHostId', null);
        }

        await loadData();
    } catch (error) {
        showToast('Failed to delete host: ' + error.message, 'error');
    }
}

// Cleanup
export function cleanup() {
    hosts = [];
}
