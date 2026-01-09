// Containers Page
import { state } from '../state.js';
import {
    listContainers, startContainer, stopContainer, restartContainer,
    pauseContainer, unpauseContainer, removeContainer, getContainer,
    execContainer, listNetworks, listImages,
    getContainerHealth, checkContainerRoot, checkContainerPrivileged,
    getContainerStats, deployCompose,
    getHostDrift, checkContainerDrift
} from '../api/docker.js';
import { listTemplates, getTemplate, getCategories } from '../api/templates.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete, showConfirm } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions, formatDate } from '../components/data-table.js';
import { renderContainerStatus, getStatusClass } from '../components/status-badge.js';
import { truncate, formatPorts } from '../utils/format.js';
import { formatBytes } from '../components/charts.js';

let containers = [];
let containerStats = new Map();
let containerDrift = new Map();
let currentView = 'list'; // 'list' or 'create'
let networks = [];
let images = [];
let templates = [];
let categories = [];
let selectedCategory = null;

// Render page
export function render() {
    return `
        <div id="containers-page">
            ${renderListView()}
        </div>
    `;
}

function renderListView() {
    return `
        <div class="section-header">
            <h2 class="section-title">Containers</h2>
            <md-filled-button id="create-container-btn">
                <span class="material-symbols-outlined" slot="icon">add</span>
                Create Container
            </md-filled-button>
        </div>
        <div class="card">
            <div class="card-content" id="containers-table">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>
    `;
}

function renderCreateView() {
    const networkOptions = networks.map(n =>
        `<md-select-option value="${n.name}" ${n.name === 'bridge' ? 'selected' : ''}>${n.name}</md-select-option>`
    ).join('');

    return `
        <div class="section-header">
            <div class="section-header-left">
                <md-icon-button id="back-to-list-btn" title="Back to containers">
                    <span class="material-symbols-outlined">arrow_back</span>
                </md-icon-button>
                <h2 class="section-title">Create Container</h2>
            </div>
        </div>

        <div class="create-container-page">
            <div class="create-tabs-nav">
                <button class="create-tab-btn active" data-tab="templates">
                    <span class="material-symbols-outlined">apps</span>
                    Templates
                </button>
                <button class="create-tab-btn" data-tab="single">
                    <span class="material-symbols-outlined">deployed_code</span>
                    Custom Container
                </button>
                <button class="create-tab-btn" data-tab="compose">
                    <span class="material-symbols-outlined">stacks</span>
                    Docker Compose
                </button>
            </div>

            <div class="create-content">
                <!-- Templates Tab -->
                <div class="create-tab-panel" id="panel-templates">
                    <div class="templates-header">
                        <div class="templates-search">
                            <md-filled-text-field id="template-search" label="Search templates" type="search" style="width: 300px;">
                                <span class="material-symbols-outlined" slot="leading-icon">search</span>
                            </md-filled-text-field>
                        </div>
                        <div class="templates-categories" id="template-categories">
                            <button class="category-chip active" data-category="">All</button>
                        </div>
                    </div>
                    <div class="templates-grid" id="templates-grid">
                        <div class="loading-container">
                            <md-circular-progress indeterminate></md-circular-progress>
                        </div>
                    </div>
                </div>

                <!-- Single Container Tab -->
                <div class="create-tab-panel hidden" id="panel-single">
                    <div class="card">
                        <div class="card-header">
                            <span class="card-title">Container Configuration</span>
                        </div>
                        <div class="card-content">
                            <form id="create-container-form" class="create-form">
                                <div class="form-row">
                                    <div class="form-field">
                                        <md-filled-text-field id="container-image" label="Image *" placeholder="nginx:latest" required style="width: 100%;"></md-filled-text-field>
                                        <span class="form-hint">Docker image to use (e.g., nginx:latest, postgres:15)</span>
                                    </div>
                                </div>
                                <div class="form-row">
                                    <div class="form-field">
                                        <md-filled-text-field id="container-name" label="Container Name" placeholder="my-container" style="width: 100%;"></md-filled-text-field>
                                        <span class="form-hint">Optional. Leave empty for auto-generated name</span>
                                    </div>
                                </div>
                                <div class="form-row">
                                    <div class="form-field">
                                        <md-filled-text-field id="container-env" label="Environment Variables" placeholder="KEY=value,KEY2=value2" style="width: 100%;"></md-filled-text-field>
                                        <span class="form-hint">Comma-separated KEY=value pairs</span>
                                    </div>
                                </div>
                                <div class="form-row two-col">
                                    <div class="form-field">
                                        <md-filled-text-field id="container-ports" label="Port Bindings" placeholder="8080:80,443:443" style="width: 100%;"></md-filled-text-field>
                                        <span class="form-hint">host:container format</span>
                                    </div>
                                    <div class="form-field">
                                        <md-filled-select id="container-network" label="Network" style="width: 100%;">
                                            ${networkOptions}
                                        </md-filled-select>
                                    </div>
                                </div>
                                <div class="form-row">
                                    <div class="form-field">
                                        <md-filled-text-field id="container-volumes" label="Volume Bindings" placeholder="/host/path:/container/path" style="width: 100%;"></md-filled-text-field>
                                        <span class="form-hint">Comma-separated host:container paths</span>
                                    </div>
                                </div>
                                <div class="form-row">
                                    <div class="form-field">
                                        <md-filled-text-field id="container-user" label="Run as User" placeholder="1000:1000" style="width: 100%;"></md-filled-text-field>
                                        <span class="form-hint">UID or UID:GID to run as non-root (e.g., "1000", "1000:1000", "nobody"). Leave empty for default (root)</span>
                                    </div>
                                </div>
                                <div class="form-actions">
                                    <md-outlined-button type="button" id="cancel-create-btn">Cancel</md-outlined-button>
                                    <md-filled-button type="submit" id="submit-container-btn">
                                        <span class="material-symbols-outlined" slot="icon">add</span>
                                        Create Container
                                    </md-filled-button>
                                </div>
                            </form>
                        </div>
                    </div>
                </div>

                <!-- Docker Compose Tab -->
                <div class="create-tab-panel hidden" id="panel-compose">
                    <div class="card">
                        <div class="card-header">
                            <span class="card-title">Docker Compose Deployment</span>
                        </div>
                        <div class="card-content">
                            <form id="compose-form" class="create-form">
                                <div class="form-row">
                                    <div class="form-field">
                                        <md-filled-text-field id="compose-project" label="Project Name (optional)" placeholder="my-project" style="width: 100%;"></md-filled-text-field>
                                        <span class="form-hint">Used as prefix for container names</span>
                                    </div>
                                </div>

                                <div class="form-row">
                                    <div class="form-field">
                                        <div id="compose-dropzone" class="dropzone">
                                            <span class="material-symbols-outlined">upload_file</span>
                                            <p>Drag & drop docker-compose.yml here</p>
                                            <p class="dropzone-hint">or click to browse</p>
                                            <input type="file" id="compose-file" accept=".yml,.yaml" style="display: none;">
                                        </div>
                                    </div>
                                </div>

                                <div class="form-row">
                                    <div class="form-field yaml-field">
                                        <label class="yaml-label">Docker Compose YAML</label>
                                        <textarea id="compose-content" class="compose-editor" placeholder="version: '3.8'
services:
  web:
    image: nginx:latest
    ports:
      - '80:80'

  db:
    image: postgres:15
    environment:
      POSTGRES_PASSWORD: secret
    volumes:
      - db_data:/var/lib/postgresql/data

volumes:
  db_data:"></textarea>
                                        <span class="form-hint">Paste or edit your docker-compose.yml content</span>
                                    </div>
                                </div>

                                <div class="form-actions">
                                    <md-outlined-button type="button" id="cancel-compose-btn">Cancel</md-outlined-button>
                                    <md-filled-button type="submit" id="submit-compose-btn">
                                        <span class="material-symbols-outlined" slot="icon">rocket_launch</span>
                                        Deploy Stack
                                    </md-filled-button>
                                </div>
                            </form>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    `;
}

