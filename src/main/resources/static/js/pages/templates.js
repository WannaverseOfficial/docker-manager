// Templates Page
import { listTemplates, getTemplate, createTemplate, updateTemplate, deleteTemplate, duplicateTemplate, getCategories, reseedTemplates } from '../api/templates.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete, showConfirm } from '../components/confirm-dialog.js';
import { getCurrentUser } from '../api/auth.js';

let templates = [];
let categories = [];
let selectedCategory = null;
let searchQuery = '';
let currentView = 'list'; // 'list' or 'form'
let editingTemplate = null;

export function render() {
    return `
        <div id="templates-page">
            <div id="templates-list-view">
                <div class="section-header">
                    <h2 class="section-title">Templates</h2>
                    <div class="card-actions">
                        <md-outlined-button id="reseed-templates-btn" class="admin-only hidden">
                            <span class="material-symbols-outlined" slot="icon">refresh</span>
                            Reseed System Templates
                        </md-outlined-button>
                        <md-filled-button id="create-template-btn">
                            <span class="material-symbols-outlined" slot="icon">add</span>
                            Create Template
                        </md-filled-button>
                    </div>
                </div>

                <div class="templates-toolbar">
                    <div class="templates-search">
                        <md-outlined-text-field
                            id="templates-search-input"
                            placeholder="Search templates..."
                            type="search">
                            <span class="material-symbols-outlined" slot="leading-icon">search</span>
                        </md-outlined-text-field>
                    </div>
                    <div id="templates-categories" class="category-chips">
                        <!-- Categories populated dynamically -->
                    </div>
                </div>

                <div class="card">
                    <div class="card-content" id="templates-grid">
                        <div class="loading-container">
                            <md-circular-progress indeterminate></md-circular-progress>
                        </div>
                    </div>
                </div>
            </div>

            <div id="templates-form-view" class="hidden">
                <div class="section-header">
                    <div class="section-header-left">
                        <md-icon-button id="back-to-list-btn">
                            <span class="material-symbols-outlined">arrow_back</span>
                        </md-icon-button>
                        <h2 class="section-title" id="form-title">Create Template</h2>
                    </div>
                    <div class="card-actions">
                        <md-outlined-button id="cancel-template-btn">Cancel</md-outlined-button>
                        <md-filled-button id="save-template-btn">Save Template</md-filled-button>
                    </div>
                </div>

                <div class="card">
                    <div class="card-content">
                        <form id="template-form" class="template-form">
                            <div class="form-row">
                                <md-filled-text-field
                                    id="template-name"
                                    label="Template Name"
                                    required>
                                </md-filled-text-field>
                                <md-filled-select id="template-type" label="Type">
                                    <md-select-option value="CONTAINER" selected>
                                        <span slot="headline">Single Container</span>
                                    </md-select-option>
                                    <md-select-option value="COMPOSE">
                                        <span slot="headline">Docker Compose</span>
                                    </md-select-option>
                                </md-filled-select>
                            </div>

                            <md-filled-text-field
                                id="template-description"
                                label="Description"
                                type="textarea"
                                rows="2">
                            </md-filled-text-field>

                            <div class="form-row">
                                <md-filled-text-field
                                    id="template-category"
                                    label="Category"
                                    placeholder="e.g., Databases, Web Servers">
                                </md-filled-text-field>
                                <md-filled-text-field
                                    id="template-logo"
                                    label="Logo URL"
                                    placeholder="https://example.com/logo.png">
                                </md-filled-text-field>
                            </div>

                            <div id="container-fields">
                                <h3 class="form-section-title">Container Configuration</h3>

                                <md-filled-text-field
                                    id="template-image"
                                    label="Image"
                                    placeholder="nginx:latest">
                                </md-filled-text-field>

                                <div class="form-row">
                                    <md-filled-text-field
                                        id="template-ports"
                                        label="Default Ports"
                                        placeholder="8080:80, 443:443">
                                    </md-filled-text-field>
                                    <md-filled-text-field
                                        id="template-network"
                                        label="Default Network"
                                        placeholder="bridge">
                                    </md-filled-text-field>
                                </div>

                                <md-filled-text-field
                                    id="template-env"
                                    label="Environment Variables"
                                    type="textarea"
                                    rows="3"
                                    placeholder="KEY=value&#10;ANOTHER_KEY=value">
                                </md-filled-text-field>

                                <md-filled-text-field
                                    id="template-volumes"
                                    label="Volumes"
                                    type="textarea"
                                    rows="2"
                                    placeholder="/host/path:/container/path&#10;volume_name:/data">
                                </md-filled-text-field>

                                <md-filled-text-field
                                    id="template-user"
                                    label="Run as User"
                                    placeholder="1000:1000">
                                </md-filled-text-field>
                            </div>

                            <div id="compose-fields" class="hidden">
                                <h3 class="form-section-title">Compose Configuration</h3>

                                <md-filled-text-field
                                    id="template-compose"
                                    label="Docker Compose YAML"
                                    type="textarea"
                                    class="compose-editor"
                                    rows="20"
                                    placeholder="version: '3.8'&#10;services:&#10;  web:&#10;    image: nginx:latest">
                                </md-filled-text-field>
                            </div>

                            <div class="form-row">
                                <md-filled-text-field
                                    id="template-platform"
                                    label="Platform"
                                    placeholder="linux">
                                </md-filled-text-field>
                                <md-filled-text-field
                                    id="template-docs"
                                    label="Documentation URL"
                                    placeholder="https://docs.example.com">
                                </md-filled-text-field>
                            </div>
                        </form>
                    </div>
                </div>
            </div>
        </div>
    `;
}

