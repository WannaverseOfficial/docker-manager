// Images Page
import { state } from '../state.js';
import { listImages, pullImage, removeImage, getImage, getDanglingImages, cleanupDanglingImages, listContainers } from '../api/docker.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete, showConfirm } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions, formatBytes, formatDate } from '../components/data-table.js';
import { truncate } from '../utils/format.js';

let images = [];
let usedImageIds = new Set();

// Render page
export function render() {
    return `
        <div class="section-header">
            <h2 class="section-title">Images</h2>
            <div class="card-actions">
                <md-outlined-button id="cleanup-images-btn">
                    <span class="material-symbols-outlined" slot="icon">cleaning_services</span>
                    Cleanup
                </md-outlined-button>
                <md-filled-button id="pull-image-btn">
                    <span class="material-symbols-outlined" slot="icon">cloud_download</span>
                    Pull Image
                </md-filled-button>
            </div>
        </div>
        <div class="card">
            <div class="card-content" id="images-table">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>
    `;
}

// Initialize
export async function init() {
    document.getElementById('pull-image-btn')?.addEventListener('click', showPullModal);
    document.getElementById('cleanup-images-btn')?.addEventListener('click', handleCleanup);

    setupTableActions('images-table', {
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
        const [imageList, containers] = await Promise.all([
            listImages(state.currentHostId),
            listContainers(state.currentHostId, true).catch(() => [])
        ]);

        images = imageList;

        // Build set of used image IDs from containers
        usedImageIds.clear();
        containers.forEach(container => {
            if (container.imageId) {
                usedImageIds.add(container.imageId);
            }
            // Also check image name in case ID format differs
            if (container.image) {
                usedImageIds.add(container.image);
            }
        });

        renderImagesTable();
    } catch (error) {
        console.error('Failed to load images:', error);
        showToast('Failed to load images', 'error');
    }
}

// Check if an image is in use
function isImageInUse(img) {
    // Check by image ID
    if (usedImageIds.has(img.id)) return true;

    // Check by repo tags (e.g., nginx:latest)
    if (img.repoTags) {
        for (const tag of img.repoTags) {
            if (usedImageIds.has(tag)) return true;
        }
    }

    return false;
}

// Render table
function renderImagesTable() {
    const container = document.getElementById('images-table');

    const columns = [
        {
            key: 'repoTags',
            label: 'Repository:Tag',
            render: (tags) => {
                if (!tags || tags.length === 0 || tags[0] === '<none>:<none>') {
                    return '<em>none</em>';
                }
                return tags[0];
            }
        },
        {
            key: 'id',
            label: 'ID',
            render: (id) => id?.replace('sha256:', '').substring(0, 12),
            mono: true
        },
        {
            key: 'id',
            label: 'Status',
            render: (id, img) => {
                const inUse = isImageInUse(img);
                return inUse
                    ? '<span class="status-badge running">In Use</span>'
                    : '<span class="status-badge exited">Unused</span>';
            }
        },
        { key: 'size', label: 'Size', render: formatBytes },
        { key: 'created', label: 'Created', render: (ts) => formatDate(ts * 1000) },
    ];

    const actions = (img) => renderActions([
        { icon: 'info', title: 'Details', action: 'details' },
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: images,
        actions,
        emptyMessage: 'No images found',
        emptyIcon: 'layers',
        getId: (img) => img.id,
    });
}

// Show pull modal
function showPullModal() {
    const content = `
        <form id="pull-form" class="dialog-form">
            <md-filled-text-field
                id="pull-image-name"
                label="Image Name"
                placeholder="nginx:latest"
                required>
            </md-filled-text-field>
            <div class="form-hint">Enter image name with optional tag (e.g., nginx:latest, ubuntu:22.04)</div>
        </form>
    `;

    openModal('Pull Image', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="pull-submit">Pull</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('pull-submit')?.addEventListener('click', handlePull);
    }, 100);
}

// Handle pull
async function handlePull() {
    const imageName = document.getElementById('pull-image-name')?.value?.trim();
    const btn = document.getElementById('pull-submit');

    if (!imageName) {
        showToast('Please enter an image name', 'error');
        return;
    }

    if (btn) {
        btn.disabled = true;
        btn.textContent = 'Pulling...';
    }

    try {
        await pullImage(state.currentHostId, imageName);
        closeModal();
        showToast('Image pulled successfully', 'success');
        // Small delay to let Docker register the image
        setTimeout(() => loadData().catch(() => {}), 500);
    } catch (error) {
        showToast('Failed to pull image: ' + error.message, 'error');
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = 'Pull';
        }
    }
}

// Handle delete
async function handleDelete(id) {
    const img = images.find(i => i.id === id);
    const name = img?.repoTags?.[0] || id?.substring(0, 12);

    const confirmed = await confirmDelete('Image', `<p><strong>${name}</strong></p>`);
    if (!confirmed) return;

    try {
        await removeImage(state.currentHostId, id, true);
        showToast('Image deleted', 'success');
        // Small delay before reloading
        setTimeout(() => loadData().catch(() => {}), 300);
    } catch (error) {
        showToast('Failed to delete image: ' + error.message, 'error');
    }
}

// Handle cleanup dangling images
async function handleCleanup() {
    try {
        const dangling = await getDanglingImages(state.currentHostId);

        if (!dangling || dangling.length === 0) {
            showToast('No dangling images to cleanup', 'info');
            return;
        }

        const totalSize = dangling.reduce((sum, img) => sum + (img.size || 0), 0);

        const confirmed = await showConfirm({
            title: 'Cleanup Dangling Images',
            message: `Delete ${dangling.length} dangling image(s)?`,
            confirmText: 'Cleanup',
            type: 'warning',
            details: `<p>This will free up <strong>${formatBytes(totalSize)}</strong> of disk space.</p>`,
        });

        if (!confirmed) return;

        await cleanupDanglingImages(state.currentHostId);
        showToast('Cleanup complete', 'success');
        // Small delay before reloading
        setTimeout(() => loadData().catch(() => {}), 300);
    } catch (error) {
        showToast('Failed to cleanup: ' + error.message, 'error');
    }
}

// Show details modal
async function showDetailsModal(id) {
    try {
        const details = await getImage(state.currentHostId, id);
        const content = `
            <div class="info-grid">
                <span class="info-label">ID:</span><span class="info-value mono">${details.id?.replace('sha256:', '').substring(0, 12)}</span>
                <span class="info-label">Tags:</span><span class="info-value">${details.repoTags?.join(', ') || 'none'}</span>
                <span class="info-label">Size:</span><span class="info-value">${formatBytes(details.size)}</span>
                <span class="info-label">Created:</span><span class="info-value">${details.created}</span>
                <span class="info-label">Architecture:</span><span class="info-value">${details.architecture || 'N/A'}</span>
                <span class="info-label">OS:</span><span class="info-value">${details.os || 'N/A'}</span>
            </div>
        `;
        openModal('Image Details', content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `);
    } catch (error) {
        showToast('Failed to load image details', 'error');
    }
}

function showEmptyHost() {
    document.getElementById('images-table').innerHTML = `
        <div class="empty-state">
            <span class="material-symbols-outlined">dns</span>
            <h3>No host selected</h3>
        </div>
    `;
}

export function cleanup() {
    images = [];
    usedImageIds.clear();
}