// Initialize
export async function init() {
    // Check if we were redirected from templates page with a template to use
    const selectedTemplateId = sessionStorage.getItem('selectedTemplateId');
    if (selectedTemplateId) {
        sessionStorage.removeItem('selectedTemplateId');
        await showCreateView();
        try {
            const template = await getTemplate(selectedTemplateId);
            selectTemplate(template);
        } catch (e) {
            showToast('Failed to load template', 'error');
        }
        return;
    }

    currentView = 'list';
    await initListView();
}

async function initListView() {
    document.getElementById('create-container-btn')?.addEventListener('click', showCreateView);

    setupTableActions('containers-table', {
        start: handleStart,
        stop: handleStop,
        restart: handleRestart,
        pause: handlePause,
        unpause: handleUnpause,
        exec: showExecModal,
        details: showDetailsModal,
        delete: handleDelete,
        checkDrift: handleCheckDrift,
        viewDrift: showDriftModal,
    });

    await loadData();
}

async function showCreateView() {
    currentView = 'create';

    // Pre-fetch networks, images, templates, and categories
    try {
        [images, networks, templates, categories] = await Promise.all([
            listImages(state.currentHostId).catch(() => []),
            listNetworks(state.currentHostId).catch(() => []),
            listTemplates().catch(() => []),
            getCategories().catch(() => [])
        ]);
    } catch (e) { /* ignore */ }

    const page = document.getElementById('containers-page');
    page.innerHTML = renderCreateView();

    // Setup event listeners
    setupCreateViewListeners();

    // Render templates and categories
    renderTemplateCategories();
    renderTemplatesGrid();
}

function showListView() {
    currentView = 'list';
    const page = document.getElementById('containers-page');
    page.innerHTML = renderListView();
    initListView();
}

