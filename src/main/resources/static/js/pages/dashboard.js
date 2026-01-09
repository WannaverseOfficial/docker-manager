// Dashboard Page - Enhanced with Charts and Host Info
import { state } from '../state.js';
import { getDashboardStats, getHostInfo, getContainerStats } from '../api/docker.js';
import { showToast } from '../components/toast.js';
import { renderContainerStatus } from '../components/status-badge.js';
import { formatDate, truncate } from '../utils/format.js';
import { navigateTo } from '../router.js';
import { initCharts, updateCharts, destroyCharts, formatBytes, formatMemory } from '../components/charts.js';

// Auto-refresh interval (5 seconds)
const REFRESH_INTERVAL = 5000;

// Store interval ID for cleanup
let refreshIntervalId = null;

// Store previous stats for rate calculations
let previousStats = null;

// Render dashboard page
export function render() {
    return `
        <!-- Host Info Card -->
        <div class="host-info-card card" id="host-info-card">
            <div class="card-header">
                <h3 class="card-title">
                    <span class="material-symbols-outlined">dns</span>
                    Host Information
                </h3>
            </div>
            <div class="card-content">
                <div class="host-info-grid" id="host-info-grid">
                    <div class="host-info-loading">
                        <md-circular-progress indeterminate></md-circular-progress>
                    </div>
                </div>
            </div>
        </div>

        <!-- Stats Grid -->
        <div class="stats-grid" id="stats-grid">
            <div class="stat-card">
                <div class="stat-icon">
                    <span class="material-symbols-outlined">inventory_2</span>
                </div>
                <div class="stat-content">
                    <div class="stat-value" id="stat-containers">-</div>
                    <div class="stat-label">Total Containers</div>
                </div>
            </div>
            <div class="stat-card">
                <div class="stat-icon" style="background: color-mix(in srgb, var(--status-running) 20%, transparent);">
                    <span class="material-symbols-outlined" style="color: var(--status-running);">play_circle</span>
                </div>
                <div class="stat-content">
                    <div class="stat-value" id="stat-running">-</div>
                    <div class="stat-label">Running</div>
                </div>
            </div>
            <div class="stat-card">
                <div class="stat-icon" style="background: color-mix(in srgb, var(--status-created) 20%, transparent);">
                    <span class="material-symbols-outlined" style="color: var(--status-created);">layers</span>
                </div>
                <div class="stat-content">
                    <div class="stat-value" id="stat-images">-</div>
                    <div class="stat-label">Images</div>
                </div>
            </div>
            <div class="stat-card">
                <div class="stat-icon" style="background: color-mix(in srgb, var(--status-paused) 20%, transparent);">
                    <span class="material-symbols-outlined" style="color: var(--status-paused);">hub</span>
                </div>
                <div class="stat-content">
                    <div class="stat-value" id="stat-networks">-</div>
                    <div class="stat-label">Networks</div>
                </div>
            </div>
        </div>

        <!-- Charts Section -->
        <div class="charts-section">
            <div class="charts-grid">
                <div class="chart-card card">
                    <div class="card-header">
                        <h3 class="card-title">
                            <span class="material-symbols-outlined">speed</span>
                            CPU Usage
                        </h3>
                    </div>
                    <div class="chart-container">
                        <canvas id="cpu-chart"></canvas>
                    </div>
                </div>
                <div class="chart-card card">
                    <div class="card-header">
                        <h3 class="card-title">
                            <span class="material-symbols-outlined">memory</span>
                            Memory Usage
                        </h3>
                    </div>
                    <div class="chart-container">
                        <canvas id="memory-chart"></canvas>
                    </div>
                </div>
                <div class="chart-card card">
                    <div class="card-header">
                        <h3 class="card-title">
                            <span class="material-symbols-outlined">network_check</span>
                            Network I/O
                        </h3>
                    </div>
                    <div class="chart-container">
                        <canvas id="network-chart"></canvas>
                    </div>
                </div>
                <div class="chart-card card">
                    <div class="card-header">
                        <h3 class="card-title">
                            <span class="material-symbols-outlined">hard_drive</span>
                            Disk I/O
                        </h3>
                    </div>
                    <div class="chart-container">
                        <canvas id="disk-chart"></canvas>
                    </div>
                </div>
            </div>
        </div>

        <!-- Recent Containers -->
        <div class="card">
            <div class="card-header">
                <h3 class="card-title">Recent Containers</h3>
                <md-text-button id="view-all-containers">
                    View All
                    <span class="material-symbols-outlined" slot="icon">arrow_forward</span>
                </md-text-button>
            </div>
            <div class="card-content" id="recent-containers">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>
    `;
}