export async function init() {
    // Event listeners
    document.getElementById('create-template-btn')?.addEventListener('click', showCreateForm);
    document.getElementById('reseed-templates-btn')?.addEventListener('click', handleReseed);
    document.getElementById('back-to-list-btn')?.addEventListener('click', showListView);
    document.getElementById('cancel-template-btn')?.addEventListener('click', showListView);
    document.getElementById('save-template-btn')?.addEventListener('click', handleSaveTemplate);

    // Search input
    const searchInput = document.getElementById('templates-search-input');
    searchInput?.addEventListener('input', (e) => {
        searchQuery = e.target.value;
        renderTemplatesGrid();
    });

    // Template type toggle
    const typeSelect = document.getElementById('template-type');
    typeSelect?.addEventListener('change', (e) => {
        toggleTemplateTypeFields(e.target.value);
    });

    // Check admin status
    try {
        const user = await getCurrentUser();
        if (user?.role === 'ADMIN') {
            document.querySelectorAll('.admin-only').forEach(el => el.classList.remove('hidden'));
        }
    } catch (e) {
        // Ignore
    }

    await loadData();
}

async function loadData() {
    try {
        const [templateList, categoryList] = await Promise.all([
            listTemplates(),
            getCategories()
        ]);
        templates = templateList;
        categories = categoryList;
        renderCategories();
        renderTemplatesGrid();
    } catch (error) {
        console.error('Failed to load templates:', error);
        showToast('Failed to load templates', 'error');
    }
}

function renderCategories() {
    const container = document.getElementById('templates-categories');
    if (!container) return;

    const categoryChips = categories.map(cat => `
        <md-filter-chip
            label="${cat}"
            data-category="${cat}"
            ${selectedCategory === cat ? 'selected' : ''}>
        </md-filter-chip>
    `).join('');

    container.innerHTML = `
        <md-filter-chip
            label="All"
            data-category=""
            ${!selectedCategory ? 'selected' : ''}>
        </md-filter-chip>
        ${categoryChips}
    `;

    // Add event listeners
    container.querySelectorAll('md-filter-chip').forEach(chip => {
        chip.addEventListener('click', () => {
            const category = chip.dataset.category;
            selectedCategory = category || null;
            renderCategories();
            renderTemplatesGrid();
        });
    });
}