function setupCreateViewListeners() {
    // Back button
    document.getElementById('back-to-list-btn')?.addEventListener('click', showListView);
    document.getElementById('cancel-create-btn')?.addEventListener('click', showListView);
    document.getElementById('cancel-compose-btn')?.addEventListener('click', showListView);

    // Tab switching
    document.querySelectorAll('.create-tab-btn').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.create-tab-btn').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.create-tab-panel').forEach(p => p.classList.add('hidden'));
            tab.classList.add('active');
            const tabName = tab.dataset.tab;
            document.getElementById(`panel-${tabName}`).classList.remove('hidden');
        });
    });

    // Template search
    const searchInput = document.getElementById('template-search');
    let searchTimeout;
    searchInput?.addEventListener('input', (e) => {
        clearTimeout(searchTimeout);
        searchTimeout = setTimeout(() => {
            renderTemplatesGrid(e.target.value);
        }, 300);
    });

    // Form submissions
    document.getElementById('create-container-form')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        await handleCreate();
    });

    document.getElementById('compose-form')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        await handleComposeDeploy();
    });

    // Dropzone
    setupComposeDropzone();
}

// Load data
async function loadData() {
    if (!state.currentHostId) {
        showEmptyHost();
        return;
    }

    try {
        // Fetch containers, stats, and drift status in parallel
        const [containerList, stats, drift] = await Promise.all([
            listContainers(state.currentHostId, true),
            getContainerStats(state.currentHostId).catch(() => []),
            getHostDrift(state.currentHostId).catch(() => [])
        ]);

        containers = containerList;

        // Build stats map for quick lookup
        containerStats.clear();
        stats.forEach(s => containerStats.set(s.containerId, s));

        // Build drift map for quick lookup
        containerDrift.clear();
        drift.forEach(d => containerDrift.set(d.containerId, d));

        renderContainersTable();
    } catch (error) {
        console.error('Failed to load containers:', error);
        showToast('Failed to load containers', 'error');
    }
}

// Render drift status badge
function renderDriftStatus(containerId) {
    const drift = containerDrift.get(containerId);
    if (!drift) {
        return '<span class="status-badge created" title="Drift: Not checked">-</span>';
    }

    const statusMap = {
        SYNCED: { class: 'running', text: 'Synced', icon: 'check_circle' },
        BEHIND: { class: 'warning', text: 'Drifted', icon: 'warning' },
        UNKNOWN: { class: 'created', text: 'Unknown', icon: 'help' },
        ERROR: { class: 'stopped', text: 'Error', icon: 'error' },
    };

    const status = drift.configDriftStatus || 'UNKNOWN';
    const s = statusMap[status] || statusMap.UNKNOWN;

    return `<span class="status-badge ${s.class}" title="Click 'View Drift' for details">
        <span class="material-symbols-outlined" style="font-size: 14px; margin-right: 2px;">${s.icon}</span>
        ${s.text}
    </span>`;
}

// Render table
function renderContainersTable() {
    const container = document.getElementById('containers-table');

    const columns = [
        { key: 'names', label: 'Name', render: (names) => names?.[0]?.replace(/^\//, '') || 'N/A' },
        { key: 'image', label: 'Image', truncate: true, render: (v) => truncate(v, 25) },
        { key: 'state', label: 'Status', render: (state) => renderContainerStatus({ state }) },
        { key: 'id', label: 'Drift', render: (id) => renderDriftStatus(id) },
        { key: 'id', label: 'CPU', render: (id) => renderCpuBar(containerStats.get(id)) },
        { key: 'id', label: 'Memory', render: (id) => renderMemoryBar(containerStats.get(id)) },
        { key: 'ports', label: 'Ports', render: formatPorts },
    ];

    const actions = (c) => {
        const isRunning = c.state === 'running';
        const isPaused = c.state === 'paused';

        const hasDrift = containerDrift.get(c.id)?.configDriftStatus === 'BEHIND';

        return renderActions([
            { icon: 'play_arrow', title: 'Start', action: 'start', hidden: isRunning },
            { icon: 'stop', title: 'Stop', action: 'stop', hidden: !isRunning },
            { icon: 'restart_alt', title: 'Restart', action: 'restart', hidden: !isRunning },
            { icon: 'pause', title: 'Pause', action: 'pause', hidden: !isRunning || isPaused },
            { icon: 'play_arrow', title: 'Unpause', action: 'unpause', hidden: !isPaused },
            { icon: 'terminal', title: 'Execute', action: 'exec', hidden: !isRunning },
            { icon: 'compare_arrows', title: 'Check Drift', action: 'checkDrift' },
            { icon: 'difference', title: 'View Drift', action: 'viewDrift', hidden: !hasDrift, color: 'var(--status-created)' },
            { icon: 'info', title: 'Details', action: 'details' },
            { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
        ]);
    };

    container.innerHTML = renderTable({
        columns,
        data: containers,
        actions,
        emptyMessage: 'No containers found',
        emptyIcon: 'inventory_2',
    });
}

// Setup drag and drop for compose file
function setupComposeDropzone() {
    const dropzone = document.getElementById('compose-dropzone');
    const fileInput = document.getElementById('compose-file');
    const contentArea = document.getElementById('compose-content');

    if (!dropzone || !fileInput || !contentArea) return;

    // Click to browse
    dropzone.addEventListener('click', () => fileInput.click());

    // File input change
    fileInput.addEventListener('change', (e) => {
        const file = e.target.files?.[0];
        if (file) readComposeFile(file, contentArea, dropzone);
    });

    // Drag events
    dropzone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropzone.classList.add('dragover');
    });

    dropzone.addEventListener('dragleave', () => {
        dropzone.classList.remove('dragover');
    });

    dropzone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropzone.classList.remove('dragover');
        const file = e.dataTransfer?.files?.[0];
        if (file) readComposeFile(file, contentArea, dropzone);
    });
}

