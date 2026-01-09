// Docker Registries Page
import { state, isAdmin } from '../state.js';
import {
    listRegistries,
    createRegistry,
    updateRegistry,
    deleteRegistry,
    testRegistry,
    setDefaultRegistry,
} from '../api/registries.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { showConfirm } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions } from '../components/data-table.js';

let registries = [];

// Registry type labels
const registryTypeLabels = {
    DOCKER_HUB: 'Docker Hub',
    PRIVATE: 'Private Registry',
    AWS_ECR: 'AWS ECR',
    GCR: 'Google Container Registry',
    ACR: 'Azure Container Registry',
};

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
            <h2 class="section-title">Docker Registries</h2>
            <md-filled-button id="create-registry-btn">
                <span class="material-symbols-outlined" slot="icon">add</span>
                Add Registry
            </md-filled-button>
        </div>

        <div class="info-banner" style="margin-bottom: 16px;">
            <span class="material-symbols-outlined">info</span>
            <div>
                Configure Docker registries for authenticated image pulls. Credentials are encrypted at rest.
                The default registry will be used when pulling images that don't specify a registry.
            </div>
        </div>

        <div class="card">
            <div class="card-content" id="registries-table">
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

    document.getElementById('create-registry-btn')?.addEventListener('click', showCreateRegistryModal);

    setupTableActions('registries-table', {
        edit: showEditRegistryModal,
        test: handleTestConnection,
        default: handleSetDefault,
        delete: handleDeleteRegistry,
    });

    await loadRegistries();
}

// Load registries
async function loadRegistries() {
    try {
        registries = await listRegistries();
        renderRegistriesTable();
    } catch (error) {
        console.error('Failed to load registries:', error);
        showToast('Failed to load registries', 'error');
    }
}

// Render registries table
function renderRegistriesTable() {
    const container = document.getElementById('registries-table');

    const columns = [
        { key: 'name', label: 'Name' },
        {
            key: 'registryType',
            label: 'Type',
            render: (type) => registryTypeLabels[type] || type,
        },
        { key: 'url', label: 'URL' },
        {
            key: 'hasCredentials',
            label: 'Auth',
            render: (hasCreds) => hasCreds
                ? '<span class="status-badge running">Configured</span>'
                : '<span class="status-badge warning">No Auth</span>',
        },
        {
            key: 'isDefault',
            label: 'Default',
            render: (isDefault) => isDefault
                ? '<span class="status-badge running">Default</span>'
                : '-',
        },
        {
            key: 'enabled',
            label: 'Status',
            render: (enabled) => `<span class="status-badge ${enabled ? 'running' : 'stopped'}">${enabled ? 'Enabled' : 'Disabled'}</span>`,
        },
    ];

    const actions = (registry) => renderActions([
        { icon: 'network_check', title: 'Test Connection', action: 'test' },
        { icon: 'edit', title: 'Edit', action: 'edit' },
        ...(!registry.isDefault ? [{ icon: 'star', title: 'Set as Default', action: 'default' }] : []),
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: registries,
        actions,
        emptyMessage: 'No registries configured',
        emptyIcon: 'cloud_upload',
    });
}

