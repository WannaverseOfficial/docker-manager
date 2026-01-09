// Charts Component - Real-time monitoring charts using Chart.js

// Store chart instances for cleanup
const chartInstances = {};

// Chart color palette (Material Design inspired)
const CHART_COLORS = {
    cpu: {
        border: '#8b5cf6',
        background: 'rgba(139, 92, 246, 0.1)',
    },
    memory: {
        border: '#06b6d4',
        background: 'rgba(6, 182, 212, 0.1)',
    },
    networkRx: {
        border: '#10b981',
        background: 'rgba(16, 185, 129, 0.1)',
    },
    networkTx: {
        border: '#f59e0b',
        background: 'rgba(245, 158, 11, 0.1)',
    },
    blockRead: {
        border: '#3b82f6',
        background: 'rgba(59, 130, 246, 0.1)',
    },
    blockWrite: {
        border: '#ef4444',
        background: 'rgba(239, 68, 68, 0.1)',
    },
};

// Maximum data points to keep (5 minutes at 5 second intervals)
const MAX_DATA_POINTS = 60;

// Chart default options
const defaultOptions = {
    responsive: true,
    maintainAspectRatio: false,
    animation: {
        duration: 300,
    },
    interaction: {
        mode: 'index',
        intersect: false,
    },
    scales: {
        x: {
            display: true,
            grid: {
                color: 'rgba(255, 255, 255, 0.05)',
            },
            ticks: {
                color: 'rgba(255, 255, 255, 0.5)',
                maxTicksLimit: 6,
            },
        },
        y: {
            display: true,
            beginAtZero: true,
            grid: {
                color: 'rgba(255, 255, 255, 0.05)',
            },
            ticks: {
                color: 'rgba(255, 255, 255, 0.5)',
            },
        },
    },
    plugins: {
        legend: {
            display: true,
            position: 'top',
            labels: {
                color: 'rgba(255, 255, 255, 0.7)',
                usePointStyle: true,
                padding: 15,
            },
        },
        tooltip: {
            backgroundColor: 'rgba(17, 20, 24, 0.9)',
            titleColor: '#fff',
            bodyColor: 'rgba(255, 255, 255, 0.7)',
            borderColor: 'rgba(255, 255, 255, 0.1)',
            borderWidth: 1,
            padding: 10,
        },
    },
};

/**
 * Initialize all dashboard charts
 * @returns {Object} Chart instances
 */
export function initCharts() {
    destroyCharts();

    // CPU Chart
    const cpuCtx = document.getElementById('cpu-chart')?.getContext('2d');
    if (cpuCtx) {
        chartInstances.cpu = new Chart(cpuCtx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: 'CPU Usage %',
                    data: [],
                    borderColor: CHART_COLORS.cpu.border,
                    backgroundColor: CHART_COLORS.cpu.background,
                    fill: true,
                    tension: 0.4,
                    pointRadius: 0,
                    borderWidth: 2,
                }],
            },
            options: {
                ...defaultOptions,
                scales: {
                    ...defaultOptions.scales,
                    y: {
                        ...defaultOptions.scales.y,
                        max: 100,
                        ticks: {
                            ...defaultOptions.scales.y.ticks,
                            callback: (value) => value + '%',
                        },
                    },
                },
            },
        });
    }

    // Memory Chart
    const memCtx = document.getElementById('memory-chart')?.getContext('2d');
    if (memCtx) {
        chartInstances.memory = new Chart(memCtx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: 'Memory Usage %',
                    data: [],
                    borderColor: CHART_COLORS.memory.border,
                    backgroundColor: CHART_COLORS.memory.background,
                    fill: true,
                    tension: 0.4,
                    pointRadius: 0,
                    borderWidth: 2,
                }],
            },
            options: {
                ...defaultOptions,
                scales: {
                    ...defaultOptions.scales,
                    y: {
                        ...defaultOptions.scales.y,
                        max: 100,
                        ticks: {
                            ...defaultOptions.scales.y.ticks,
                            callback: (value) => value + '%',
                        },
                    },
                },
            },
        });
    }

    // Network I/O Chart
    const netCtx = document.getElementById('network-chart')?.getContext('2d');
    if (netCtx) {
        chartInstances.network = new Chart(netCtx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [
                    {
                        label: 'RX (Received)',
                        data: [],
                        borderColor: CHART_COLORS.networkRx.border,
                        backgroundColor: CHART_COLORS.networkRx.background,
                        fill: true,
                        tension: 0.4,
                        pointRadius: 0,
                        borderWidth: 2,
                    },
                    {
                        label: 'TX (Transmitted)',
                        data: [],
                        borderColor: CHART_COLORS.networkTx.border,
                        backgroundColor: CHART_COLORS.networkTx.background,
                        fill: true,
                        tension: 0.4,
                        pointRadius: 0,
                        borderWidth: 2,
                    },
                ],
            },
            options: {
                ...defaultOptions,
                scales: {
                    ...defaultOptions.scales,
                    y: {
                        ...defaultOptions.scales.y,
                        ticks: {
                            ...defaultOptions.scales.y.ticks,
                            callback: (value) => formatBytes(value) + '/s',
                        },
                    },
                },
            },
        });
    }

    // Disk I/O Chart
    const diskCtx = document.getElementById('disk-chart')?.getContext('2d');
    if (diskCtx) {
        chartInstances.disk = new Chart(diskCtx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [
                    {
                        label: 'Read',
                        data: [],
                        borderColor: CHART_COLORS.blockRead.border,
                        backgroundColor: CHART_COLORS.blockRead.background,
                        fill: true,
                        tension: 0.4,
                        pointRadius: 0,
                        borderWidth: 2,
                    },
                    {
                        label: 'Write',
                        data: [],
                        borderColor: CHART_COLORS.blockWrite.border,
                        backgroundColor: CHART_COLORS.blockWrite.background,
                        fill: true,
                        tension: 0.4,
                        pointRadius: 0,
                        borderWidth: 2,
                    },
                ],
            },
            options: {
                ...defaultOptions,
                scales: {
                    ...defaultOptions.scales,
                    y: {
                        ...defaultOptions.scales.y,
                        ticks: {
                            ...defaultOptions.scales.y.ticks,
                            callback: (value) => formatBytes(value),
                        },
                    },
                },
            },
        });
    }

    return chartInstances;
}