// Read compose file
function readComposeFile(file, contentArea, dropzone) {
    if (!file.name.match(/\.(yml|yaml)$/i)) {
        showToast('Please select a YAML file', 'error');
        return;
    }

    const reader = new FileReader();
    reader.onload = (e) => {
        contentArea.value = e.target.result;
        dropzone.innerHTML = `
            <span class="material-symbols-outlined">check_circle</span>
            <p>File loaded: ${file.name}</p>
            <p class="dropzone-hint">Click to replace</p>
        `;
        dropzone.classList.add('file-loaded');
    };
    reader.onerror = () => showToast('Failed to read file', 'error');
    reader.readAsText(file);
}

// Handle compose deployment
async function handleComposeDeploy() {
    const content = document.getElementById('compose-content')?.value?.trim();
    const projectName = document.getElementById('compose-project')?.value?.trim();
    const submitBtn = document.getElementById('submit-compose-btn');

    if (!content) {
        showToast('Please provide docker-compose.yml content', 'error');
        return;
    }

    // Show loading state
    if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<md-circular-progress indeterminate style="--md-circular-progress-size: 20px;"></md-circular-progress> Deploying...';
    }

    try {
        const result = await deployCompose(state.currentHostId, content, projectName || null);

        // Check for success - handle both direct boolean and object with success property
        const isSuccess = result === true || result?.success === true;

        if (isSuccess) {
            showToast('Compose deployment successful', 'success');
            // Go back to list view and reload
            showListView();
        } else {
            showToast('Deployment failed: ' + (result?.error || 'Unknown error'), 'error');
        }
    } catch (error) {
        showToast('Failed to deploy: ' + error.message, 'error');
    } finally {
        // Reset button state
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<span class="material-symbols-outlined" slot="icon">rocket_launch</span> Deploy Stack';
        }
    }
}

// Handle create
async function handleCreate() {
    const image = document.getElementById('container-image')?.value?.trim();
    const name = document.getElementById('container-name')?.value?.trim();
    const env = document.getElementById('container-env')?.value?.trim();
    const ports = document.getElementById('container-ports')?.value?.trim();
    const volumes = document.getElementById('container-volumes')?.value?.trim();
    const network = document.getElementById('container-network')?.value;
    const user = document.getElementById('container-user')?.value?.trim();
    const submitBtn = document.getElementById('submit-container-btn');

    if (!image) {
        showToast('Image is required', 'error');
        return;
    }

    const config = {
        imageName: image,
        containerName: name || undefined,
        environmentVariables: env ? env.split(',').map(e => e.trim()) : [],
        portBindings: ports ? parsePorts(ports) : {},
        volumeBindings: volumes ? volumes.split(',').map(v => v.trim()) : [],
        networkName: network || 'bridge',
        user: user || undefined,
    };

    // Show loading state
    if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<md-circular-progress indeterminate style="--md-circular-progress-size: 20px;"></md-circular-progress> Creating...';
    }

    try {
        const { createContainer } = await import('../api/docker.js');
        await createContainer(state.currentHostId, config);
        showToast('Container created', 'success');
        showListView();
    } catch (error) {
        showToast('Failed to create container: ' + error.message, 'error');
        // Reset button state
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<span class="material-symbols-outlined" slot="icon">add</span> Create Container';
        }
    }
}

// Parse ports string to Map<containerPort, hostPort>
function parsePorts(portsStr) {
    const ports = {};
    portsStr.split(',').forEach(p => {
        const [host, container] = p.trim().split(':');
        if (host && container) {
            // Backend expects { containerPort: hostPort }
            ports[parseInt(container, 10)] = parseInt(host, 10);
        }
    });
    return ports;
}

// Container actions
async function handleStart(id) {
    try {
        await startContainer(state.currentHostId, id);
        showToast('Container started', 'success');
        await loadData();
    } catch (e) { showToast('Failed: ' + e.message, 'error'); }
}

async function handleStop(id) {
    try {
        await stopContainer(state.currentHostId, id);
        showToast('Container stopped', 'success');
        await loadData();
    } catch (e) { showToast('Failed: ' + e.message, 'error'); }
}