// Show create registry modal
function showCreateRegistryModal() {
    const content = `
        <form id="create-registry-form" class="form-grid">
            <md-filled-text-field name="name" label="Registry Name" required style="width: 100%;"></md-filled-text-field>
            <md-filled-select name="registryType" label="Registry Type" required style="width: 100%;" id="registry-type-select">
                <md-select-option value="PRIVATE" selected>Private Registry</md-select-option>
                <md-select-option value="DOCKER_HUB">Docker Hub</md-select-option>
                <md-select-option value="AWS_ECR">AWS ECR</md-select-option>
                <md-select-option value="GCR">Google Container Registry</md-select-option>
                <md-select-option value="ACR">Azure Container Registry</md-select-option>
            </md-filled-select>
            <md-filled-text-field name="url" label="Registry URL" placeholder="https://registry.example.com" style="width: 100%;"></md-filled-text-field>

            <div id="auth-fields">
                <!-- Dynamic auth fields will be inserted here -->
            </div>

            <label class="checkbox-label" style="display: flex; align-items: center; gap: 8px;">
                <md-checkbox name="isDefault"></md-checkbox>
                Set as default registry
            </label>
        </form>
    `;

    openModal('Add Registry', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="submit-create-registry">Create</md-filled-button>
    `);

    // Setup type change listener
    const typeSelect = document.getElementById('registry-type-select');
    typeSelect?.addEventListener('change', () => updateAuthFields(typeSelect.value, false));

    // Initial render
    updateAuthFields('PRIVATE', false);

    document.getElementById('submit-create-registry')?.addEventListener('click', handleCreateRegistry);
}

// Update auth fields based on registry type
function updateAuthFields(registryType, isEdit = false) {
    const container = document.getElementById('auth-fields');
    if (!container) return;

    let html = '';

    switch (registryType) {
        case 'DOCKER_HUB':
        case 'PRIVATE':
            html = `
                <md-filled-text-field name="username" label="Username" style="width: 100%;"></md-filled-text-field>
                <md-filled-text-field name="password" label="Password" type="password" style="width: 100%;"
                    placeholder="${isEdit ? 'Leave empty to keep existing' : ''}"></md-filled-text-field>
            `;
            break;

        case 'AWS_ECR':
            html = `
                <md-filled-text-field name="awsRegion" label="AWS Region" placeholder="us-east-1" style="width: 100%;"></md-filled-text-field>
                <md-filled-text-field name="awsAccessKeyId" label="AWS Access Key ID" style="width: 100%;"></md-filled-text-field>
                <md-filled-text-field name="awsSecretKey" label="AWS Secret Key" type="password" style="width: 100%;"
                    placeholder="${isEdit ? 'Leave empty to keep existing' : ''}"></md-filled-text-field>
                <p class="hint" style="color: var(--md-sys-color-outline); font-size: 12px;">
                    Alternatively, you can provide an ECR authorization token as password.
                </p>
                <md-filled-text-field name="password" label="ECR Auth Token (optional)" type="password" style="width: 100%;"></md-filled-text-field>
            `;
            break;

        case 'GCR':
            html = `
                <md-filled-text-field name="gcpProjectId" label="GCP Project ID" style="width: 100%;"></md-filled-text-field>
                <md-filled-text-field name="gcpServiceAccountJson" label="Service Account JSON" type="password" style="width: 100%;"
                    placeholder="${isEdit ? 'Leave empty to keep existing' : 'Paste service account JSON key'}"></md-filled-text-field>
                <p class="hint" style="color: var(--md-sys-color-outline); font-size: 12px;">
                    Paste the entire JSON key file content from your GCP service account.
                </p>
            `;
            break;

        case 'ACR':
            html = `
                <md-filled-text-field name="azureClientId" label="Azure Client ID" style="width: 100%;"></md-filled-text-field>
                <md-filled-text-field name="azureClientSecret" label="Azure Client Secret" type="password" style="width: 100%;"
                    placeholder="${isEdit ? 'Leave empty to keep existing' : ''}"></md-filled-text-field>
                <md-filled-text-field name="azureTenantId" label="Azure Tenant ID" style="width: 100%;"></md-filled-text-field>
            `;
            break;
    }

    container.innerHTML = html;
}

// Handle create registry
async function handleCreateRegistry() {
    const form = document.getElementById('create-registry-form');
    const data = getFormData(form);

    if (!data.name) {
        showToast('Registry name is required', 'error');
        return;
    }

    try {
        await createRegistry(data);
        showToast('Registry created', 'success');
        closeModal();
        await loadRegistries();
    } catch (error) {
        showToast('Failed to create registry: ' + error.message, 'error');
    }
}

// Show edit registry modal
function showEditRegistryModal(id) {
    const registry = registries.find(r => r.id === id);
    if (!registry) return;

    const content = `
        <form id="edit-registry-form" class="form-grid">
            <md-filled-text-field name="name" label="Registry Name" value="${registry.name}" required style="width: 100%;"></md-filled-text-field>
            <md-filled-text-field name="url" label="Registry URL" value="${registry.url}" style="width: 100%;"></md-filled-text-field>

            <div id="auth-fields">
                <!-- Dynamic auth fields will be inserted here -->
            </div>

            <label class="checkbox-label" style="display: flex; align-items: center; gap: 8px;">
                <md-checkbox name="enabled" ${registry.enabled ? 'checked' : ''}></md-checkbox>
                Enabled
            </label>

            <label class="checkbox-label" style="display: flex; align-items: center; gap: 8px;">
                <md-checkbox name="isDefault" ${registry.isDefault ? 'checked' : ''}></md-checkbox>
                Set as default registry
            </label>
        </form>
    `;

    openModal('Edit Registry', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="submit-edit-registry">Save</md-filled-button>
    `);

    // Render auth fields for this type
    updateAuthFields(registry.registryType, true);

    // Pre-fill non-sensitive fields
    const form = document.getElementById('edit-registry-form');
    if (registry.awsRegion) {
        const field = form.querySelector('[name="awsRegion"]');
        if (field) field.value = registry.awsRegion;
    }
    if (registry.gcpProjectId) {
        const field = form.querySelector('[name="gcpProjectId"]');
        if (field) field.value = registry.gcpProjectId;
    }
    if (registry.azureClientId) {
        const field = form.querySelector('[name="azureClientId"]');
        if (field) field.value = registry.azureClientId;
    }
    if (registry.azureTenantId) {
        const field = form.querySelector('[name="azureTenantId"]');
        if (field) field.value = registry.azureTenantId;
    }

    document.getElementById('submit-edit-registry')?.addEventListener('click', () => handleEditRegistry(id));
}

