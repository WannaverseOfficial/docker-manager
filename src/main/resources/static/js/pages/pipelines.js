// Pipelines Page - Visual Pipeline Builder
import {
    listPipelines, getPipeline, createPipeline, updatePipeline, deletePipeline,
    duplicatePipeline, togglePipelineEnabled, triggerExecution, listExecutions,
    getExecution, cancelExecution, streamExecutionLogs
} from '../api/pipelines.js';
import { loadHosts } from '../api/docker.js';
import { listRepositories } from '../api/git.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete } from '../components/confirm-dialog.js';
import { renderTable, formatDate } from '../components/data-table.js';
import { PipelineGraphEditor } from '../components/pipeline-graph-editor.js';

let pipelines = [];
let hosts = [];
let gitRepos = [];
let currentPipeline = null;
let currentExecution = null;
let currentEventSource = null;
let graphEditor = null;

export function render() {
    return `<div id="pipelines-page">${renderListView()}</div>`;
}

function renderListView() {
    return `
        <md-tabs id="pipeline-tabs">
            <md-primary-tab id="tab-pipelines" aria-controls="panel-pipelines">Pipelines</md-primary-tab>
            <md-primary-tab id="tab-executions" aria-controls="panel-executions">Recent Executions</md-primary-tab>
        </md-tabs>

        <div id="panel-pipelines" class="tab-panel">
            <div class="section-header" style="margin-top: 16px;">
                <h2 class="section-title">Pipelines</h2>
                <md-filled-button id="add-pipeline-btn">
                    <span class="material-symbols-outlined" slot="icon">add</span>
                    Create Pipeline
                </md-filled-button>
            </div>
            <div id="pipelines-grid" class="pipelines-grid">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>

        <div id="panel-executions" class="tab-panel hidden">
            <div class="section-header" style="margin-top: 16px;">
                <h2 class="section-title">Recent Executions</h2>
            </div>
            <div class="card">
                <div class="card-content" id="executions-table">
                    <div class="loading-container">
                        <md-circular-progress indeterminate></md-circular-progress>
                    </div>
                </div>
            </div>
        </div>
    `;
}

function renderPipelineEditor(pipeline = null) {
    const isEdit = !!pipeline;
    const hostOptions = hosts.map(h =>
        `<md-select-option value="${h.id}" ${pipeline?.dockerHostId === h.id ? 'selected' : ''}>${h.name || h.dockerHostUrl}</md-select-option>`
    ).join('');

    const repoOptions = `
        <md-select-option value="">None</md-select-option>
        ${gitRepos.map(r =>
            `<md-select-option value="${r.id}" ${pipeline?.gitRepositoryId === r.id ? 'selected' : ''}>${r.name}</md-select-option>`
        ).join('')}
    `;

    return `
        <div class="pipeline-editor-page">
            <div class="editor-header">
                <div class="editor-header-left">
                    <md-icon-button id="back-to-list-btn" title="Back to pipelines">
                        <span class="material-symbols-outlined">arrow_back</span>
                    </md-icon-button>
                    <div class="editor-title-group">
                        <input type="text" id="pipeline-name-input" class="pipeline-name-input"
                               value="${pipeline?.name || 'New Pipeline'}" placeholder="Pipeline Name">
                        <span class="editor-subtitle">${isEdit ? 'Edit Pipeline' : 'Create New Pipeline'}</span>
                    </div>
                </div>
                <div class="editor-header-right">
                    ${isEdit ? `
                        <md-outlined-button id="trigger-pipeline-btn">
                            <span class="material-symbols-outlined" slot="icon">play_arrow</span>
                            Run
                        </md-outlined-button>
                    ` : ''}
                    <md-filled-button id="save-pipeline-btn">
                        <span class="material-symbols-outlined" slot="icon">save</span>
                        ${isEdit ? 'Save' : 'Create'}
                    </md-filled-button>
                </div>
            </div>

            <div class="editor-body">
                <div class="editor-main">
                    <div id="pipeline-graph-container" class="pipeline-graph-container"></div>
                </div>

                <div class="editor-sidebar">
                    <div class="sidebar-section">
                        <div class="sidebar-section-title">Configuration</div>
                        <div class="sidebar-form">
                            <div class="form-field">
                                <md-filled-select id="pipeline-host" label="Docker Host *" required style="width: 100%;">
                                    ${hostOptions}
                                </md-filled-select>
                            </div>
                            <div class="form-field">
                                <md-filled-select id="pipeline-git-repo" label="Git Repository" style="width: 100%;">
                                    ${repoOptions}
                                </md-filled-select>
                            </div>
                            <div class="form-field">
                                <md-filled-text-field id="pipeline-branch-filter" label="Branch Filter (regex)"
                                    value="${pipeline?.branchFilter || ''}" style="width: 100%;">
                                </md-filled-text-field>
                            </div>
                        </div>
                    </div>

                    <div class="sidebar-section">
                        <div class="sidebar-section-title">Triggers</div>
                        <div class="trigger-options">
                            <label class="trigger-option">
                                <md-checkbox id="pipeline-webhook" ${pipeline?.webhookEnabled ? 'checked' : ''}></md-checkbox>
                                <div class="trigger-info">
                                    <span class="trigger-label">Webhook</span>
                                    <span class="trigger-desc">Trigger on push events</span>
                                </div>
                            </label>
                            <label class="trigger-option">
                                <md-checkbox id="pipeline-polling" ${pipeline?.pollingEnabled ? 'checked' : ''}></md-checkbox>
                                <div class="trigger-info">
                                    <span class="trigger-label">Polling</span>
                                    <span class="trigger-desc">Check for changes periodically</span>
                                </div>
                            </label>
                        </div>
                    </div>

                    <div class="sidebar-section">
                        <div class="sidebar-section-title">Status</div>
                        <label class="status-toggle">
                            <md-switch id="pipeline-enabled" ${pipeline?.enabled !== false ? 'selected' : ''}></md-switch>
                            <span>Pipeline Enabled</span>
                        </label>
                    </div>

                    ${isEdit && pipeline?.webhookSecret ? `
                        <div class="sidebar-section">
                            <div class="sidebar-section-title">Webhook URL</div>
                            <div class="webhook-url-box">
                                <code>/api/pipelines/webhook/${pipeline.webhookSecret}</code>
                                <md-icon-button class="copy-webhook-btn" title="Copy URL">
                                    <span class="material-symbols-outlined">content_copy</span>
                                </md-icon-button>
                            </div>
                        </div>
                    ` : ''}
                </div>
            </div>
        </div>
    `;
}