// Initialize dashboard
export async function init() {
    // Setup navigation
    document.getElementById('view-all-containers')?.addEventListener('click', () => {
        navigateTo('containers');
    });

    // Initialize charts
    initCharts();

    // Load initial data
    await loadDashboardData();

    // Start auto-refresh
    startAutoRefresh();
}

// Start auto-refresh polling
function startAutoRefresh() {
    // Clear any existing interval
    stopAutoRefresh();

    // Start new interval
    refreshIntervalId = setInterval(async () => {
        await refreshStats();
    }, REFRESH_INTERVAL);
}

// Stop auto-refresh polling
function stopAutoRefresh() {
    if (refreshIntervalId) {
        clearInterval(refreshIntervalId);
        refreshIntervalId = null;
    }
}

// Refresh stats only (lighter than full dashboard load)
async function refreshStats() {
    if (!state.currentHostId) return;

    // Check if we're still on the dashboard
    if (!document.getElementById('cpu-chart')) return;

    try {
        const containerStats = await getContainerStats(state.currentHostId);

        // Check again after async operation
        if (!document.getElementById('cpu-chart')) return;

        previousStats = updateCharts(containerStats, previousStats);
    } catch (error) {
        // Silently fail on refresh - don't spam error messages
        console.error('Failed to refresh stats:', error);
    }
}

// Load dashboard data
async function loadDashboardData() {
    if (!state.currentHostId) {
        showEmptyState();
        return;
    }

    try {
        // Load all data in parallel
        const [stats, hostInfo, containerStats] = await Promise.all([
            getDashboardStats(state.currentHostId),
            getHostInfo(state.currentHostId).catch(() => null),
            getContainerStats(state.currentHostId).catch(() => []),
        ]);

        // Check if we're still on the dashboard (user may have navigated away)
        if (!document.getElementById('stat-containers')) return;

        updateStats(stats);
        renderHostInfo(hostInfo);
        renderRecentContainers(stats.containers, containerStats);

        // Initial chart update
        if (containerStats && containerStats.length > 0) {
            previousStats = updateCharts(containerStats, previousStats);
        }
    } catch (error) {
        // Only show error if we're still on the dashboard
        if (document.getElementById('stat-containers')) {
            console.error('Failed to load dashboard:', error);
            showToast('Failed to load dashboard data', 'error');
            showEmptyState();
        }
    }
}

// Render host info section
function renderHostInfo(hostInfo) {
    const container = document.getElementById('host-info-grid');
    if (!container) return;

    if (!hostInfo) {
        container.innerHTML = `
            <div class="empty-state small">
                <span class="material-symbols-outlined">info</span>
                <p>Host information unavailable</p>
            </div>
        `;
        return;
    }

    container.innerHTML = `
        <div class="host-info-item">
            <span class="host-info-label">Docker Version</span>
            <span class="host-info-value">${hostInfo.dockerVersion || 'N/A'}</span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">Operating System</span>
            <span class="host-info-value">${hostInfo.operatingSystem || 'N/A'}</span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">Architecture</span>
            <span class="host-info-value">${hostInfo.architecture || 'N/A'}</span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">Kernel</span>
            <span class="host-info-value">${hostInfo.kernelVersion || 'N/A'}</span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">Hostname</span>
            <span class="host-info-value">${hostInfo.hostname || 'N/A'}</span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">Total Memory</span>
            <span class="host-info-value">${hostInfo.totalMemory ? formatMemory(hostInfo.totalMemory) : 'N/A'}</span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">CPUs</span>
            <span class="host-info-value">${hostInfo.cpus || 'N/A'}</span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">Storage Driver</span>
            <span class="host-info-value">${hostInfo.storageDriver || 'N/A'}</span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">Containers</span>
            <span class="host-info-value">
                <span class="host-info-badge running">${hostInfo.containersRunning || 0}</span> running
                <span class="host-info-badge paused">${hostInfo.containersPaused || 0}</span> paused
                <span class="host-info-badge stopped">${hostInfo.containersStopped || 0}</span> stopped
            </span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">Images</span>
            <span class="host-info-value">${hostInfo.imagesTotal || 0}</span>
        </div>
        <div class="host-info-item">
            <span class="host-info-label">Root Dir</span>
            <span class="host-info-value mono">${hostInfo.dockerRootDir || 'N/A'}</span>
        </div>
    `;
}

