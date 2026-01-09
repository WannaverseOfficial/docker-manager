// Volumes Page
import { state } from '../state.js';
import { listVolumes, createVolume, removeVolume, listContainers } from '../api/docker.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions } from '../components/data-table.js';

let volumes = [];
let usedVolumes = new Set();

// Render page
export function render() {
    return `
        <div class="section-header">
            <h2 class="section-title">Volumes</h2>
            <md-filled-button id="create-volume-btn">
                <span class="material-symbols-outlined" slot="icon">add</span>
                Create Volume
            </md-filled-button>
        </div>
        <div class="card">
            <div class="card-content" id="volumes-table">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>
    `;
}

// Initialize
export async function init() {
    document.getElementById('create-volume-btn')?.addEventListener('click', showCreateModal);

    setupTableActions('volumes-table', {
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
        const [volumeList, containers] = await Promise.all([
            listVolumes(state.currentHostId),
            listContainers(state.currentHostId, true).catch(() => [])
        ]);

        volumes = volumeList;

        // Build set of used volume names from container mounts
        usedVolumes.clear();
        containers.forEach(container => {
            (container.mounts || []).forEach(mount => {
                if (mount.type === 'volume' && mount.name) {
                    usedVolumes.add(mount.name);
                }
            });
        });

        renderVolumesTable();
    } catch (error) {
        console.error('Failed to load volumes:', error);
        showToast('Failed to load volumes', 'error');
    }
}

// Render table
function renderVolumesTable() {
    const container = document.getElementById('volumes-table');

    const columns = [
        { key: 'name', label: 'Name' },
        { key: 'driver', label: 'Driver' },
        {
            key: 'name',
            label: 'Status',
            render: (name) => {
                const inUse = usedVolumes.has(name);
                return inUse
                    ? '<span class="status-badge running">In Use</span>'
                    : '<span class="status-badge exited">Unused</span>';
            }
        },
        { key: 'mountpoint', label: 'Mountpoint', truncate: true, mono: true },
    ];

    const actions = (vol) => renderActions([
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: volumes,
        actions,
        emptyMessage: 'No volumes found',
        emptyIcon: 'hard_drive',
        getId: (vol) => vol.name,
    });
}

// Show create modal
function showCreateModal() {
    const content = `
        <form id="create-volume-form" class="dialog-form">
            <md-filled-text-field
                id="volume-name"
                label="Volume Name"
                placeholder="my-volume"
                required>
            </md-filled-text-field>
        </form>
    `;

    openModal('Create Volume', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="create-submit">Create</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('create-submit')?.addEventListener('click', handleCreate);
    }, 100);
}

// Handle create
async function handleCreate() {
    const name = document.getElementById('volume-name')?.value?.trim();
    const btn = document.getElementById('create-submit');

    if (!name) {
        showToast('Please enter a volume name', 'error');
        return;
    }

    if (btn) {
        btn.disabled = true;
        btn.textContent = 'Creating...';
    }

    try {
        await createVolume(state.currentHostId, name);
        closeModal();
        showToast('Volume created', 'success');
        setTimeout(() => loadData().catch(() => {}), 300);
    } catch (error) {
        showToast('Failed to create volume: ' + error.message, 'error');
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = 'Create';
        }
    }
}

// Handle delete
async function handleDelete(name) {
    const confirmed = await confirmDelete('Volume', `<p><strong>${name}</strong></p>`);
    if (!confirmed) return;

    try {
        await removeVolume(state.currentHostId, name);
        showToast('Volume deleted', 'success');
        setTimeout(() => loadData().catch(() => {}), 300);
    } catch (error) {
        showToast('Failed to delete volume: ' + error.message, 'error');
    }
}

function showEmptyHost() {
    document.getElementById('volumes-table').innerHTML = `
        <div class="empty-state">
            <span class="material-symbols-outlined">dns</span>
            <h3>No host selected</h3>
        </div>
    `;
}

export function cleanup() {
    volumes = [];
    usedVolumes.clear();
}