function renderTemplatesGrid() {
    const container = document.getElementById('templates-grid');
    if (!container) return;

    let filtered = templates;

    // Filter by category
    if (selectedCategory) {
        filtered = filtered.filter(t => t.category === selectedCategory);
    }

    // Filter by search
    if (searchQuery) {
        const query = searchQuery.toLowerCase();
        filtered = filtered.filter(t =>
            t.name?.toLowerCase().includes(query) ||
            t.description?.toLowerCase().includes(query) ||
            t.category?.toLowerCase().includes(query)
        );
    }

    if (filtered.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <span class="material-symbols-outlined">library_books</span>
                <h3>No templates found</h3>
                <p>${searchQuery || selectedCategory ? 'Try adjusting your filters' : 'Create your first template'}</p>
            </div>
        `;
        return;
    }

    const cards = filtered.map(template => `
        <div class="template-card" data-id="${template.id}">
            <div class="template-card-header">
                ${template.logo
                    ? `<img src="${template.logo}" alt="" class="template-logo" onerror="this.style.display='none'">`
                    : `<span class="material-symbols-outlined template-logo-icon">${template.type === 'COMPOSE' ? 'widgets' : 'deployed_code'}</span>`
                }
                <div class="template-info">
                    <h3 class="template-name">${template.name}</h3>
                    <span class="template-type-badge ${template.type?.toLowerCase()}">${template.type}</span>
                </div>
            </div>
            <p class="template-description">${template.description || 'No description'}</p>
            <div class="template-meta">
                <span class="template-category">${template.category || 'Uncategorized'}</span>
                ${template.system
                    ? '<span class="system-badge">System</span>'
                    : '<span class="user-badge">Custom</span>'
                }
            </div>
            <div class="template-actions">
                <md-icon-button class="action-duplicate" title="Duplicate">
                    <span class="material-symbols-outlined">content_copy</span>
                </md-icon-button>
                ${!template.system ? `
                    <md-icon-button class="action-edit" title="Edit">
                        <span class="material-symbols-outlined">edit</span>
                    </md-icon-button>
                    <md-icon-button class="action-delete" title="Delete">
                        <span class="material-symbols-outlined">delete</span>
                    </md-icon-button>
                ` : ''}
            </div>
        </div>
    `).join('');

    container.innerHTML = `<div class="templates-grid">${cards}</div>`;

    // Add event listeners
    container.querySelectorAll('.template-card').forEach(card => {
        const id = card.dataset.id;

        card.querySelector('.action-duplicate')?.addEventListener('click', (e) => {
            e.stopPropagation();
            handleDuplicate(id);
        });

        card.querySelector('.action-edit')?.addEventListener('click', (e) => {
            e.stopPropagation();
            showEditForm(id);
        });

        card.querySelector('.action-delete')?.addEventListener('click', (e) => {
            e.stopPropagation();
            handleDelete(id);
        });

        // Card click to view details
        card.addEventListener('click', () => showTemplateDetails(id));
    });
}

function showListView() {
    currentView = 'list';
    editingTemplate = null;
    document.getElementById('templates-list-view')?.classList.remove('hidden');
    document.getElementById('templates-form-view')?.classList.add('hidden');
    clearForm();
}

function showCreateForm() {
    currentView = 'form';
    editingTemplate = null;
    document.getElementById('form-title').textContent = 'Create Template';
    document.getElementById('templates-list-view')?.classList.add('hidden');
    document.getElementById('templates-form-view')?.classList.remove('hidden');
    clearForm();
    toggleTemplateTypeFields('CONTAINER');
}

async function showEditForm(id) {
    try {
        const template = await getTemplate(id);
        if (template.system) {
            showToast('System templates cannot be edited. Duplicate it first.', 'info');
            return;
        }

        currentView = 'form';
        editingTemplate = template;
        document.getElementById('form-title').textContent = 'Edit Template';
        document.getElementById('templates-list-view')?.classList.add('hidden');
        document.getElementById('templates-form-view')?.classList.remove('hidden');

        populateForm(template);
    } catch (error) {
        showToast('Failed to load template', 'error');
    }
}

function populateForm(template) {
    document.getElementById('template-name').value = template.name || '';
    document.getElementById('template-type').value = template.type || 'CONTAINER';
    document.getElementById('template-description').value = template.description || '';
    document.getElementById('template-category').value = template.category || '';
    document.getElementById('template-logo').value = template.logo || '';
    document.getElementById('template-image').value = template.imageName || '';
    document.getElementById('template-ports').value = template.defaultPorts || '';
    document.getElementById('template-network').value = template.defaultNetwork || '';
    document.getElementById('template-env').value = template.defaultEnv || '';
    document.getElementById('template-volumes').value = template.defaultVolumes || '';
    document.getElementById('template-user').value = template.defaultUser || '';
    document.getElementById('template-compose').value = template.composeContent || '';
    document.getElementById('template-platform').value = template.platform || '';
    document.getElementById('template-docs').value = template.documentation || '';

    toggleTemplateTypeFields(template.type || 'CONTAINER');
}

function clearForm() {
    document.getElementById('template-form')?.reset();
    document.getElementById('template-type').value = 'CONTAINER';
}

function toggleTemplateTypeFields(type) {
    const containerFields = document.getElementById('container-fields');
    const composeFields = document.getElementById('compose-fields');

    if (type === 'COMPOSE') {
        containerFields?.classList.add('hidden');
        composeFields?.classList.remove('hidden');
    } else {
        containerFields?.classList.remove('hidden');
        composeFields?.classList.add('hidden');
    }
}

async function handleSaveTemplate() {
    const btn = document.getElementById('save-template-btn');
    const name = document.getElementById('template-name')?.value?.trim();
    const type = document.getElementById('template-type')?.value;

    if (!name) {
        showToast('Template name is required', 'error');
        return;
    }

    const templateData = {
        name,
        type,
        description: document.getElementById('template-description')?.value?.trim() || null,
        category: document.getElementById('template-category')?.value?.trim() || null,
        logo: document.getElementById('template-logo')?.value?.trim() || null,
        imageName: document.getElementById('template-image')?.value?.trim() || null,
        defaultPorts: document.getElementById('template-ports')?.value?.trim() || null,
        defaultNetwork: document.getElementById('template-network')?.value?.trim() || null,
        defaultEnv: document.getElementById('template-env')?.value?.trim() || null,
        defaultVolumes: document.getElementById('template-volumes')?.value?.trim() || null,
        defaultUser: document.getElementById('template-user')?.value?.trim() || null,
        composeContent: document.getElementById('template-compose')?.value?.trim() || null,
        platform: document.getElementById('template-platform')?.value?.trim() || null,
        documentation: document.getElementById('template-docs')?.value?.trim() || null,
    };

    if (btn) {
        btn.disabled = true;
        btn.textContent = 'Saving...';
    }

    try {
        if (editingTemplate) {
            await updateTemplate(editingTemplate.id, templateData);
            showToast('Template updated', 'success');
        } else {
            await createTemplate(templateData);
            showToast('Template created', 'success');
        }
        showListView();
        await loadData();
    } catch (error) {
        showToast('Failed to save template: ' + error.message, 'error');
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = 'Save Template';
        }
    }
}

async function handleDuplicate(id) {
    const template = templates.find(t => t.id === id);
    if (!template) return;

    const confirmed = await showConfirm({
        title: 'Duplicate Template',
        message: `Create a copy of "${template.name}"?`,
        confirmText: 'Duplicate',
        type: 'info',
    });

    if (!confirmed) return;

    try {
        const newName = `${template.name} (Copy)`;
        await duplicateTemplate(id, newName);
        showToast('Template duplicated', 'success');
        await loadData();
    } catch (error) {
        showToast('Failed to duplicate template: ' + error.message, 'error');
    }
}

async function handleDelete(id) {
    const template = templates.find(t => t.id === id);
    if (!template || template.system) {
        showToast('System templates cannot be deleted', 'error');
        return;
    }

    const confirmed = await confirmDelete('Template', `<p><strong>${template.name}</strong></p>`);
    if (!confirmed) return;

    try {
        await deleteTemplate(id);
        showToast('Template deleted', 'success');
        await loadData();
    } catch (error) {
        showToast('Failed to delete template: ' + error.message, 'error');
    }
}

async function handleReseed() {
    const confirmed = await showConfirm({
        title: 'Reseed System Templates',
        message: 'This will restore all system templates to their default values. Custom templates will not be affected.',
        confirmText: 'Reseed',
        type: 'warning',
    });

    if (!confirmed) return;

    try {
        await reseedTemplates();
        showToast('System templates reseeded', 'success');
        await loadData();
    } catch (error) {
        showToast('Failed to reseed templates: ' + error.message, 'error');
    }
}

async function showTemplateDetails(id) {
    try {
        const template = await getTemplate(id);

        const content = `
            <div class="template-details">
                <div class="template-details-header">
                    ${template.logo
                        ? `<img src="${template.logo}" alt="" class="template-details-logo" onerror="this.style.display='none'">`
                        : `<span class="material-symbols-outlined template-details-icon">${template.type === 'COMPOSE' ? 'widgets' : 'deployed_code'}</span>`
                    }
                    <div>
                        <h3>${template.name}</h3>
                        <span class="template-type-badge ${template.type?.toLowerCase()}">${template.type}</span>
                        ${template.system ? '<span class="system-badge">System</span>' : '<span class="user-badge">Custom</span>'}
                    </div>
                </div>
                <p class="template-details-description">${template.description || 'No description'}</p>

                <div class="info-grid">
                    <span class="info-label">Category:</span>
                    <span class="info-value">${template.category || 'Uncategorized'}</span>

                    ${template.type === 'CONTAINER' ? `
                        <span class="info-label">Image:</span>
                        <span class="info-value mono">${template.imageName || '-'}</span>

                        <span class="info-label">Ports:</span>
                        <span class="info-value mono">${template.defaultPorts || '-'}</span>

                        <span class="info-label">Network:</span>
                        <span class="info-value">${template.defaultNetwork || '-'}</span>

                        <span class="info-label">Run as User:</span>
                        <span class="info-value mono">${template.defaultUser || '-'}</span>
                    ` : ''}

                    ${template.platform ? `
                        <span class="info-label">Platform:</span>
                        <span class="info-value">${template.platform}</span>
                    ` : ''}

                    ${template.documentation ? `
                        <span class="info-label">Documentation:</span>
                        <span class="info-value"><a href="${template.documentation}" target="_blank" rel="noopener">${template.documentation}</a></span>
                    ` : ''}
                </div>

                ${template.defaultEnv ? `
                    <div class="template-details-section">
                        <h4>Environment Variables</h4>
                        <pre class="code-block">${template.defaultEnv}</pre>
                    </div>
                ` : ''}

                ${template.defaultVolumes ? `
                    <div class="template-details-section">
                        <h4>Volumes</h4>
                        <pre class="code-block">${template.defaultVolumes}</pre>
                    </div>
                ` : ''}

                ${template.composeContent ? `
                    <div class="template-details-section">
                        <h4>Compose Configuration</h4>
                        <pre class="code-block">${escapeHtml(template.composeContent)}</pre>
                    </div>
                ` : ''}
            </div>
        `;

        openModal('Template Details', content, `
            <md-text-button onclick="document.getElementById('app-dialog').close()">Close</md-text-button>
            <md-filled-button id="use-template-btn">Use Template</md-filled-button>
        `);

        setTimeout(() => {
            document.getElementById('use-template-btn')?.addEventListener('click', () => {
                closeModal();
                // Navigate to containers page with template pre-selected
                window.location.hash = 'containers';
                // Store template ID for containers page to pick up
                sessionStorage.setItem('selectedTemplateId', id);
            });
        }, 100);

    } catch (error) {
        showToast('Failed to load template details', 'error');
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

export function cleanup() {
    templates = [];
    categories = [];
    selectedCategory = null;
    searchQuery = '';
    currentView = 'list';
    editingTemplate = null;
}