function renderExecutionView(execution) {
    const statusColors = {
        PENDING: 'var(--md-sys-color-outline)',
        RUNNING: 'var(--md-sys-color-primary)',
        SUCCESS: 'var(--md-sys-color-primary)',
        FAILED: 'var(--md-sys-color-error)',
        CANCELLED: 'var(--md-sys-color-outline)'
    };

    return `
        <div class="execution-view">
            <div class="execution-header">
                <div class="execution-header-left">
                    <md-icon-button id="back-to-list-btn" title="Back">
                        <span class="material-symbols-outlined">arrow_back</span>
                    </md-icon-button>
                    <div class="execution-title-group">
                        <h2>${execution.pipelineName} <span class="build-number">#${execution.buildNumber}</span></h2>
                        <div class="execution-meta">
                            <span class="status-badge ${execution.status.toLowerCase()}">${execution.status}</span>
                            <span class="meta-item">
                                <span class="material-symbols-outlined">schedule</span>
                                ${formatDate(execution.createdAt)}
                            </span>
                            ${execution.triggerCommit ? `
                                <span class="meta-item">
                                    <span class="material-symbols-outlined">commit</span>
                                    ${execution.triggerCommit.substring(0, 7)}
                                </span>
                            ` : ''}
                        </div>
                    </div>
                </div>
                ${execution.status === 'RUNNING' || execution.status === 'PENDING' ? `
                    <md-outlined-button id="cancel-execution-btn">
                        <span class="material-symbols-outlined" slot="icon">cancel</span>
                        Cancel
                    </md-outlined-button>
                ` : ''}
            </div>

            <div class="execution-content">
                <div class="execution-stages-panel">
                    <div class="stages-timeline">
                        ${(execution.stageExecutions || []).map((stage, index) => `
                            <div class="timeline-stage ${stage.status.toLowerCase()}">
                                <div class="timeline-connector ${index === 0 ? 'first' : ''}"></div>
                                <div class="timeline-node">
                                    ${getStatusIcon(stage.status)}
                                </div>
                                <div class="timeline-content">
                                    <div class="stage-name">${stage.stageName}</div>
                                    <div class="stage-info">
                                        ${stage.durationMs ? formatDuration(stage.durationMs) : 'Waiting...'}
                                    </div>
                                </div>
                            </div>
                        `).join('')}
                    </div>
                </div>

                <div class="execution-logs-panel">
                    <div class="logs-header">
                        <span class="logs-title">Build Output</span>
                        <md-icon-button id="copy-logs-btn" title="Copy Logs">
                            <span class="material-symbols-outlined">content_copy</span>
                        </md-icon-button>
                    </div>
                    <pre id="execution-logs" class="execution-logs">${execution.logs || 'Waiting for output...'}</pre>
                </div>
            </div>
        </div>
    `;
}

