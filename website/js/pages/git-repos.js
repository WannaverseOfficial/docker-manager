// Git Repositories Page
import { state } from '../state.js';
import {
    listRepositories, createRepository, updateRepository, deleteRepository,
    deployRepository, pollRepository, getRepository, listJobs, getJobLogs,
    cancelJob, streamJobLogs, checkGitDrift
} from '../api/git.js';
import { loadHosts } from '../api/docker.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete, showConfirm } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions, formatDate } from '../components/data-table.js';
import { renderTriggerBadges, renderJobStatus } from '../components/status-badge.js';
import { truncate } from '../utils/format.js';

let repositories = [];
let jobs = [];
let currentEventSource = null;

// Render page
export function render() {
    return `
        <md-tabs id="git-tabs">
            <md-primary-tab id="tab-repos" aria-controls="panel-repos">Repositories</md-primary-tab>
            <md-primary-tab id="tab-jobs" aria-controls="panel-jobs">Deployment Jobs</md-primary-tab>
        </md-tabs>

        <div id="panel-repos" class="tab-panel">
            <div class="section-header" style="margin-top: 16px;">
                <h2 class="section-title">Repositories</h2>
                <md-filled-button id="add-repo-btn">
                    <span class="material-symbols-outlined" slot="icon">add</span>
                    Add Repository
                </md-filled-button>
            </div>
            <div class="card">
                <div class="card-content" id="repos-table">
                    <div class="loading-container">
                        <md-circular-progress indeterminate></md-circular-progress>
                    </div>
                </div>
            </div>
        </div>

        <div id="panel-jobs" class="tab-panel hidden">
            <div class="section-header" style="margin-top: 16px;">
                <h2 class="section-title">Deployment Jobs</h2>
            </div>
            <div class="card">
                <div class="card-content" id="jobs-table">
                    <div class="loading-container">
                        <md-circular-progress indeterminate></md-circular-progress>
                    </div>
                </div>
            </div>
        </div>
    `;
}

// Initialize
export async function init() {
    // Setup tabs
    const tabs = document.getElementById('git-tabs');
    tabs?.addEventListener('change', () => {
        const activeTab = tabs.activeTabIndex;
        document.getElementById('panel-repos').classList.toggle('hidden', activeTab !== 0);
        document.getElementById('panel-jobs').classList.toggle('hidden', activeTab !== 1);
    });

    document.getElementById('add-repo-btn')?.addEventListener('click', showAddRepoModal);

    setupTableActions('repos-table', {
        deploy: handleDeploy,
        poll: handlePoll,
        checkDrift: handleCheckDrift,
        details: showRepoDetails,
        edit: showEditModal,
        delete: handleDeleteRepo,
    });

    setupTableActions('jobs-table', {
        logs: showJobLogs,
        cancel: handleCancelJob,
    });

    await loadData();
}

// Load data
async function loadData() {
    try {
        [repositories, jobs] = await Promise.all([
            listRepositories(),
            listJobs()
        ]);
        renderReposTable();
        renderJobsTable();
    } catch (error) {
        console.error('Failed to load git data:', error);
        showToast('Failed to load repositories', 'error');
    }
}

// Render drift status badge
function renderDriftStatus(status) {
    const statusMap = {
        SYNCED: { class: 'running', text: 'Synced', icon: 'check_circle' },
        BEHIND: { class: 'warning', text: 'Behind', icon: 'warning' },
        UNKNOWN: { class: 'created', text: 'Unknown', icon: 'help' },
        ERROR: { class: 'stopped', text: 'Error', icon: 'error' },
    };
    const s = statusMap[status] || statusMap.UNKNOWN;
    return `<span class="status-badge ${s.class}" title="Drift: ${s.text}"><span class="material-symbols-outlined" style="font-size: 14px; vertical-align: middle;">${s.icon}</span> ${s.text}</span>`;
}

