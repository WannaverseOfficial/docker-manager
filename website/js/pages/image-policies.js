// Image Policies Page
import { state, isAdmin } from '../state.js';
import {
    listPolicies,
    getPolicy,
    createPolicy,
    updatePolicy,
    deletePolicy,
    addRule,
    removeRule,
    validateImage,
} from '../api/image-policies.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { showConfirm } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions } from '../components/data-table.js';

let policies = [];
let currentPolicyId = null;

// Render page
export function render() {
    if (!isAdmin()) {
        return `
            <div class="empty-state">
                <span class="material-symbols-outlined">lock</span>
                <h3>Access Denied</h3>
                <p>You need admin privileges to access this page.</p>
            </div>
        `;
    }

    return `
        <div class="section-header">
            <h2 class="section-title">Image Policies</h2>
            <div style="display: flex; gap: 8px;">
                <md-filled-tonal-button id="test-image-btn">
                    <span class="material-symbols-outlined" slot="icon">science</span>
                    Test Image
                </md-filled-tonal-button>
                <md-filled-button id="create-policy-btn">
                    <span class="material-symbols-outlined" slot="icon">add</span>
                    Create Policy
                </md-filled-button>
            </div>
        </div>

        <div class="info-banner" style="margin-bottom: 16px;">
            <span class="material-symbols-outlined">info</span>
            <div>
                <strong>How policies work:</strong> DENY policies are checked first - if an image matches any DENY rule, it's blocked.
                Then ALLOW policies are checked - if ALLOW policies exist, images must match at least one ALLOW rule.
                Lower priority numbers are evaluated first.
            </div>
        </div>

        <div class="card">
            <div class="card-content" id="policies-table">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>
    `;
}

// Initialize
export async function init() {
    if (!isAdmin()) return;

    document.getElementById('create-policy-btn')?.addEventListener('click', showCreatePolicyModal);
    document.getElementById('test-image-btn')?.addEventListener('click', showTestImageModal);

    setupTableActions('policies-table', {
        edit: showEditPolicyModal,
        rules: showRulesModal,
        delete: handleDeletePolicy,
        toggle: handleTogglePolicy,
    });

    await loadPolicies();
}

// Load policies
async function loadPolicies() {
    try {
        policies = await listPolicies();
        renderPoliciesTable();
    } catch (error) {
        console.error('Failed to load policies:', error);
        showToast('Failed to load policies', 'error');
    }
}

// Render policies table
function renderPoliciesTable() {
    const container = document.getElementById('policies-table');

    const columns = [
        { key: 'name', label: 'Name' },
        {
            key: 'policyType',
            label: 'Type',
            render: (type) => `<span class="status-badge ${type === 'DENY' ? 'stopped' : 'running'}">${type}</span>`,
        },
        { key: 'priority', label: 'Priority' },
        { key: 'ruleCount', label: 'Rules' },
        {
            key: 'enabled',
            label: 'Status',
            render: (enabled) => `<span class="status-badge ${enabled ? 'running' : 'stopped'}">${enabled ? 'Enabled' : 'Disabled'}</span>`,
        },
        {
            key: 'description',
            label: 'Description',
            render: (desc) => desc || '-',
        },
    ];

    const actions = (policy) => renderActions([
        { icon: 'rule', title: 'Manage Rules', action: 'rules' },
        { icon: 'edit', title: 'Edit', action: 'edit' },
        { icon: policy.enabled ? 'toggle_on' : 'toggle_off', title: policy.enabled ? 'Disable' : 'Enable', action: 'toggle' },
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: policies,
        actions,
        emptyMessage: 'No image policies configured',
        emptyIcon: 'policy',
    });
}

// Show create policy modal
function showCreatePolicyModal() {
    const content = `
        <form id="create-policy-form" class="form-grid">
            <md-filled-text-field name="name" label="Policy Name" required style="width: 100%;"></md-filled-text-field>
            <md-filled-text-field name="description" label="Description" style="width: 100%;"></md-filled-text-field>
            <md-filled-select name="policyType" label="Policy Type" required style="width: 100%;">
                <md-select-option value="DENY" selected>DENY - Block matching images</md-select-option>
                <md-select-option value="ALLOW">ALLOW - Only allow matching images</md-select-option>
            </md-filled-select>
            <md-filled-text-field name="priority" label="Priority" type="number" value="100" style="width: 100%;">
            </md-filled-text-field>
            <p class="hint" style="color: var(--md-sys-color-outline); font-size: 12px; margin-top: -8px;">
                Lower numbers = higher priority. Default is 100.
            </p>
        </form>
    `;

    openModal('Create Image Policy', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="submit-create-policy">Create</md-filled-button>
    `);

    document.getElementById('submit-create-policy')?.addEventListener('click', handleCreatePolicy);
}

// Handle create policy
async function handleCreatePolicy() {
    const form = document.getElementById('create-policy-form');
    const name = form.querySelector('[name="name"]').value?.trim();
    const description = form.querySelector('[name="description"]').value?.trim();
    const policyType = form.querySelector('[name="policyType"]').value;
    const priority = parseInt(form.querySelector('[name="priority"]').value) || 100;

    if (!name) {
        showToast('Policy name is required', 'error');
        return;
    }

    try {
        await createPolicy({ name, description, policyType, priority, enabled: true });
        showToast('Policy created', 'success');
        closeModal();
        await loadPolicies();
    } catch (error) {
        showToast('Failed to create policy: ' + error.message, 'error');
    }
}

// Show edit policy modal
async function showEditPolicyModal(id) {
    const policy = policies.find(p => p.id === id);
    if (!policy) return;

    const content = `
        <form id="edit-policy-form" class="form-grid">
            <md-filled-text-field name="name" label="Policy Name" value="${policy.name}" required style="width: 100%;"></md-filled-text-field>
            <md-filled-text-field name="description" label="Description" value="${policy.description || ''}" style="width: 100%;"></md-filled-text-field>
            <md-filled-text-field name="priority" label="Priority" type="number" value="${policy.priority}" style="width: 100%;">
            </md-filled-text-field>
            <label class="checkbox-label" style="display: flex; align-items: center; gap: 8px;">
                <md-checkbox name="enabled" ${policy.enabled ? 'checked' : ''}></md-checkbox>
                Enabled
            </label>
        </form>
    `;

    openModal('Edit Policy', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="submit-edit-policy">Save</md-filled-button>
    `);

    document.getElementById('submit-edit-policy')?.addEventListener('click', () => handleEditPolicy(id));
}