function getStatusIcon(status) {
    switch (status) {
        case 'SUCCESS': return '<span class="material-symbols-outlined status-icon success">check_circle</span>';
        case 'FAILED': return '<span class="material-symbols-outlined status-icon failed">error</span>';
        case 'RUNNING': return '<md-circular-progress indeterminate class="status-spinner"></md-circular-progress>';
        case 'PENDING': return '<span class="material-symbols-outlined status-icon pending">schedule</span>';
        case 'CANCELLED': return '<span class="material-symbols-outlined status-icon cancelled">cancel</span>';
        default: return '<span class="material-symbols-outlined status-icon">circle</span>';
    }
}

function formatDuration(ms) {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`;
}

export async function init() {
    // Load CSS
    if (!document.getElementById('pipeline-editor-css')) {
        const link = document.createElement('link');
        link.id = 'pipeline-editor-css';
        link.rel = 'stylesheet';
        link.href = '/css/pipeline-editor.css';
        document.head.appendChild(link);
    }

    await Promise.all([
        loadData(),
        loadHosts().then(h => hosts = h),
        listRepositories().then(r => gitRepos = r).catch(() => gitRepos = [])
    ]);

    setupEventListeners();
    renderPipelinesGrid();
}

async function loadData() {
    try {
        pipelines = await listPipelines();
    } catch (error) {
        console.error('Failed to load pipelines:', error);
        showToast('Failed to load pipelines', 'error');
        pipelines = [];
    }
}

function setupEventListeners() {
    const page = document.getElementById('pipelines-page');
    if (!page) return;

    // Tab switching
    document.getElementById('tab-pipelines')?.addEventListener('click', () => {
        document.getElementById('panel-pipelines').classList.remove('hidden');
        document.getElementById('panel-executions').classList.add('hidden');
    });

    document.getElementById('tab-executions')?.addEventListener('click', () => {
        document.getElementById('panel-pipelines').classList.add('hidden');
        document.getElementById('panel-executions').classList.remove('hidden');
        loadRecentExecutions();
    });

    // Add pipeline button
    document.getElementById('add-pipeline-btn')?.addEventListener('click', () => {
        openPipelineEditor(null);
    });

    // Delegate pipeline card actions
    page.addEventListener('click', async (e) => {
        const card = e.target.closest('.pipeline-card');
        const action = e.target.closest('[data-action]');

        if (action) {
            e.stopPropagation();
            const pipelineId = action.dataset.pipelineId;
            const actionType = action.dataset.action;

            switch (actionType) {
                case 'run':
                    await handleTriggerPipeline(pipelineId);
                    break;
                case 'edit':
                    await openPipelineEditor(pipelineId);
                    break;
                case 'delete':
                    await handleDeletePipeline(pipelineId);
                    break;
                case 'duplicate':
                    await handleDuplicatePipeline(pipelineId);
                    break;
            }
        } else if (card) {
            const pipelineId = card.dataset.pipelineId;
            await openPipelineEditor(pipelineId);
        }

        // Execution view action
        const execAction = e.target.closest('[data-exec-action]');
        if (execAction) {
            const executionId = execAction.dataset.executionId;
            await viewExecution(executionId);
        }
    });
}

function renderPipelinesGrid() {
    const container = document.getElementById('pipelines-grid');
    if (!container) return;

    if (pipelines.length === 0) {
        container.innerHTML = `
            <div class="empty-state-large">
                <div class="empty-icon">
                    <span class="material-symbols-outlined">account_tree</span>
                </div>
                <h3>No Pipelines Yet</h3>
                <p>Create your first pipeline to automate a workflow</p>
                <md-filled-button id="empty-add-btn">
                    <span class="material-symbols-outlined" slot="icon">add</span>
                    Create Pipeline
                </md-filled-button>
            </div>
        `;
        document.getElementById('empty-add-btn')?.addEventListener('click', () => {
            openPipelineEditor(null);
        });
        return;
    }

    container.innerHTML = pipelines.map(p => {
        const stageCount = p.stages?.length || 0;
        const stepCount = p.stages?.reduce((sum, s) => sum + (s.steps?.length || 0), 0) || 0;

        return `
            <div class="pipeline-card ${p.enabled ? '' : 'disabled'}" data-pipeline-id="${p.id}">
                <div class="pipeline-card-header">
                    <div class="pipeline-status ${p.lastExecutionStatus?.toLowerCase() || 'none'}">
                        ${p.lastExecutionStatus ? getStatusIcon(p.lastExecutionStatus) : '<span class="material-symbols-outlined">circle</span>'}
                    </div>
                    <div class="pipeline-card-actions">
                        <md-icon-button data-action="run" data-pipeline-id="${p.id}" title="Run Pipeline">
                            <span class="material-symbols-outlined">play_arrow</span>
                        </md-icon-button>
                        <md-icon-button data-action="duplicate" data-pipeline-id="${p.id}" title="Duplicate">
                            <span class="material-symbols-outlined">content_copy</span>
                        </md-icon-button>
                        <md-icon-button data-action="delete" data-pipeline-id="${p.id}" title="Delete">
                            <span class="material-symbols-outlined">delete</span>
                        </md-icon-button>
                    </div>
                </div>
                <div class="pipeline-card-body">
                    <h3 class="pipeline-card-title">${p.name}</h3>
                    <div class="pipeline-card-stats">
                        <span class="stat">
                            <span class="material-symbols-outlined">layers</span>
                            ${stageCount} stage${stageCount !== 1 ? 's' : ''}
                        </span>
                        <span class="stat">
                            <span class="material-symbols-outlined">list</span>
                            ${stepCount} step${stepCount !== 1 ? 's' : ''}
                        </span>
                    </div>
                    <div class="pipeline-card-triggers">
                        ${p.webhookEnabled ? '<span class="trigger-badge">Webhook</span>' : ''}
                        ${p.pollingEnabled ? '<span class="trigger-badge">Polling</span>' : ''}
                        ${!p.webhookEnabled && !p.pollingEnabled ? '<span class="trigger-badge manual">Manual</span>' : ''}
                    </div>
                </div>
                <div class="pipeline-card-footer">
                    ${p.lastExecutionAt ? `
                        <span class="last-run">Last run ${formatDate(p.lastExecutionAt)}</span>
                    ` : '<span class="last-run">Never run</span>'}
                </div>
            </div>
        `;
    }).join('');
}

async function openPipelineEditor(pipelineId) {
    const page = document.getElementById('pipelines-page');

    if (pipelineId) {
        try {
            currentPipeline = await getPipeline(pipelineId);
        } catch (error) {
            showToast('Failed to load pipeline', 'error');
            return;
        }
    } else {
        currentPipeline = null;
    }

    page.innerHTML = renderPipelineEditor(currentPipeline);
    setupEditorListeners();

    // Initialize graph editor
    const graphContainer = document.getElementById('pipeline-graph-container');
    graphEditor = new PipelineGraphEditor(graphContainer, {
        onChange: (stages) => {
            // Auto-save is handled by the save button
        },
        onStageSelect: (stage) => {
            // Stage selected in graph
        }
    });

    if (currentPipeline?.stages) {
        graphEditor.setStages(currentPipeline.stages);
    }
}

function setupEditorListeners() {
    const page = document.getElementById('pipelines-page');

    // Back button - use onclick to replace any existing handler
    const backBtn = document.getElementById('back-to-list-btn');
    if (backBtn) {
        backBtn.onclick = async () => {
            page.innerHTML = renderListView();
            await loadData();
            setupEventListeners();
            renderPipelinesGrid();
        };
    }

    // Save button
    const saveBtn = document.getElementById('save-pipeline-btn');
    if (saveBtn) {
        saveBtn.onclick = savePipeline;
    }

    // Trigger button
    const triggerBtn = document.getElementById('trigger-pipeline-btn');
    if (triggerBtn) {
        triggerBtn.onclick = async () => {
            if (currentPipeline) {
                await handleTriggerPipeline(currentPipeline.id);
            }
        };
    }

    // Copy webhook URL
    const copyBtn = document.querySelector('.copy-webhook-btn');
    if (copyBtn) {
        copyBtn.onclick = () => {
            const webhookUrl = document.querySelector('.webhook-url-box code')?.textContent;
            if (webhookUrl) {
                navigator.clipboard.writeText(window.location.origin + webhookUrl);
                showToast('Webhook URL copied to clipboard', 'success');
            }
        };
    }
}

async function savePipeline() {
    const name = document.getElementById('pipeline-name-input')?.value;
    const dockerHostId = document.getElementById('pipeline-host')?.value;
    const gitRepositoryId = document.getElementById('pipeline-git-repo')?.value;
    const branchFilter = document.getElementById('pipeline-branch-filter')?.value;
    const enabled = document.getElementById('pipeline-enabled')?.selected;
    const webhookEnabled = document.getElementById('pipeline-webhook')?.checked;
    const pollingEnabled = document.getElementById('pipeline-polling')?.checked;

    if (!name) {
        showToast('Pipeline name is required', 'error');
        return;
    }

    if (!dockerHostId) {
        showToast('Docker host is required', 'error');
        return;
    }

    const stages = graphEditor ? graphEditor.getStages() : [];

    const request = {
        name,
        dockerHostId,
        gitRepositoryId: gitRepositoryId || null,
        branchFilter,
        enabled,
        webhookEnabled,
        pollingEnabled,
        stages
    };

    // Debug: log the request to console
    console.log('Saving pipeline request:', JSON.stringify(request, null, 2));

    try {
        if (currentPipeline) {
            await updatePipeline(currentPipeline.id, request);
            showToast('Pipeline saved successfully', 'success');
        } else {
            const newPipeline = await createPipeline(request);
            currentPipeline = newPipeline;
            showToast('Pipeline created successfully', 'success');

            // Navigate back to list after creation
            const page = document.getElementById('pipelines-page');
            page.innerHTML = renderListView();
            await loadData();
            setupEventListeners();
            renderPipelinesGrid();
        }
    } catch (error) {
        console.error('Save pipeline error:', error);
        showToast('Failed to save pipeline: ' + error.message, 'error');
    }
}

async function loadRecentExecutions() {
    const container = document.getElementById('executions-table');
    if (!container) return;

    try {
        const allExecutions = [];
        for (const p of pipelines.slice(0, 10)) {
            try {
                const execs = await listExecutions(p.id);
                allExecutions.push(...execs.slice(0, 5));
            } catch (e) {
                console.error('Failed to load executions for pipeline', p.id, e);
            }
        }

        allExecutions.sort((a, b) => b.createdAt - a.createdAt);

        if (allExecutions.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <span class="material-symbols-outlined">history</span>
                    <p>No executions yet. Run a pipeline to see results here.</p>
                </div>
            `;
            return;
        }

        const rows = allExecutions.slice(0, 20).map(e => ({
            id: e.id,
            pipeline: `<strong>${e.pipelineName || 'Unknown'}</strong> <span class="build-num">#${e.buildNumber}</span>`,
            status: `<span class="status-badge ${(e.status || 'unknown').toLowerCase()}">${e.status || 'UNKNOWN'}</span>`,
            trigger: e.triggerType || '-',
            commit: e.triggerCommit ? `<code>${e.triggerCommit.substring(0, 7)}</code>` : '-',
            duration: formatDuration(e.durationMs),
            started: formatDate(e.createdAt)
        }));

        container.innerHTML = renderTable({
            columns: [
                { label: 'Pipeline', key: 'pipeline' },
                { label: 'Status', key: 'status' },
                { label: 'Trigger', key: 'trigger' },
                { label: 'Commit', key: 'commit' },
                { label: 'Duration', key: 'duration' },
                { label: 'Started', key: 'started' }
            ],
            data: rows,
            actions: (row) => `
                <md-icon-button data-exec-action="view" data-execution-id="${row.id}" title="View Details">
                    <span class="material-symbols-outlined">visibility</span>
                </md-icon-button>
            `,
            emptyMessage: 'No executions yet',
            emptyIcon: 'history'
        });
    } catch (error) {
        console.error('Failed to load executions:', error);
        container.innerHTML = '<p class="error-message">Failed to load executions</p>';
    }
}