// Render repos table
function renderReposTable() {
    const container = document.getElementById('repos-table');

    const columns = [
        { key: 'name', label: 'Name' },
        { key: 'repositoryUrl', label: 'URL', truncate: true, render: (v) => truncate(v, 35) },
        { key: 'branch', label: 'Branch', mono: true },
        { key: 'driftStatus', label: 'Drift', render: renderDriftStatus },
        { key: 'webhookEnabled', label: 'Triggers', render: (_, repo) => renderTriggerBadges(repo) },
        { key: 'lastDeployedAt', label: 'Last Deploy', render: (v) => v ? formatDate(v) : 'Never' },
    ];

    const actions = (repo) => renderActions([
        { icon: 'rocket_launch', title: 'Deploy', action: 'deploy' },
        { icon: 'sync', title: 'Poll', action: 'poll' },
        { icon: 'compare_arrows', title: 'Check Drift', action: 'checkDrift' },
        { icon: 'info', title: 'Details', action: 'details' },
        { icon: 'edit', title: 'Edit', action: 'edit' },
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: repositories,
        actions,
        emptyMessage: 'No repositories configured',
        emptyIcon: 'code',
    });
}

// Render jobs table
function renderJobsTable() {
    const container = document.getElementById('jobs-table');

    const columns = [
        { key: 'gitRepositoryName', label: 'Repository' },
        { key: 'status', label: 'Status', render: renderJobStatus },
        { key: 'triggerType', label: 'Trigger' },
        { key: 'commitSha', label: 'Commit', render: (v) => v?.substring(0, 7) || 'N/A', mono: true },
        { key: 'createdAt', label: 'Started', render: formatDate },
    ];

    const actions = (job) => renderActions([
        { icon: 'description', title: 'View Logs', action: 'logs' },
        {
            icon: 'cancel',
            title: 'Cancel',
            action: 'cancel',
            hidden: job.status !== 'PENDING' && job.status !== 'RUNNING'
        },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: jobs,
        actions,
        emptyMessage: 'No deployment jobs',
        emptyIcon: 'work_history',
    });
}

// Show add repo modal
async function showAddRepoModal() {
    const hosts = await loadHosts();
    const hostOptions = hosts.map(h =>
        `<md-select-option value="${h.id}">${h.dockerHostUrl}</md-select-option>`
    ).join('');

    const content = `
        <form id="add-repo-form" class="dialog-form">
            <md-filled-text-field id="repo-name" label="Name" required></md-filled-text-field>
            <md-filled-text-field id="repo-url" label="Repository URL" placeholder="https://github.com/user/repo.git" required></md-filled-text-field>
            <md-filled-text-field id="repo-branch" label="Branch" value="main"></md-filled-text-field>

            <md-filled-select id="repo-auth" label="Authentication">
                <md-select-option value="NONE" selected>None (Public)</md-select-option>
                <md-select-option value="PAT">Personal Access Token</md-select-option>
                <md-select-option value="SSH_KEY">SSH Key</md-select-option>
            </md-filled-select>

            <md-filled-text-field id="repo-token" label="Access Token" type="password" class="hidden"></md-filled-text-field>

            <md-filled-select id="repo-deploy-type" label="Deployment Type">
                <md-select-option value="DOCKER_COMPOSE" selected>Docker Compose</md-select-option>
                <md-select-option value="DOCKERFILE">Dockerfile</md-select-option>
            </md-filled-select>

            <md-filled-text-field id="repo-compose-path" label="Compose File Path" value="docker-compose.yml"></md-filled-text-field>

            <md-filled-select id="repo-host" label="Docker Host" required>
                ${hostOptions}
            </md-filled-select>

            <div style="display: flex; flex-direction: column; gap: 8px; margin-top: 8px;">
                <label><md-checkbox id="repo-webhook"></md-checkbox> Enable Webhook</label>
                <label><md-checkbox id="repo-polling"></md-checkbox> Enable Polling</label>
            </div>
        </form>
    `;

    openModal('Add Repository', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="add-repo-submit">Add</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('repo-auth')?.addEventListener('change', (e) => {
            const tokenField = document.getElementById('repo-token');
            tokenField.classList.toggle('hidden', e.target.value !== 'PAT');
        });

        document.getElementById('add-repo-submit')?.addEventListener('click', handleAddRepo);
    }, 100);
}