/**
 * Update charts with new container stats data
 * @param {Array} statsArray - Array of container stats
 * @param {Object} previousStats - Previous stats for calculating rates
 */
export function updateCharts(statsArray, previousStats = null) {
    // Check if charts are still initialized (user may have navigated away)
    if (Object.keys(chartInstances).length === 0) {
        return previousStats;
    }

    const timestamp = new Date().toLocaleTimeString('en-US', {
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
    });

    // Calculate aggregated stats
    let totalCpuPercent = 0;
    let totalMemoryPercent = 0;
    let totalNetworkRx = 0;
    let totalNetworkTx = 0;
    let totalBlockRead = 0;
    let totalBlockWrite = 0;

    statsArray.forEach((stats) => {
        totalCpuPercent += stats.cpuPercent || 0;
        totalMemoryPercent += stats.memoryPercent || 0;
        totalNetworkRx += stats.networkRxBytes || 0;
        totalNetworkTx += stats.networkTxBytes || 0;
        totalBlockRead += stats.blockReadBytes || 0;
        totalBlockWrite += stats.blockWriteBytes || 0;
    });

    // Calculate average percentages
    const containerCount = statsArray.length || 1;
    const avgCpuPercent = totalCpuPercent / containerCount;
    const avgMemoryPercent = totalMemoryPercent / containerCount;

    // Calculate network/disk rates if we have previous stats
    let networkRxRate = 0;
    let networkTxRate = 0;
    let blockReadRate = 0;
    let blockWriteRate = 0;

    if (previousStats) {
        const timeDelta = 5; // 5 seconds polling interval
        networkRxRate = Math.max(0, (totalNetworkRx - (previousStats.networkRx || 0)) / timeDelta);
        networkTxRate = Math.max(0, (totalNetworkTx - (previousStats.networkTx || 0)) / timeDelta);
        blockReadRate = Math.max(0, (totalBlockRead - (previousStats.blockRead || 0)) / timeDelta);
        blockWriteRate = Math.max(0, (totalBlockWrite - (previousStats.blockWrite || 0)) / timeDelta);
    }

    // Update CPU chart
    if (chartInstances.cpu) {
        addDataPoint(chartInstances.cpu, timestamp, avgCpuPercent);
    }

    // Update Memory chart
    if (chartInstances.memory) {
        addDataPoint(chartInstances.memory, timestamp, avgMemoryPercent);
    }

    // Update Network chart
    if (chartInstances.network) {
        addDataPoint(chartInstances.network, timestamp, [networkRxRate, networkTxRate]);
    }

    // Update Disk chart
    if (chartInstances.disk) {
        addDataPoint(chartInstances.disk, timestamp, [blockReadRate, blockWriteRate]);
    }

    // Return current totals for next calculation
    return {
        networkRx: totalNetworkRx,
        networkTx: totalNetworkTx,
        blockRead: totalBlockRead,
        blockWrite: totalBlockWrite,
    };
}

/**
 * Add a data point to a chart
 * @param {Chart} chart - Chart.js instance
 * @param {string} label - X-axis label (timestamp)
 * @param {number|Array} data - Data value(s) to add
 */
function addDataPoint(chart, label, data) {
    chart.data.labels.push(label);

    if (Array.isArray(data)) {
        data.forEach((value, index) => {
            chart.data.datasets[index].data.push(value);
        });
    } else {
        chart.data.datasets[0].data.push(data);
    }

    // Remove old data points to maintain sliding window
    if (chart.data.labels.length > MAX_DATA_POINTS) {
        chart.data.labels.shift();
        chart.data.datasets.forEach((dataset) => {
            dataset.data.shift();
        });
    }

    chart.update('none'); // 'none' mode skips animations for smooth updates
}

/**
 * Destroy all chart instances
 */
export function destroyCharts() {
    Object.values(chartInstances).forEach((chart) => {
        if (chart) {
            chart.destroy();
        }
    });
    // Clear the instances object
    Object.keys(chartInstances).forEach((key) => {
        delete chartInstances[key];
    });
}

/**
 * Format bytes to human readable string
 * @param {number} bytes - Bytes value
 * @returns {string} Formatted string
 */
export function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

/**
 * Format memory to human readable string with appropriate unit
 * @param {number} bytes - Memory in bytes
 * @returns {string} Formatted string
 */
export function formatMemory(bytes) {
    return formatBytes(bytes);
}

/**
 * Get chart instances (for external access)
 */
export function getChartInstances() {
    return chartInstances;
}