async function handleDeletePipeline(id) {
    const pipeline = pipelines.find(p => p.id === id);
    const confirmed = await confirmDelete(`Delete pipeline "${pipeline?.name}"? This cannot be undone.`);
    if (confirmed) {
        try {
            await deletePipeline(id);
            showToast('Pipeline deleted', 'success');
            await loadData();
            renderPipelinesGrid();
        } catch (error) {
            showToast('Failed to delete pipeline', 'error');
        }
    }
}

let isTriggering = false;
async function handleTriggerPipeline(id) {
    if (isTriggering) return; // Prevent double-click
    isTriggering = true;

    try {
        const execution = await triggerExecution(id);
        showToast(`Pipeline triggered - Build #${execution.buildNumber}`, 'success');
        await viewExecution(execution.id);
    } catch (error) {
        showToast('Failed to trigger pipeline: ' + error.message, 'error');
    } finally {
        isTriggering = false;
    }
}

async function handleDuplicatePipeline(id) {
    try {
        await duplicatePipeline(id);
        showToast('Pipeline duplicated', 'success');
        await loadData();
        renderPipelinesGrid();
    } catch (error) {
        showToast('Failed to duplicate pipeline', 'error');
    }
}

async function viewExecution(executionId) {
    try {
        currentExecution = await getExecution(executionId);
        const page = document.getElementById('pipelines-page');
        page.innerHTML = renderExecutionView(currentExecution);

        // Back button - use onclick to prevent duplicate handlers
        const backBtn = document.getElementById('back-to-list-btn');
        if (backBtn) {
            backBtn.onclick = async () => {
                if (currentEventSource) {
                    currentEventSource.close();
                    currentEventSource = null;
                }
                if (window._executionPollInterval) {
                    clearInterval(window._executionPollInterval);
                    window._executionPollInterval = null;
                }
                page.innerHTML = renderListView();
                await loadData();
                setupEventListeners();
                renderPipelinesGrid();
            };
        }

        // Cancel button
        const cancelBtn = document.getElementById('cancel-execution-btn');
        if (cancelBtn) {
            cancelBtn.onclick = async () => {
                await cancelExecution(executionId);
                showToast('Execution cancelled', 'success');
                await viewExecution(executionId);
            };
        }

        // Copy logs
        const copyLogsBtn = document.getElementById('copy-logs-btn');
        if (copyLogsBtn) {
            copyLogsBtn.onclick = () => {
                const logs = document.getElementById('execution-logs')?.textContent;
                if (logs) {
                    navigator.clipboard.writeText(logs);
                    showToast('Logs copied to clipboard', 'success');
                }
            };
        }

        // Stream logs and poll for status updates if still running
        if (currentExecution.status === 'RUNNING' || currentExecution.status === 'PENDING') {
            const logsEl = document.getElementById('execution-logs');

            // Set up SSE for real-time logs
            currentEventSource = streamExecutionLogs(
                executionId,
                (log) => {
                    logsEl.textContent += log + '\n';
                    logsEl.scrollTop = logsEl.scrollHeight;
                },
                async () => {
                    // Wait a moment for async log writes to complete, then refresh
                    setTimeout(async () => {
                        await viewExecution(executionId);
                    }, 500);
                },
                (error) => {
                    console.error('Log stream error:', error);
                }
            );

            // Also poll for status updates every 2 seconds (in case SSE doesn't work)
            const pollInterval = setInterval(async () => {
                try {
                    const updated = await getExecution(executionId);

                    // Update logs if we have new ones
                    if (updated.logs && updated.logs !== logsEl.textContent) {
                        logsEl.textContent = updated.logs;
                        logsEl.scrollTop = logsEl.scrollHeight;
                    }

                    // If execution is complete, refresh the full view
                    if (updated.status !== 'RUNNING' && updated.status !== 'PENDING') {
                        clearInterval(pollInterval);
                        if (currentEventSource) {
                            currentEventSource.close();
                            currentEventSource = null;
                        }
                        await viewExecution(executionId);
                    }
                } catch (e) {
                    console.error('Polling error:', e);
                }
            }, 2000);

            // Store interval ID for cleanup
            window._executionPollInterval = pollInterval;
        } else {
            // Execution already complete - if no logs, poll for them (async writes may be pending)
            if (!currentExecution.logs || currentExecution.logs.trim() === '') {
                setTimeout(async () => {
                    const updated = await getExecution(executionId);
                    if (updated.logs) {
                        document.getElementById('execution-logs').textContent = updated.logs;
                    }
                }, 1000);
            }
        }
    } catch (error) {
        showToast('Failed to load execution', 'error');
    }
}

export function cleanup() {
    if (currentEventSource) {
        currentEventSource.close();
        currentEventSource = null;
    }
    if (window._executionPollInterval) {
        clearInterval(window._executionPollInterval);
        window._executionPollInterval = null;
    }
    graphEditor = null;
}