// Handle edit policy
async function handleEditPolicy(id) {
    const form = document.getElementById('edit-policy-form');
    const name = form.querySelector('[name="name"]').value?.trim();
    const description = form.querySelector('[name="description"]').value?.trim();
    const priority = parseInt(form.querySelector('[name="priority"]').value) || 100;
    const enabled = form.querySelector('[name="enabled"]').checked;

    if (!name) {
        showToast('Policy name is required', 'error');
        return;
    }

    try {
        await updatePolicy(id, { name, description, priority, enabled });
        showToast('Policy updated', 'success');
        closeModal();
        await loadPolicies();
    } catch (error) {
        showToast('Failed to update policy: ' + error.message, 'error');
    }
}

// Handle delete policy
async function handleDeletePolicy(id) {
    const policy = policies.find(p => p.id === id);
    if (!policy) return;

    const confirmed = await showConfirm({
        title: 'Delete Policy',
        message: `Are you sure you want to delete the policy "${policy.name}"? This action cannot be undone.`,
        confirmText: 'Delete',
        type: 'danger',
    });

    if (!confirmed) return;

    try {
        await deletePolicy(id);
        showToast('Policy deleted', 'success');
        await loadPolicies();
    } catch (error) {
        showToast('Failed to delete policy: ' + error.message, 'error');
    }
}

// Handle toggle policy
async function handleTogglePolicy(id) {
    const policy = policies.find(p => p.id === id);
    if (!policy) return;

    try {
        await updatePolicy(id, {
            name: policy.name,
            description: policy.description,
            priority: policy.priority,
            enabled: !policy.enabled,
        });
        showToast(`Policy ${policy.enabled ? 'disabled' : 'enabled'}`, 'success');
        await loadPolicies();
    } catch (error) {
        showToast('Failed to update policy: ' + error.message, 'error');
    }
}

// Show rules modal
async function showRulesModal(id) {
    currentPolicyId = id;
    try {
        const policy = await getPolicy(id);
        renderRulesModal(policy);
    } catch (error) {
        showToast('Failed to load policy rules', 'error');
    }
}