// Update stats display
function updateStats(stats) {
    const containers = document.getElementById('stat-containers');
    const running = document.getElementById('stat-running');
    const images = document.getElementById('stat-images');
    const networks = document.getElementById('stat-networks');

    // Check if elements still exist (user may have navigated away)
    if (!containers || !running || !images || !networks) return;

    containers.textContent = stats.totalContainers;
    running.textContent = stats.runningContainers;
    images.textContent = stats.totalImages;
    networks.textContent = stats.totalNetworks;
}

// Render recent containers table with resource usage
function renderRecentContainers(containers, containerStats = []) {
    const container = document.getElementById('recent-containers');

    // Check if element still exists (user may have navigated away)
    if (!container) return;

    if (!containers || containers.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <span class="material-symbols-outlined">inventory_2</span>
                <h3>No containers</h3>
                <p>Create a container to get started</p>
            </div>
        `;
        return;
    }

    // Create a map of container stats by ID for quick lookup
    const statsMap = new Map();
    containerStats.forEach(stats => {
        statsMap.set(stats.containerId, stats);
    });

    container.innerHTML = `
        <table class="data-table">
            <thead>
                <tr>
                    <th>Name</th>
                    <th>Image</th>
                    <th>Status</th>
                    <th>CPU</th>
                    <th>Memory</th>
                    <th>Created</th>
                </tr>
            </thead>
            <tbody>
                ${containers.map(c => {
                    const stats = statsMap.get(c.id);
                    return `
                    <tr>
                        <td><strong>${getContainerName(c)}</strong></td>
                        <td class="mono truncate" style="max-width: 200px;">${truncate(c.image, 30)}</td>
                        <td>${renderContainerStatus(c)}</td>
                        <td>${renderCpuBar(stats)}</td>
                        <td>${renderMemoryBar(stats)}</td>
                        <td>${formatDate(c.created * 1000)}</td>
                    </tr>
                `;
                }).join('')}
            </tbody>
        </table>
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

// Get container name
function getContainerName(container) {
    if (container.names && container.names.length > 0) {
        return container.names[0].replace(/^\//, '');
    }
    return container.id?.substring(0, 12) || 'Unknown';
}

// Show empty state
function showEmptyState() {
    const statContainers = document.getElementById('stat-containers');
    const statRunning = document.getElementById('stat-running');
    const statImages = document.getElementById('stat-images');
    const statNetworks = document.getElementById('stat-networks');
    const hostInfoGrid = document.getElementById('host-info-grid');
    const recentContainers = document.getElementById('recent-containers');

    // Check if elements still exist (user may have navigated away)
    if (!statContainers) return;

    statContainers.textContent = '-';
    if (statRunning) statRunning.textContent = '-';
    if (statImages) statImages.textContent = '-';
    if (statNetworks) statNetworks.textContent = '-';

    if (hostInfoGrid) {
        hostInfoGrid.innerHTML = `
            <div class="empty-state small">
                <span class="material-symbols-outlined">dns</span>
                <p>No host selected</p>
            </div>
        `;
    }

    if (recentContainers) {
        recentContainers.innerHTML = `
            <div class="empty-state">
                <span class="material-symbols-outlined">dns</span>
                <h3>No host selected</h3>
                <p>Select a Docker host to view containers</p>
            </div>
        `;
    }
}

// Cleanup
export function cleanup() {
    // Stop auto-refresh
    stopAutoRefresh();

    // Destroy charts
    destroyCharts();

    // Reset previous stats
    previousStats = null;
}