// Handle add repo
async function handleAddRepo() {
    const data = {
        name: document.getElementById('repo-name')?.value?.trim(),
        repositoryUrl: document.getElementById('repo-url')?.value?.trim(),
        branch: document.getElementById('repo-branch')?.value?.trim() || 'main',
        authType: document.getElementById('repo-auth')?.value || 'NONE',
        token: document.getElementById('repo-token')?.value || null,
        deploymentType: document.getElementById('repo-deploy-type')?.value,
        composePath: document.getElementById('repo-compose-path')?.value,
        dockerHostId: document.getElementById('repo-host')?.value,
        webhookEnabled: document.getElementById('repo-webhook')?.checked || false,
        pollingEnabled: document.getElementById('repo-polling')?.checked || false,
        pollingIntervalSeconds: 300,
    };

    if (!data.name || !data.repositoryUrl || !data.dockerHostId) {
        showToast('Please fill required fields', 'error');
        return;
    }

    try {
        const result = await createRepository(data);
        closeModal();
        showToast('Repository added', 'success');

        if (data.webhookEnabled && result.webhookUrl) {
            showWebhookInfo(result.webhookUrl);
        }

        await loadData();
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

// Show webhook info
function showWebhookInfo(url) {
    const content = `
        <p style="margin-bottom: 16px;">Configure this URL in your Git provider's webhook settings:</p>
        <div style="display: flex; gap: 8px; margin-bottom: 16px;">
            <md-filled-text-field id="webhook-url" value="${url}" readonly style="flex: 1;"></md-filled-text-field>
            <md-filled-button id="copy-webhook">Copy</md-filled-button>
        </div>
        <div class="form-hint">
            <strong>GitHub:</strong> Settings → Webhooks → Add webhook<br>
            <strong>GitLab:</strong> Settings → Webhooks<br>
            Set Content-Type to application/json, trigger on Push events.
        </div>
    `;

    openModal('Webhook Setup', content, `
        <md-filled-button onclick="document.getElementById('app-dialog').close()">Done</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('copy-webhook')?.addEventListener('click', () => {
            navigator.clipboard.writeText(url);
            showToast('Copied to clipboard', 'success');
        });
    }, 100);
}

// Handle deploy
async function handleDeploy(id) {
    const confirmed = await showConfirm({
        title: 'Deploy Repository',
        message: 'Start deployment now?',
        confirmText: 'Deploy',
        type: 'info',
        icon: 'rocket_launch'
    });

    if (!confirmed) return;

    try {
        const job = await deployRepository(id);
        showToast('Deployment started', 'success');
        await loadData();
        showJobLogs(job.id);
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

// Handle poll
async function handlePoll(id) {
    try {
        const result = await pollRepository(id);
        if (result.triggered) {
            showToast('Changes detected, deployment started', 'success');
        } else {
            showToast('No changes detected', 'info');
        }
        await loadData();
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

// Handle check drift
async function handleCheckDrift(id) {
    try {
        showToast('Checking for drift...', 'info');
        const result = await checkGitDrift(id);
        if (result.driftStatus === 'BEHIND') {
            showToast('Repository is behind remote!', 'warning');
        } else if (result.driftStatus === 'SYNCED') {
            showToast('Repository is up to date', 'success');
        } else {
            showToast(`Drift status: ${result.driftStatus}`, 'info');
        }
        await loadData();
    } catch (error) {
        showToast('Failed to check drift: ' + error.message, 'error');
    }
}

// Show repo details
async function showRepoDetails(id) {
    try {
        const repo = await getRepository(id);
        const content = `
            <div class="info-grid">
                <span class="info-label">Name:</span><span class="info-value">${repo.name}</span>
                <span class="info-label">URL:</span><span class="info-value" style="word-break: break-all;">${repo.repositoryUrl}</span>
                <span class="info-label">Branch:</span><span class="info-value">${repo.branch}</span>
                <span class="info-label">Type:</span><span class="info-value">${repo.deploymentType}</span>
                <span class="info-label">Triggers:</span><span class="info-value">${renderTriggerBadges(repo)}</span>
                <span class="info-label">Last Commit:</span><span class="info-value mono">${repo.lastCommitSha || 'N/A'}</span>
                <span class="info-label">Last Deploy:</span><span class="info-value">${repo.lastDeployedAt ? formatDate(repo.lastDeployedAt) : 'Never'}</span>
            </div>
            ${repo.webhookEnabled ? `
                <div style="margin-top: 16px;">
                    <label class="form-label">Webhook URL</label>
                    <div style="display: flex; gap: 8px;">
                        <md-filled-text-field value="${repo.webhookUrl}" readonly style="flex: 1; font-size: 12px;"></md-filled-text-field>
                        <md-text-button onclick="navigator.clipboard.writeText('${repo.webhookUrl}'); alert('Copied!')">Copy</md-text-button>
                    </div>
                </div>
            ` : ''}
        `;

        openModal('Repository Details', content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `);
    } catch (error) {
        showToast('Failed to load details', 'error');
    }
}

// Show edit modal
async function showEditModal(id) {
    const repo = await getRepository(id);

    const content = `
        <form class="dialog-form">
            <md-filled-text-field id="edit-name" label="Name" value="${repo.name}"></md-filled-text-field>
            <md-filled-text-field id="edit-branch" label="Branch" value="${repo.branch}"></md-filled-text-field>
            <div style="display: flex; flex-direction: column; gap: 8px; margin-top: 8px;">
                <label><md-checkbox id="edit-webhook" ${repo.webhookEnabled ? 'checked' : ''}></md-checkbox> Enable Webhook</label>
                <label><md-checkbox id="edit-polling" ${repo.pollingEnabled ? 'checked' : ''}></md-checkbox> Enable Polling</label>
            </div>
        </form>
    `;

    openModal('Edit Repository', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="edit-submit">Save</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('edit-submit')?.addEventListener('click', async () => {
            try {
                await updateRepository(id, {
                    name: document.getElementById('edit-name')?.value,
                    branch: document.getElementById('edit-branch')?.value,
                    webhookEnabled: document.getElementById('edit-webhook')?.checked,
                    pollingEnabled: document.getElementById('edit-polling')?.checked,
                });
                closeModal();
                showToast('Repository updated', 'success');
                await loadData();
            } catch (e) {
                showToast('Failed: ' + e.message, 'error');
            }
        });
    }, 100);
}