async function handleRestart(id) {
    try {
        await restartContainer(state.currentHostId, id);
        showToast('Container restarted', 'success');
        await loadData();
    } catch (e) { showToast('Failed: ' + e.message, 'error'); }
}

async function handlePause(id) {
    try {
        await pauseContainer(state.currentHostId, id);
        showToast('Container paused', 'success');
        await loadData();
    } catch (e) { showToast('Failed: ' + e.message, 'error'); }
}

async function handleUnpause(id) {
    try {
        await unpauseContainer(state.currentHostId, id);
        showToast('Container unpaused', 'success');
        await loadData();
    } catch (e) { showToast('Failed: ' + e.message, 'error'); }
}

async function handleCheckDrift(id) {
    try {
        showToast('Checking drift...', 'info');
        const result = await checkContainerDrift(state.currentHostId, id);
        containerDrift.set(id, result);
        renderContainersTable();
        const status = result.configDriftStatus || 'UNKNOWN';
        if (status === 'SYNCED') {
            showToast('Container is in sync', 'success');
        } else if (status === 'BEHIND') {
            showToast('Drift detected - container has changed', 'warning');
        } else {
            showToast('Drift check complete', 'info');
        }
    } catch (e) {
        showToast('Failed to check drift: ' + e.message, 'error');
    }
}