// Render rules modal
function renderRulesModal(policy) {
    const rulesHtml = policy.rules && policy.rules.length > 0
        ? policy.rules.map(rule => `
            <div class="rule-item" style="display: flex; align-items: center; justify-content: space-between; padding: 12px; background: var(--md-sys-color-surface-container); border-radius: 8px; margin-bottom: 8px;">
                <div>
                    <code style="font-size: 14px; color: var(--md-sys-color-primary);">${rule.pattern}</code>
                    ${rule.description ? `<div style="font-size: 12px; color: var(--md-sys-color-outline); margin-top: 4px;">${rule.description}</div>` : ''}
                </div>
                <md-icon-button class="action-btn" data-action="remove-rule" data-rule-id="${rule.id}" title="Remove">
                    <span class="material-symbols-outlined" style="color: var(--status-stopped);">delete</span>
                </md-icon-button>
            </div>
        `).join('')
        : '<p style="color: var(--md-sys-color-outline); text-align: center; padding: 24px;">No rules defined</p>';

    const content = `
        <div style="margin-bottom: 16px;">
            <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
                <span class="status-badge ${policy.policyType === 'DENY' ? 'stopped' : 'running'}">${policy.policyType}</span>
                <span style="color: var(--md-sys-color-outline);">Priority: ${policy.priority}</span>
            </div>
            <p style="font-size: 12px; color: var(--md-sys-color-outline);">
                ${policy.policyType === 'DENY'
                    ? 'Images matching any rule will be blocked.'
                    : 'Only images matching at least one rule will be allowed.'}
            </p>
        </div>

        <h4 style="margin-bottom: 12px;">Rules</h4>
        <div id="rules-list">${rulesHtml}</div>

        <div style="border-top: 1px solid var(--md-sys-color-outline-variant); margin-top: 16px; padding-top: 16px;">
            <h4 style="margin-bottom: 12px;">Add Rule</h4>
            <div style="display: flex; gap: 8px; flex-wrap: wrap;">
                <md-filled-text-field id="new-rule-pattern" label="Pattern" placeholder="nginx:*, myregistry.com/*" style="flex: 1; min-width: 200px;"></md-filled-text-field>
                <md-filled-text-field id="new-rule-description" label="Description (optional)" style="flex: 1; min-width: 200px;"></md-filled-text-field>
                <md-filled-button id="add-rule-btn">
                    <span class="material-symbols-outlined" slot="icon">add</span>
                    Add
                </md-filled-button>
            </div>
            <p style="font-size: 12px; color: var(--md-sys-color-outline); margin-top: 8px;">
                Patterns support wildcards: <code>*</code> matches any characters. Examples: <code>nginx:*</code>, <code>myregistry.com/*</code>, <code>ubuntu:20.04</code>
            </p>
        </div>
    `;

    openModal(`Rules: ${policy.name}`, content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Close</md-text-button>
    `);

    // Setup event listeners
    document.getElementById('add-rule-btn')?.addEventListener('click', handleAddRule);
    document.querySelectorAll('[data-action="remove-rule"]').forEach(btn => {
        btn.addEventListener('click', () => handleRemoveRule(btn.dataset.ruleId));
    });
}

// Handle add rule
async function handleAddRule() {
    const pattern = document.getElementById('new-rule-pattern')?.value?.trim();
    const description = document.getElementById('new-rule-description')?.value?.trim();

    if (!pattern) {
        showToast('Pattern is required', 'error');
        return;
    }

    try {
        await addRule(currentPolicyId, { pattern, description });
        showToast('Rule added', 'success');
        // Reload the modal
        const policy = await getPolicy(currentPolicyId);
        renderRulesModal(policy);
    } catch (error) {
        showToast('Failed to add rule: ' + error.message, 'error');
    }
}

// Handle remove rule
async function handleRemoveRule(ruleId) {
    const confirmed = await showConfirm({
        title: 'Remove Rule',
        message: 'Are you sure you want to remove this rule?',
        confirmText: 'Remove',
        type: 'danger',
    });

    if (!confirmed) return;

    try {
        await removeRule(currentPolicyId, ruleId);
        showToast('Rule removed', 'success');
        // Reload the modal
        const policy = await getPolicy(currentPolicyId);
        renderRulesModal(policy);
        await loadPolicies(); // Refresh rule count in table
    } catch (error) {
        showToast('Failed to remove rule: ' + error.message, 'error');
    }
}

// Show test image modal
function showTestImageModal() {
    const content = `
        <div class="form-grid">
            <md-filled-text-field id="test-image-name" label="Image Name" placeholder="nginx:latest, myregistry.com/myimage:tag" style="width: 100%;"></md-filled-text-field>
            <md-filled-button id="run-test-btn" style="width: fit-content;">
                <span class="material-symbols-outlined" slot="icon">play_arrow</span>
                Test
            </md-filled-button>
        </div>
        <div id="test-result" style="margin-top: 16px;"></div>
    `;

    openModal('Test Image Against Policies', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Close</md-text-button>
    `);

    document.getElementById('run-test-btn')?.addEventListener('click', handleTestImage);
    document.getElementById('test-image-name')?.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') handleTestImage();
    });
}

// Handle test image
async function handleTestImage() {
    const imageName = document.getElementById('test-image-name')?.value?.trim();
    const resultDiv = document.getElementById('test-result');

    if (!imageName) {
        showToast('Image name is required', 'error');
        return;
    }

    try {
        const result = await validateImage(imageName);

        const icon = result.allowed ? 'check_circle' : 'block';
        const color = result.allowed ? 'var(--status-running)' : 'var(--status-stopped)';
        const status = result.allowed ? 'ALLOWED' : 'DENIED';

        resultDiv.innerHTML = `
            <div style="display: flex; align-items: center; gap: 12px; padding: 16px; background: var(--md-sys-color-surface-container); border-radius: 8px; border-left: 4px solid ${color};">
                <span class="material-symbols-outlined" style="color: ${color}; font-size: 32px;">${icon}</span>
                <div>
                    <div style="font-weight: 500; font-size: 16px; color: ${color};">${status}</div>
                    <div style="font-size: 14px; color: var(--md-sys-color-on-surface);">${result.reason}</div>
                    ${result.policyName ? `<div style="font-size: 12px; color: var(--md-sys-color-outline); margin-top: 4px;">Policy: ${result.policyName}</div>` : ''}
                </div>
            </div>
        `;
    } catch (error) {
        resultDiv.innerHTML = `
            <div style="padding: 16px; background: var(--md-sys-color-error-container); border-radius: 8px; color: var(--md-sys-color-on-error-container);">
                Error: ${error.message}
            </div>
        `;
    }
}

// Cleanup
export function cleanup() {
    policies = [];
    currentPolicyId = null;
}