// Handle delete repo
async function handleDeleteRepo(id) {
    const repo = repositories.find(r => r.id === id);
    const confirmed = await confirmDelete('Repository', `<p><strong>${repo?.name}</strong></p>`);
    if (!confirmed) return;

    try {
        await deleteRepository(id);
        showToast('Repository deleted', 'success');
        await loadData();
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

// Show job logs
async function showJobLogs(id) {
    const job = jobs.find(j => j.id === id);

    const content = `
        <div style="margin-bottom: 8px;">
            <strong>${job?.gitRepositoryName || 'Job'}</strong> - ${renderJobStatus(job?.status)}
        </div>
        <div id="job-logs" class="terminal" style="min-height: 300px; max-height: 400px;"></div>
    `;

    openModal('Deployment Logs', content, `
        <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
    `);

    // Load logs
    const logsContainer = document.getElementById('job-logs');

    if (job?.status === 'RUNNING') {
        // Stream live logs
        logsContainer.textContent = 'Connecting...';
        currentEventSource = streamJobLogs(id, {
            onLog: (line) => {
                if (logsContainer.textContent === 'Connecting...') {
                    logsContainer.textContent = '';
                }
                logsContainer.textContent += line + '\n';
                logsContainer.scrollTop = logsContainer.scrollHeight;
            },
            onComplete: () => {
                logsContainer.textContent += '\n--- Deployment Complete ---\n';
                loadData();
            },
            onError: () => {
                logsContainer.textContent += '\n--- Connection Lost ---\n';
            }
        });
    } else {
        // Load static logs
        try {
            const result = await getJobLogs(id);
            logsContainer.textContent = result.logs || '(no logs available)';
        } catch (e) {
            logsContainer.textContent = 'Failed to load logs';
        }
    }
}

// Handle cancel job
async function handleCancelJob(id) {
    try {
        await cancelJob(id);
        showToast('Job cancelled', 'success');
        await loadData();
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

export function cleanup() {
    if (currentEventSource) {
        currentEventSource.close();
        currentEventSource = null;
    }
    repositories = [];
    jobs = [];
}