// Show drift details modal
function showDriftModal(id) {
    const drift = containerDrift.get(id);
    const c = containers.find(x => x.id === id);
    const containerName = c?.names?.[0]?.replace(/^\//, '') || id.substring(0, 12);

    if (!drift) {
        showToast('No drift data available. Run Check Drift first.', 'info');
        return;
    }

    // Parse drift details
    let driftDetails = [];
    if (drift.driftDetails) {
        try {
            driftDetails = JSON.parse(drift.driftDetails);
        } catch (e) {
            driftDetails = [{ type: 'unknown', description: drift.driftDetails, details: null }];
        }
    }

    const driftTypeIcons = {
        environment: 'settings',
        image: 'image',
        volumes: 'folder',
        ports: 'lan',
    };

    const driftTypeDescriptions = {
        environment: 'Environment variables have changed since deployment.',
        image: 'The container is running a different image than expected.',
        volumes: 'Volume mount configuration has changed.',
        ports: 'Port binding configuration has changed.',
    };

    const driftTypeFixes = {
        environment: 'Recreate the container with the original environment variables, or update the deployment to match current state.',
        image: 'Pull the expected image and recreate the container, or update the deployment record.',
        volumes: 'Recreate the container with correct volume mounts.',
        ports: 'Recreate the container with correct port bindings.',
    };

    let detailsHtml = '';
    if (driftDetails.length === 0) {
        detailsHtml = '<p class="text-muted">No specific drift details available.</p>';
    } else {
        detailsHtml = driftDetails.map(d => {
            const icon = driftTypeIcons[d.type] || 'change_circle';
            const explanation = driftTypeDescriptions[d.type] || 'Configuration has changed.';
            const fix = driftTypeFixes[d.type] || 'Recreate the container to reset to expected state.';

            return `
                <div class="drift-item">
                    <div class="drift-item-header">
                        <span class="material-symbols-outlined" style="color: var(--status-created);">${icon}</span>
                        <strong>${d.description || d.type}</strong>
                    </div>
                    ${d.details ? `<p class="drift-item-details mono">${d.details}</p>` : ''}
                    <p class="drift-item-explanation">${explanation}</p>
                    <p class="drift-item-fix"><strong>How to fix:</strong> ${fix}</p>
                </div>
            `;
        }).join('');
    }

    const content = `
        <style>
            .drift-summary {
                background: var(--surface-variant);
                padding: 12px 16px;
                border-radius: 8px;
                margin-bottom: 16px;
            }
            .drift-summary-row {
                display: flex;
                justify-content: space-between;
                margin-bottom: 4px;
            }
            .drift-summary-row:last-child {
                margin-bottom: 0;
            }
            .drift-item {
                background: var(--surface);
                border: 1px solid var(--outline-variant);
                border-radius: 8px;
                padding: 16px;
                margin-bottom: 12px;
            }
            .drift-item:last-child {
                margin-bottom: 0;
            }
            .drift-item-header {
                display: flex;
                align-items: center;
                gap: 8px;
                margin-bottom: 8px;
            }
            .drift-item-details {
                background: var(--surface-variant);
                padding: 8px 12px;
                border-radius: 4px;
                font-size: 13px;
                margin: 8px 0;
            }
            .drift-item-explanation {
                color: var(--on-surface-variant);
                font-size: 14px;
                margin: 8px 0;
            }
            .drift-item-fix {
                font-size: 14px;
                margin: 8px 0 0 0;
                padding: 8px 12px;
                background: rgba(var(--primary-rgb), 0.1);
                border-radius: 4px;
            }
            .drift-general-fix {
                margin-top: 16px;
                padding: 16px;
                background: var(--surface-variant);
                border-radius: 8px;
            }
            .drift-general-fix h4 {
                margin: 0 0 8px 0;
                display: flex;
                align-items: center;
                gap: 8px;
            }
        </style>

        <div class="drift-summary">
            <div class="drift-summary-row">
                <span>Container:</span>
                <strong>${containerName}</strong>
            </div>
            <div class="drift-summary-row">
                <span>Image:</span>
                <span class="mono">${drift.imageName || 'N/A'}</span>
            </div>
            <div class="drift-summary-row">
                <span>Last Checked:</span>
                <span>${drift.lastCheckedAt ? formatDate(drift.lastCheckedAt) : 'Never'}</span>
            </div>
        </div>

        <h4 style="margin: 0 0 12px 0;">Changes Detected</h4>
        ${detailsHtml}

        <div class="drift-general-fix">
            <h4>
                <span class="material-symbols-outlined">lightbulb</span>
                General Resolution
            </h4>
            <p style="margin: 0;">
                Container drift means the running container has changed from its original deployment state.
                To resolve drift, you can either:
            </p>
            <ul style="margin: 8px 0 0 0; padding-left: 20px;">
                <li><strong>Recreate the container</strong> - Delete and redeploy to reset to expected state</li>
                <li><strong>Accept the changes</strong> - If the changes are intentional, redeploy to capture new state as baseline</li>
            </ul>
        </div>
    `;

    openModal('Drift Details', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Close</md-text-button>
    `);
}

async function handleDelete(id) {
    const c = containers.find(x => x.id === id);
    const containerState = c?.state?.toLowerCase() || '';
    const containerName = c?.names?.[0]?.replace(/^\//, '') || id.substring(0, 12);

    // Check if container needs force delete (any non-stopped state)
    const needsForce = ['running', 'restarting', 'paused', 'removing'].includes(containerState);

    const stateWarning = needsForce
        ? `<p style="margin-top: 8px; color: var(--status-paused);">Container is <strong>${containerState}</strong> and will be force deleted.</p>`
        : '';

    const confirmed = await showConfirm({
        title: 'Delete Container',
        message: `Delete container "${containerName}"?`,
        confirmText: needsForce ? 'Force Delete' : 'Delete',
        type: 'danger',
        details: stateWarning,
    });

    if (!confirmed) return;

    try {
        await removeContainer(state.currentHostId, id, needsForce);
        showToast('Container deleted', 'success');
        await loadData();
    } catch (e) { showToast('Failed: ' + e.message, 'error'); }
}

// Show exec modal
function showExecModal(id) {
    const content = `
        <div class="dialog-form">
            <md-filled-text-field id="exec-command" label="Command" value="ls -la" placeholder="e.g., ls -la, cat /etc/hosts" style="width: 100%;"></md-filled-text-field>
            <div id="exec-output" class="terminal" style="margin-top: 16px; min-height: 200px; display: none;"></div>
        </div>
    `;

    openModal('Execute Command', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Close</md-text-button>
        <md-filled-button id="exec-run">Run</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('exec-run')?.addEventListener('click', () => runExec(id));
    }, 100);
}

async function runExec(id) {
    const command = document.getElementById('exec-command')?.value;
    const output = document.getElementById('exec-output');

    if (!command) return;

    output.style.display = 'block';
    output.textContent = 'Running...';

    try {
        const result = await execContainer(state.currentHostId, id, command);
        output.textContent = result.output || '(no output)';
    } catch (e) {
        output.textContent = 'Error: ' + e.message;
    }
}

// Show details modal
async function showDetailsModal(id) {
    try {
        // Fetch all container info in parallel
        const [info, health, rootCheck, privCheck] = await Promise.all([
            getContainer(state.currentHostId, id),
            getContainerHealth(state.currentHostId, id).catch(() => null),
            checkContainerRoot(state.currentHostId, id).catch(() => null),
            checkContainerPrivileged(state.currentHostId, id).catch(() => null),
        ]);

        console.log('Container details:', { info, health, rootCheck, privCheck });

        // Extract data from response
        const containerId = info.id || '';
        const containerName = (info.name || '').replace(/^\//, '');
        const imageName = info.config?.image || info.image || 'N/A';
        const containerState = info.state?.status || 'unknown';
        const createdAt = info.created ? formatDate(new Date(info.created).getTime()) : 'N/A';
        const healthStatus = health?.status || 'No health check';

        // Network info
        const ipAddress = info.networkSettings?.iPAddress || info.networkSettings?.ipAddress || 'N/A';
        const gateway = info.networkSettings?.gateway || 'N/A';
        const macAddress = info.networkSettings?.macAddress || 'N/A';
        const ports = info.networkSettings?.ports;

        // Environment variables
        const envVars = info.config?.env || [];

        // Security info
        const isRoot = rootCheck?.runningAsRoot;
        const isPrivileged = privCheck?.privileged;
        const user = info.config?.user || 'root (default)';

        const content = `
            <div class="details-tabs">
                <button class="details-tab active" data-tab="general">General</button>
                <button class="details-tab" data-tab="network">Network</button>
                <button class="details-tab" data-tab="security">Security</button>
            </div>

            <div class="details-panel" id="panel-general">
                <div class="info-grid">
                    <span class="info-label">Container ID</span>
                    <span class="info-value mono">${containerId}</span>
                    <span class="info-label">Name</span>
                    <span class="info-value">${containerName}</span>
                    <span class="info-label">Image</span>
                    <span class="info-value">${imageName}</span>
                    <span class="info-label">Status</span>
                    <span class="info-value">${renderContainerStatus({ state: containerState })}</span>
                    <span class="info-label">Created</span>
                    <span class="info-value">${createdAt}</span>
                    <span class="info-label">Health</span>
                    <span class="info-value">${healthStatus}</span>
                </div>
                ${envVars.length > 0 ? `
                    <h4 class="details-section-title">Environment Variables</h4>
                    <div class="terminal"><pre>${envVars.join('\n')}</pre></div>
                ` : ''}
            </div>

            <div class="details-panel hidden" id="panel-network">
                <div class="info-grid">
                    <span class="info-label">IP Address</span>
                    <span class="info-value mono">${ipAddress}</span>
                    <span class="info-label">Gateway</span>
                    <span class="info-value mono">${gateway}</span>
                    <span class="info-label">MAC Address</span>
                    <span class="info-value mono">${macAddress}</span>
                </div>
                ${ports ? `
                    <h4 class="details-section-title">Port Bindings</h4>
                    <div class="terminal"><pre>${JSON.stringify(ports, null, 2)}</pre></div>
                ` : ''}
            </div>

            <div class="details-panel hidden" id="panel-security">
                <div class="info-grid">
                    <span class="info-label">Running as Root</span>
                    <span class="info-value">
                        ${isRoot === true ? '<span class="status-badge stopped">Yes</span>' :
                          isRoot === false ? '<span class="status-badge running">No</span>' : 'Unknown'}
                    </span>
                    <span class="info-label">Privileged Mode</span>
                    <span class="info-value">
                        ${isPrivileged === true ? '<span class="status-badge stopped">Yes</span>' :
                          isPrivileged === false ? '<span class="status-badge running">No</span>' : 'Unknown'}
                    </span>
                    <span class="info-label">User</span>
                    <span class="info-value">${user}</span>
                </div>
            </div>
        `;

        openModal('Container Details', content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `);

        // Setup tab switching
        setTimeout(() => {
            document.querySelectorAll('.details-tab').forEach(tab => {
                tab.addEventListener('click', () => {
                    document.querySelectorAll('.details-tab').forEach(t => t.classList.remove('active'));
                    document.querySelectorAll('.details-panel').forEach(p => p.classList.add('hidden'));
                    tab.classList.add('active');
                    document.getElementById(`panel-${tab.dataset.tab}`).classList.remove('hidden');
                });
            });
        }, 50);

    } catch (e) {
        console.error('Failed to load container details:', e);
        showToast('Failed to load details', 'error');
    }
}