// Handle edit registry
async function handleEditRegistry(id) {
    const form = document.getElementById('edit-registry-form');
    const data = getFormData(form);

    if (!data.name) {
        showToast('Registry name is required', 'error');
        return;
    }

    // Remove empty password fields to keep existing values
    const passwordFields = ['password', 'awsSecretKey', 'gcpServiceAccountJson', 'azureClientSecret'];
    for (const field of passwordFields) {
        if (data[field] === '') {
            delete data[field];
        }
    }

    try {
        await updateRegistry(id, data);
        showToast('Registry updated', 'success');
        closeModal();
        await loadRegistries();
    } catch (error) {
        showToast('Failed to update registry: ' + error.message, 'error');
    }
}

// Get form data
function getFormData(form) {
    const data = {};
    const fields = form.querySelectorAll('md-filled-text-field, md-filled-select');
    fields.forEach(field => {
        if (field.name) {
            data[field.name] = field.value?.trim() || null;
        }
    });

    const checkboxes = form.querySelectorAll('md-checkbox');
    checkboxes.forEach(checkbox => {
        if (checkbox.name) {
            data[checkbox.name] = checkbox.checked;
        }
    });

    return data;
}

// Handle test connection
async function handleTestConnection(id) {
    const registry = registries.find(r => r.id === id);
    if (!registry) return;

    showToast('Testing connection...', 'info');

    try {
        const result = await testRegistry(id);
        if (result.success) {
            showToast(`Connection to "${registry.name}" successful`, 'success');
        } else {
            showToast(`Connection to "${registry.name}" failed`, 'error');
        }
    } catch (error) {
        showToast('Failed to test connection: ' + error.message, 'error');
    }
}

// Handle set default
async function handleSetDefault(id) {
    const registry = registries.find(r => r.id === id);
    if (!registry) return;

    try {
        await setDefaultRegistry(id);
        showToast(`"${registry.name}" set as default registry`, 'success');
        await loadRegistries();
    } catch (error) {
        showToast('Failed to set default: ' + error.message, 'error');
    }
}

// Handle delete registry
async function handleDeleteRegistry(id) {
    const registry = registries.find(r => r.id === id);
    if (!registry) return;

    const confirmed = await showConfirm({
        title: 'Delete Registry',
        message: `Are you sure you want to delete the registry "${registry.name}"? This action cannot be undone.`,
        confirmText: 'Delete',
        type: 'danger',
    });

    if (!confirmed) return;

    try {
        await deleteRegistry(id);
        showToast('Registry deleted', 'success');
        await loadRegistries();
    } catch (error) {
        showToast('Failed to delete registry: ' + error.message, 'error');
    }
}

// Cleanup
export function cleanup() {
    registries = [];
}