function showEmptyHost() {
    document.getElementById('containers-table').innerHTML = `
        <div class="empty-state">
            <span class="material-symbols-outlined">dns</span>
            <h3>No host selected</h3>
        </div>
    `;
}

// Render CPU usage bar
function renderCpuBar(stats) {
    if (!stats || stats.cpuPercent === undefined) {
        return '<span class="text-muted">-</span>';
    }

    const percent = Math.min(100, stats.cpuPercent).toFixed(1);
    const color = percent > 80 ? 'var(--status-exited)' : percent > 50 ? 'var(--status-created)' : 'var(--status-running)';

    return `
        <div class="resource-bar">
            <div class="resource-bar-fill" style="width: ${Math.min(100, percent)}%; background: ${color};"></div>
            <span class="resource-bar-label">${percent}%</span>
        </div>
    `;
}

// Render memory usage bar
function renderMemoryBar(stats) {
    if (!stats || stats.memoryPercent === undefined) {
        return '<span class="text-muted">-</span>';
    }

    const percent = Math.min(100, stats.memoryPercent).toFixed(1);
    const color = percent > 80 ? 'var(--status-exited)' : percent > 50 ? 'var(--status-created)' : 'var(--status-running)';
    const usage = formatBytes(stats.memoryUsage || 0);

    return `
        <div class="resource-bar" title="${usage}">
            <div class="resource-bar-fill" style="width: ${Math.min(100, percent)}%; background: ${color};"></div>
            <span class="resource-bar-label">${percent}%</span>
        </div>
    `;
}

// ==================== Template Functions ====================

function renderTemplateCategories() {
    const container = document.getElementById('template-categories');
    if (!container) return;

    const chips = ['All', ...categories].map(cat => {
        const catValue = cat === 'All' ? '' : cat;
        const isActive = selectedCategory === catValue || (selectedCategory === null && cat === 'All');
        return `<button class="category-chip ${isActive ? 'active' : ''}" data-category="${catValue}">${cat}</button>`;
    }).join('');

    container.innerHTML = chips;

    // Add click handlers
    container.querySelectorAll('.category-chip').forEach(chip => {
        chip.addEventListener('click', () => {
            selectedCategory = chip.dataset.category || null;
            container.querySelectorAll('.category-chip').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            renderTemplatesGrid();
        });
    });
}

function renderTemplatesGrid(searchQuery = '') {
    const container = document.getElementById('templates-grid');
    if (!container) return;

    let filteredTemplates = templates;

    // Filter by category
    if (selectedCategory) {
        filteredTemplates = filteredTemplates.filter(t => t.category === selectedCategory);
    }

    // Filter by search
    if (searchQuery) {
        const query = searchQuery.toLowerCase();
        filteredTemplates = filteredTemplates.filter(t =>
            t.name?.toLowerCase().includes(query) ||
            t.description?.toLowerCase().includes(query) ||
            t.category?.toLowerCase().includes(query)
        );
    }

    if (filteredTemplates.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <span class="material-symbols-outlined">search_off</span>
                <h3>No templates found</h3>
                <p>Try a different search term or category</p>
            </div>
        `;
        return;
    }

    const cards = filteredTemplates.map(t => renderTemplateCard(t)).join('');
    container.innerHTML = cards;

    // Add click handlers
    container.querySelectorAll('.template-card').forEach(card => {
        card.addEventListener('click', () => {
            const templateId = card.dataset.templateId;
            const template = templates.find(t => t.id === templateId);
            if (template) {
                selectTemplate(template);
            }
        });
    });
}

function renderTemplateCard(template) {
    const isCompose = template.type === 'COMPOSE';
    const typeIcon = isCompose ? 'stacks' : 'deployed_code';
    const typeLabel = isCompose ? 'Stack' : 'Container';

    return `
        <div class="template-card" data-template-id="${template.id}">
            <div class="template-card-header">
                ${template.logo
                    ? `<img src="${template.logo}" alt="" class="template-logo" onerror="this.style.display='none'">`
                    : `<span class="material-symbols-outlined template-icon">${isCompose ? 'widgets' : 'deployed_code'}</span>`
                }
                <div class="template-card-meta">
                    <span class="template-type ${isCompose ? 'compose' : 'container'}">
                        <span class="material-symbols-outlined">${typeIcon}</span>
                        ${typeLabel}
                    </span>
                </div>
            </div>
            <h3 class="template-card-title">${template.name}</h3>
            <p class="template-card-description">${template.description || ''}</p>
            <div class="template-card-footer">
                <span class="template-category">${template.category}</span>
                ${template.system ? '<span class="template-badge system">Built-in</span>' : '<span class="template-badge user">Custom</span>'}
            </div>
        </div>
    `;
}

function selectTemplate(template) {
    if (template.type === 'COMPOSE') {
        // Switch to Compose tab and fill in the content
        document.querySelectorAll('.create-tab-btn').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.create-tab-panel').forEach(p => p.classList.add('hidden'));
        document.querySelector('.create-tab-btn[data-tab="compose"]').classList.add('active');
        document.getElementById('panel-compose').classList.remove('hidden');

        // Fill in compose content
        const contentField = document.getElementById('compose-content');
        if (contentField) {
            contentField.value = template.composeContent || '';
        }

        // Fill in project name from template name
        const projectField = document.getElementById('compose-project');
        if (projectField && !projectField.value) {
            projectField.value = template.name.toLowerCase().replace(/[^a-z0-9]/g, '-');
        }

        showToast(`Loaded "${template.name}" template`, 'success');
    } else {
        // Switch to Single Container tab and fill in the fields
        document.querySelectorAll('.create-tab-btn').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.create-tab-panel').forEach(p => p.classList.add('hidden'));
        document.querySelector('.create-tab-btn[data-tab="single"]').classList.add('active');
        document.getElementById('panel-single').classList.remove('hidden');

        // Fill in container fields
        const imageField = document.getElementById('container-image');
        const nameField = document.getElementById('container-name');
        const envField = document.getElementById('container-env');
        const portsField = document.getElementById('container-ports');
        const volumesField = document.getElementById('container-volumes');
        const userField = document.getElementById('container-user');

        if (imageField) imageField.value = template.imageName || '';
        if (nameField && !nameField.value) {
            nameField.value = template.name.toLowerCase().replace(/[^a-z0-9]/g, '-');
        }
        if (envField) envField.value = template.defaultEnv || '';
        if (portsField) portsField.value = template.defaultPorts || '';
        if (volumesField) volumesField.value = template.defaultVolumes || '';
        if (userField) userField.value = template.defaultUser || '';

        showToast(`Loaded "${template.name}" template`, 'success');
    }
}

export function cleanup() {
    containers = [];
    containerStats.clear();
    containerDrift.clear();
    currentView = 'list';
    templates = [];
    categories = [];
    selectedCategory = null;
}
