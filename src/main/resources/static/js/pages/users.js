// Users & Groups Page (Admin Only)
import { state, isAdmin } from '../state.js';
import {
    listUsers, createUser, updateUser, deleteUser, resetUserPassword,
    getUserPermissions, grantUserPermission, revokeUserPermission,
    listGroups, createGroup, updateGroup, deleteGroup,
    getGroupPermissions, grantGroupPermission, revokeGroupPermission,
    addUserToGroup, removeUserFromGroup,
    RESOURCES, RESOURCE_ACTIONS, RESOURCE_CATEGORIES, RESOURCE_LABELS
} from '../api/users.js';
import { listContainers, listVolumes, listNetworks } from '../api/docker.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions } from '../components/data-table.js';
import { renderRoleBadge, renderEnabledBadge } from '../components/status-badge.js';

const SCOPABLE_RESOURCES = ['CONTAINERS', 'VOLUMES', 'NETWORKS'];

let users = [];
let groups = [];
let currentView = 'list';
let currentUserId = null;
let currentGroupId = null;

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

    return `<div id="users-page"></div>`;
}

export async function init() {
    if (!isAdmin()) return;
    await loadData();
    renderListView();
}

async function loadData() {
    try {
        [users, groups] = await Promise.all([listUsers(), listGroups()]);
    } catch (error) {
        console.error('Failed to load data:', error);
        showToast('Failed to load data', 'error');
    }
}

function renderListView() {
    currentView = 'list';
    currentUserId = null;
    currentGroupId = null;

    const page = document.getElementById('users-page');
    page.innerHTML = `
        <md-tabs id="users-tabs">
            <md-primary-tab id="tab-users" aria-controls="panel-users">Users</md-primary-tab>
            <md-primary-tab id="tab-groups" aria-controls="panel-groups">Groups</md-primary-tab>
        </md-tabs>

        <div id="panel-users" class="tab-panel">
            <div class="section-header" style="margin-top: 16px;">
                <h2 class="section-title">Users</h2>
                <md-filled-button id="create-user-btn">
                    <span class="material-symbols-outlined" slot="icon">person_add</span>
                    Create User
                </md-filled-button>
            </div>
            <div class="card">
                <div class="card-content" id="users-table"></div>
            </div>
        </div>

        <div id="panel-groups" class="tab-panel hidden">
            <div class="section-header" style="margin-top: 16px;">
                <h2 class="section-title">Groups</h2>
                <md-filled-button id="create-group-btn">
                    <span class="material-symbols-outlined" slot="icon">group_add</span>
                    Create Group
                </md-filled-button>
            </div>
            <div class="card">
                <div class="card-content" id="groups-table"></div>
            </div>
        </div>
    `;

    renderUsersTable();
    renderGroupsTable();
    setupListViewListeners();
}

function setupListViewListeners() {
    const tabs = document.getElementById('users-tabs');
    tabs?.addEventListener('change', () => {
        const activeTab = tabs.activeTabIndex;
        document.getElementById('panel-users').classList.toggle('hidden', activeTab !== 0);
        document.getElementById('panel-groups').classList.toggle('hidden', activeTab !== 1);
    });

    document.getElementById('create-user-btn')?.addEventListener('click', showCreateUserModal);
    document.getElementById('create-group-btn')?.addEventListener('click', showCreateGroupModal);

    setupTableActions('users-table', {
        edit: showEditUserModal,
        permissions: (id) => renderUserPermissionsView(id),
        reset: handleResetPassword,
        delete: handleDeleteUser,
    });

    setupTableActions('groups-table', {
        edit: (id) => renderGroupDetailView(id),
        permissions: (id) => renderGroupDetailView(id),
        delete: handleDeleteGroup,
    });
}

function renderUsersTable() {
    const container = document.getElementById('users-table');
    const columns = [
        { key: 'username', label: 'Username' },
        { key: 'email', label: 'Email' },
        { key: 'admin', label: 'Role', render: renderRoleBadge },
        { key: 'enabled', label: 'Status', render: renderEnabledBadge },
        { key: 'groups', label: 'Groups', render: (grps) => grps?.map(g => g.name).join(', ') || 'None' },
    ];

    const actions = (user) => renderActions([
        { icon: 'edit', title: 'Edit', action: 'edit' },
        { icon: 'key', title: 'Permissions', action: 'permissions' },
        { icon: 'lock_reset', title: 'Reset Password', action: 'reset' },
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({ columns, data: users, actions, emptyMessage: 'No users found', emptyIcon: 'group' });
}

function renderGroupsTable() {
    const container = document.getElementById('groups-table');
    const columns = [
        { key: 'name', label: 'Name' },
        { key: 'description', label: 'Description' },
        { key: 'members', label: 'Members', render: (members) => members?.length || 0 },
    ];

    const actions = (group) => renderActions([
        { icon: 'edit', title: 'Edit', action: 'edit' },
        { icon: 'key', title: 'Permissions', action: 'permissions' },
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({ columns, data: groups, actions, emptyMessage: 'No groups found', emptyIcon: 'groups' });
}

// ==================== User Permissions Full Page View ====================

async function renderUserPermissionsView(userId) {
    currentView = 'user-permissions';
    currentUserId = userId;

    const user = users.find(u => u.id === userId);
    if (!user) return renderListView();

    const page = document.getElementById('users-page');
    page.innerHTML = `
        <div class="full-page-view">
            <div class="view-header">
                <div class="view-header-left">
                    <md-icon-button id="back-to-list-btn">
                        <span class="material-symbols-outlined">arrow_back</span>
                    </md-icon-button>
                    <div class="view-title-group">
                        <h2>Permissions: ${user.username}</h2>
                        <span class="view-subtitle">${user.email} ${user.admin ? '(Admin)' : ''}</span>
                    </div>
                </div>
            </div>
            <div class="permissions-layout">
                <div class="permissions-current">
                    <div class="section-header">
                        <h3 class="section-title">Current Permissions</h3>
                    </div>
                    <div id="current-permissions-list" class="permissions-list">
                        <div class="loading-container"><md-circular-progress indeterminate></md-circular-progress></div>
                    </div>
                </div>
                <div class="permissions-grant">
                    <div class="section-header">
                        <h3 class="section-title">Grant Permission</h3>
                    </div>
                    <div class="grant-form">
                        ${renderPermissionGrantForm('user')}
                    </div>
                </div>
            </div>
        </div>
    `;

    document.getElementById('back-to-list-btn')?.addEventListener('click', renderListView);
    await loadAndRenderUserPermissions(userId);
    setupPermissionGrantForm('user', userId);
}

async function loadAndRenderUserPermissions(userId) {
    try {
        const permissions = await getUserPermissions(userId);
        renderPermissionsList('current-permissions-list', permissions, 'user', userId);
    } catch (error) {
        showToast('Failed to load permissions', 'error');
    }
}

// ==================== Group Detail Full Page View ====================

async function renderGroupDetailView(groupId) {
    currentView = 'group-detail';
    currentGroupId = groupId;

    const group = groups.find(g => g.id === groupId);
    if (!group) return renderListView();

    const page = document.getElementById('users-page');
    page.innerHTML = `
        <div class="full-page-view">
            <div class="view-header">
                <div class="view-header-left">
                    <md-icon-button id="back-to-list-btn">
                        <span class="material-symbols-outlined">arrow_back</span>
                    </md-icon-button>
                    <div class="view-title-group">
                        <h2>${group.name}</h2>
                        <span class="view-subtitle">${group.description || 'No description'}</span>
                    </div>
                </div>
                <div class="view-header-right">
                    <md-outlined-button id="edit-group-info-btn">
                        <span class="material-symbols-outlined" slot="icon">edit</span>
                        Edit Info
                    </md-outlined-button>
                    <md-outlined-button id="delete-group-btn" style="--md-outlined-button-outline-color: var(--md-sys-color-error);">
                        <span class="material-symbols-outlined" slot="icon">delete</span>
                        Delete
                    </md-outlined-button>
                </div>
            </div>

            <div class="group-detail-layout">
                <div class="group-members-section">
                    <div class="section-header">
                        <h3 class="section-title">Members (${group.members?.length || 0})</h3>
                    </div>
                    <div class="members-list">
                        ${renderGroupMembers(group)}
                    </div>
                </div>

                <div class="permissions-layout">
                    <div class="permissions-current">
                        <div class="section-header">
                            <h3 class="section-title">Group Permissions</h3>
                        </div>
                        <div id="current-permissions-list" class="permissions-list">
                            <div class="loading-container"><md-circular-progress indeterminate></md-circular-progress></div>
                        </div>
                    </div>
                    <div class="permissions-grant">
                        <div class="section-header">
                            <h3 class="section-title">Grant Permission</h3>
                        </div>
                        <div class="grant-form">
                            ${renderPermissionGrantForm('group')}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    `;

    document.getElementById('back-to-list-btn')?.addEventListener('click', renderListView);
    document.getElementById('edit-group-info-btn')?.addEventListener('click', () => showEditGroupInfoModal(groupId));
    document.getElementById('delete-group-btn')?.addEventListener('click', () => handleDeleteGroupFromDetail(groupId));

    await loadAndRenderGroupPermissions(groupId);
    setupPermissionGrantForm('group', groupId);
}

function renderGroupMembers(group) {
    if (!group.members?.length) {
        return `<div class="empty-state small"><span class="material-symbols-outlined">group_off</span><p>No members</p></div>`;
    }

    return group.members.map(member => `
        <div class="member-item">
            <div class="member-info">
                <span class="material-symbols-outlined">person</span>
                <span class="member-name">${member.username}</span>
                ${member.admin ? '<span class="chip admin">Admin</span>' : ''}
            </div>
        </div>
    `).join('');
}

async function loadAndRenderGroupPermissions(groupId) {
    try {
        const permissions = await getGroupPermissions(groupId);
        renderPermissionsList('current-permissions-list', permissions, 'group', groupId);
    } catch (error) {
        showToast('Failed to load permissions', 'error');
    }
}

// ==================== Shared Permission Components ====================

function renderPermissionGrantForm(type) {
    return `
        <div class="grant-form-content">
            <div class="form-section">
                <label class="form-label">Resource Category</label>
                <div class="category-selector" id="${type}-category-selector">
                    ${Object.keys(RESOURCE_CATEGORIES).map((cat, i) => `
                        <button class="category-btn ${i === 0 ? 'selected' : ''}" data-category="${cat}">${cat}</button>
                    `).join('')}
                </div>
            </div>

            <div class="form-section">
                <label class="form-label">Resource</label>
                <div class="resource-grid" id="${type}-resource-grid">
                    ${renderResourceButtons(Object.keys(RESOURCE_CATEGORIES)[0], type)}
                </div>
            </div>

            <div class="form-section">
                <label class="form-label">Actions</label>
                <div class="actions-selector" id="${type}-actions-selector">
                    <p class="hint-text">Select a resource first</p>
                </div>
            </div>

            <div class="form-section" id="${type}-scope-section" style="display: none;">
                <label class="form-label">Scope (Optional)</label>
                <md-filled-select id="${type}-scope-select" label="Specific resource" style="width: 100%;">
                    <md-select-option value="" selected>All (no restriction)</md-select-option>
                </md-filled-select>
            </div>

            <div class="form-actions">
                <md-filled-button id="${type}-grant-btn" disabled>
                    <span class="material-symbols-outlined" slot="icon">add</span>
                    Grant Permission
                </md-filled-button>
            </div>
        </div>
    `;
}

function renderResourceButtons(category, type) {
    const resources = RESOURCE_CATEGORIES[category] || [];
    return resources.map(r => `
        <button class="resource-btn" data-resource="${r}">
            <span class="resource-name">${RESOURCE_LABELS[r] || r}</span>
        </button>
    `).join('');
}

function renderPermissionsList(containerId, permissions, type, entityId) {
    const container = document.getElementById(containerId);

    if (!permissions?.length) {
        container.innerHTML = `<div class="empty-state small"><span class="material-symbols-outlined">key_off</span><p>No permissions assigned</p></div>`;
        return;
    }

    // Group by resource
    const grouped = {};
    permissions.forEach(p => {
        const key = p.resource;
        if (!grouped[key]) grouped[key] = [];
        grouped[key].push(p);
    });

    container.innerHTML = Object.entries(grouped).map(([resource, perms]) => `
        <div class="permission-group">
            <div class="permission-group-header">
                <span class="resource-label">${RESOURCE_LABELS[resource] || resource}</span>
                <span class="permission-count">${perms.length} permission${perms.length > 1 ? 's' : ''}</span>
            </div>
            <div class="permission-items">
                ${perms.map(p => `
                    <div class="permission-item">
                        <div class="permission-info">
                            <span class="action-name">${p.action === '*' ? 'All actions' : p.action}</span>
                            ${p.scopeResourceId ? `<span class="scope-badge">${p.scopeResourceId}</span>` : ''}
                            ${p.source && p.source !== 'direct' ? `<span class="source-badge">${p.source}</span>` : ''}
                        </div>
                        ${(!p.source || p.source === 'direct') ? `
                            <md-icon-button class="revoke-btn" data-permission-id="${p.id}" data-type="${type}" data-entity-id="${entityId}">
                                <span class="material-symbols-outlined">close</span>
                            </md-icon-button>
                        ` : ''}
                    </div>
                `).join('')}
            </div>
        </div>
    `).join('');

    // Setup revoke handlers
    container.querySelectorAll('.revoke-btn').forEach(btn => {
        btn.addEventListener('click', async () => {
            const permId = btn.dataset.permissionId;
            const pType = btn.dataset.type;
            const entId = btn.dataset.entityId;

            try {
                if (pType === 'user') {
                    await revokeUserPermission(entId, permId);
                    await loadAndRenderUserPermissions(entId);
                } else {
                    await revokeGroupPermission(entId, permId);
                    await loadAndRenderGroupPermissions(entId);
                }
                showToast('Permission revoked', 'success');
            } catch (e) {
                showToast('Failed: ' + e.message, 'error');
            }
        });
    });
}

function setupPermissionGrantForm(type, entityId) {
    let selectedResource = null;
    let selectedActions = new Set();

    // Category selection
    document.querySelectorAll(`#${type}-category-selector .category-btn`).forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll(`#${type}-category-selector .category-btn`).forEach(b => b.classList.remove('selected'));
            btn.classList.add('selected');
            const category = btn.dataset.category;
            document.getElementById(`${type}-resource-grid`).innerHTML = renderResourceButtons(category, type);
            setupResourceButtons();
            selectedResource = null;
            selectedActions.clear();
            updateActionsSelector();
            updateGrantButton();
        });
    });

    function setupResourceButtons() {
        document.querySelectorAll(`#${type}-resource-grid .resource-btn`).forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll(`#${type}-resource-grid .resource-btn`).forEach(b => b.classList.remove('selected'));
                btn.classList.add('selected');
                selectedResource = btn.dataset.resource;
                selectedActions.clear();
                updateActionsSelector();
                updateScopeSection();
                updateGrantButton();
            });
        });
    }

    function updateActionsSelector() {
        const container = document.getElementById(`${type}-actions-selector`);
        if (!selectedResource) {
            container.innerHTML = '<p class="hint-text">Select a resource first</p>';
            return;
        }

        const actions = RESOURCE_ACTIONS[selectedResource] || [];
        const hint = getPermissionHint(selectedResource, selectedActions);

        container.innerHTML = `
            <div class="action-chips">
                <button class="action-chip ${selectedActions.has('*') ? 'selected' : ''}" data-action="*">
                    All Actions
                </button>
                ${actions.map(a => `
                    <button class="action-chip ${selectedActions.has(a) ? 'selected' : ''}" data-action="${a}">${a}</button>
                `).join('')}
            </div>
            ${hint ? `<div class="permission-hint" id="${type}-permission-hint">${hint}</div>` : ''}
        `;

        container.querySelectorAll('.action-chip').forEach(chip => {
            chip.addEventListener('click', () => {
                const action = chip.dataset.action;
                if (action === '*') {
                    selectedActions.clear();
                    selectedActions.add('*');
                } else {
                    selectedActions.delete('*');
                    if (selectedActions.has(action)) {
                        selectedActions.delete(action);
                    } else {
                        selectedActions.add(action);
                    }
                }
                updateActionsSelector();
                updateGrantButton();
            });
        });

        // Setup quick-add buttons in hints
        container.querySelectorAll('.quick-add-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const actionsToAdd = btn.dataset.actions.split(',');
                actionsToAdd.forEach(a => selectedActions.add(a));
                selectedActions.delete('*');
                updateActionsSelector();
                updateGrantButton();
            });
        });
    }

    function getPermissionHint(resource, actions) {
        const scopeSelect = document.getElementById(`${type}-scope-select`);
        const isScoped = scopeSelect?.value && scopeSelect.value !== '';

        if (resource === 'CONTAINERS') {
            if (actions.has('list') && actions.size === 1) {
                return `
                    <div class="hint-card">
                        <span class="material-symbols-outlined">lightbulb</span>
                        <div class="hint-content">
                            <p><strong>Tip:</strong> "list" only shows the container in the list. To view details or control it, also add:</p>
                            <div class="hint-actions">
                                <button class="quick-add-btn" data-actions="inspect,logs">+ View (inspect, logs)</button>
                                <button class="quick-add-btn" data-actions="start,stop,restart">+ Control (start, stop, restart)</button>
                                <button class="quick-add-btn" data-actions="inspect,logs,start,stop,restart,exec">+ Full Access</button>
                            </div>
                        </div>
                    </div>
                `;
            }
            if (actions.has('list') && !actions.has('inspect') && !actions.has('*')) {
                return `
                    <div class="hint-card info">
                        <span class="material-symbols-outlined">info</span>
                        <p>Add <button class="quick-add-btn text" data-actions="inspect">inspect</button> to view container details</p>
                    </div>
                `;
            }
        }

        if (resource === 'PIPELINES') {
            if (actions.has('list') && actions.size === 1) {
                return `
                    <div class="hint-card">
                        <span class="material-symbols-outlined">lightbulb</span>
                        <div class="hint-content">
                            <p><strong>Tip:</strong> To work with pipelines, also consider:</p>
                            <div class="hint-actions">
                                <button class="quick-add-btn" data-actions="read,trigger">+ Run pipelines</button>
                                <button class="quick-add-btn" data-actions="read,create,update,delete,trigger">+ Manage pipelines</button>
                            </div>
                        </div>
                    </div>
                `;
            }
        }

        if (resource === 'INGRESS_ROUTES') {
            if (actions.has('list') && actions.size === 1) {
                return `
                    <div class="hint-card">
                        <span class="material-symbols-outlined">lightbulb</span>
                        <div class="hint-content">
                            <p><strong>Tip:</strong> To manage ingress routes, also add:</p>
                            <div class="hint-actions">
                                <button class="quick-add-btn" data-actions="read,create,update,delete">+ Manage routes</button>
                            </div>
                        </div>
                    </div>
                `;
            }
        }

        if (resource === 'GIT_REPOS') {
            if (actions.has('list') && actions.size === 1) {
                return `
                    <div class="hint-card">
                        <span class="material-symbols-outlined">lightbulb</span>
                        <div class="hint-content">
                            <p><strong>Tip:</strong> To work with repositories:</p>
                            <div class="hint-actions">
                                <button class="quick-add-btn" data-actions="read,deploy">+ Deploy only</button>
                                <button class="quick-add-btn" data-actions="read,create,update,delete,deploy,poll">+ Full management</button>
                            </div>
                        </div>
                    </div>
                `;
            }
        }

        if (resource === 'USERS' || resource === 'USER_GROUPS') {
            if (actions.has('list') && actions.size === 1) {
                return `
                    <div class="hint-card">
                        <span class="material-symbols-outlined">lightbulb</span>
                        <div class="hint-content">
                            <p><strong>Tip:</strong> To manage ${resource === 'USERS' ? 'users' : 'groups'}:</p>
                            <div class="hint-actions">
                                <button class="quick-add-btn" data-actions="read">+ View details</button>
                                <button class="quick-add-btn" data-actions="read,create,update,delete">+ Full management</button>
                            </div>
                        </div>
                    </div>
                `;
            }
        }

        return null;
    }

    async function updateScopeSection() {
        const section = document.getElementById(`${type}-scope-section`);
        const select = document.getElementById(`${type}-scope-select`);

        if (SCOPABLE_RESOURCES.includes(selectedResource)) {
            section.style.display = 'block';
            await loadScopeOptions(select, selectedResource);
        } else {
            section.style.display = 'none';
        }
    }

    function updateGrantButton() {
        const btn = document.getElementById(`${type}-grant-btn`);
        btn.disabled = !selectedResource || selectedActions.size === 0;
    }

    // Grant button
    document.getElementById(`${type}-grant-btn`)?.addEventListener('click', async () => {
        if (!selectedResource || selectedActions.size === 0) return;

        const scopeSelect = document.getElementById(`${type}-scope-select`);
        const scopeResourceId = scopeSelect?.value || null;

        try {
            // Grant each selected action
            for (const action of selectedActions) {
                const permission = { resource: selectedResource, action };
                if (scopeResourceId) permission.scopeResourceId = scopeResourceId;

                if (type === 'user') {
                    await grantUserPermission(entityId, permission);
                } else {
                    await grantGroupPermission(entityId, permission);
                }
            }

            showToast('Permission(s) granted', 'success');

            // Reload permissions
            if (type === 'user') {
                await loadAndRenderUserPermissions(entityId);
            } else {
                await loadAndRenderGroupPermissions(entityId);
            }

            // Reset form
            selectedActions.clear();
            updateActionsSelector();
            updateGrantButton();
        } catch (e) {
            showToast('Failed: ' + e.message, 'error');
        }
    });

    setupResourceButtons();
}

async function loadScopeOptions(selectElement, resource) {
    const hostId = state.currentHostId;
    if (!hostId) {
        selectElement.innerHTML = `<md-select-option value="" selected>All (no restriction)</md-select-option>`;
        return;
    }

    try {
        let items = [];
        if (resource === 'CONTAINERS') {
            const containers = await listContainers(hostId, true);
            items = containers.map(c => ({ value: c.names?.[0]?.replace(/^\//, '') || c.id?.substring(0, 12), label: c.names?.[0]?.replace(/^\//, '') || c.id?.substring(0, 12) }));
        } else if (resource === 'VOLUMES') {
            const volumes = await listVolumes(hostId);
            items = volumes.map(v => ({ value: v.name, label: v.name }));
        } else if (resource === 'NETWORKS') {
            const networks = await listNetworks(hostId);
            items = networks.map(n => ({ value: n.name, label: n.name }));
        }

        selectElement.innerHTML = `
            <md-select-option value="" selected>All (no restriction)</md-select-option>
            ${items.map(item => `<md-select-option value="${item.value}">${item.label}</md-select-option>`).join('')}
        `;
    } catch (error) {
        selectElement.innerHTML = `<md-select-option value="" selected>All (no restriction)</md-select-option>`;
    }
}

// ==================== Modals (Create/Edit) ====================

function showCreateUserModal() {
    const content = `
        <form class="dialog-form">
            <md-filled-text-field id="new-username" label="Username" required></md-filled-text-field>
            <md-filled-text-field id="new-email" label="Email" type="email" required></md-filled-text-field>
            <label style="display: flex; align-items: center; gap: 8px; margin-top: 8px;">
                <md-checkbox id="new-admin"></md-checkbox>
                Admin privileges
            </label>
        </form>
    `;

    openModal('Create User', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="create-user-submit">Create</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('create-user-submit')?.addEventListener('click', async () => {
            const data = {
                username: document.getElementById('new-username')?.value?.trim(),
                email: document.getElementById('new-email')?.value?.trim(),
                admin: document.getElementById('new-admin')?.checked || false,
            };

            if (!data.username || !data.email) {
                showToast('Please fill all fields', 'error');
                return;
            }

            try {
                const result = await createUser(data);
                closeModal();
                showToast('User created', 'success');

                openModal('User Created', `
                    <p>Temporary password for <strong>${data.username}</strong>:</p>
                    <div class="terminal" style="margin-top: 16px;">${result.temporaryPassword}</div>
                    <p class="form-hint" style="margin-top: 8px;">The user must change this password on first login.</p>
                `, `<md-filled-button onclick="document.getElementById('app-dialog').close()">OK</md-filled-button>`);

                await loadData();
                renderListView();
            } catch (error) {
                showToast('Failed: ' + error.message, 'error');
            }
        });
    }, 100);
}

async function showEditUserModal(id) {
    const user = users.find(u => u.id === id);
    if (!user) return;

    const groupCheckboxes = groups.map(g => {
        const checked = user.groups?.some(ug => ug.id === g.id) ? 'checked' : '';
        return `<label style="display: flex; align-items: center; gap: 8px;"><md-checkbox data-group-id="${g.id}" ${checked}></md-checkbox>${g.name}</label>`;
    }).join('');

    const content = `
        <form class="dialog-form">
            <md-filled-text-field id="edit-email" label="Email" value="${user.email}"></md-filled-text-field>
            <label style="display: flex; align-items: center; gap: 8px; margin: 8px 0;">
                <md-checkbox id="edit-admin" ${user.admin ? 'checked' : ''}></md-checkbox>
                Admin privileges
            </label>
            <label style="display: flex; align-items: center; gap: 8px;">
                <md-checkbox id="edit-enabled" ${user.enabled ? 'checked' : ''}></md-checkbox>
                Enabled
            </label>
            ${groups.length > 0 ? `
                <div style="margin-top: 16px;">
                    <label class="form-label">Groups</label>
                    <div style="display: flex; flex-direction: column; gap: 8px;">${groupCheckboxes}</div>
                </div>
            ` : ''}
        </form>
    `;

    openModal('Edit User: ' + user.username, content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="edit-user-submit">Save</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('edit-user-submit')?.addEventListener('click', async () => {
            try {
                await updateUser(id, {
                    email: document.getElementById('edit-email')?.value,
                    admin: document.getElementById('edit-admin')?.checked,
                    enabled: document.getElementById('edit-enabled')?.checked,
                });

                const selectedGroups = Array.from(document.querySelectorAll('[data-group-id]')).filter(cb => cb.checked).map(cb => cb.dataset.groupId);
                const currentGroups = user.groups?.map(g => g.id) || [];

                for (const gid of selectedGroups) {
                    if (!currentGroups.includes(gid)) await addUserToGroup(id, gid);
                }
                for (const gid of currentGroups) {
                    if (!selectedGroups.includes(gid)) await removeUserFromGroup(id, gid);
                }

                closeModal();
                showToast('User updated', 'success');
                await loadData();
                renderListView();
            } catch (error) {
                showToast('Failed: ' + error.message, 'error');
            }
        });
    }, 100);
}

function showCreateGroupModal() {
    const content = `
        <form class="dialog-form">
            <md-filled-text-field id="new-group-name" label="Name" required></md-filled-text-field>
            <md-filled-text-field id="new-group-desc" label="Description"></md-filled-text-field>
        </form>
    `;

    openModal('Create Group', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="create-group-submit">Create</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('create-group-submit')?.addEventListener('click', async () => {
            const data = {
                name: document.getElementById('new-group-name')?.value?.trim(),
                description: document.getElementById('new-group-desc')?.value?.trim(),
            };

            if (!data.name) {
                showToast('Please enter a name', 'error');
                return;
            }

            try {
                await createGroup(data);
                closeModal();
                showToast('Group created', 'success');
                await loadData();
                renderListView();
            } catch (error) {
                showToast('Failed: ' + error.message, 'error');
            }
        });
    }, 100);
}

async function showEditGroupInfoModal(groupId) {
    const group = groups.find(g => g.id === groupId);
    if (!group) return;

    const content = `
        <form class="dialog-form">
            <md-filled-text-field id="edit-group-name" label="Name" value="${group.name}"></md-filled-text-field>
            <md-filled-text-field id="edit-group-desc" label="Description" value="${group.description || ''}"></md-filled-text-field>
        </form>
    `;

    openModal('Edit Group', content, `
        <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
        <md-filled-button id="edit-group-submit">Save</md-filled-button>
    `);

    setTimeout(() => {
        document.getElementById('edit-group-submit')?.addEventListener('click', async () => {
            try {
                await updateGroup(groupId, {
                    name: document.getElementById('edit-group-name')?.value,
                    description: document.getElementById('edit-group-desc')?.value,
                });
                closeModal();
                showToast('Group updated', 'success');
                await loadData();
                renderGroupDetailView(groupId);
            } catch (error) {
                showToast('Failed: ' + error.message, 'error');
            }
        });
    }, 100);
}

// ==================== Actions ====================

async function handleResetPassword(id) {
    const user = users.find(u => u.id === id);
    if (!user) return;

    try {
        const result = await resetUserPassword(id);
        openModal('Password Reset', `
            <p>New temporary password for <strong>${user.username}</strong>:</p>
            <div class="terminal" style="margin-top: 16px;">${result.temporaryPassword}</div>
        `, `<md-filled-button onclick="document.getElementById('app-dialog').close()">OK</md-filled-button>`);
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

async function handleDeleteUser(id) {
    const user = users.find(u => u.id === id);
    const confirmed = await confirmDelete('User', `<p><strong>${user?.username}</strong></p>`);
    if (!confirmed) return;

    try {
        await deleteUser(id);
        showToast('User deleted', 'success');
        await loadData();
        renderListView();
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

async function handleDeleteGroup(id) {
    const group = groups.find(g => g.id === id);
    const confirmed = await confirmDelete('Group', `<p><strong>${group?.name}</strong></p>`);
    if (!confirmed) return;

    try {
        await deleteGroup(id);
        showToast('Group deleted', 'success');
        await loadData();
        renderListView();
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

async function handleDeleteGroupFromDetail(groupId) {
    const group = groups.find(g => g.id === groupId);
    const confirmed = await confirmDelete('Group', `<p><strong>${group?.name}</strong></p>`);
    if (!confirmed) return;

    try {
        await deleteGroup(groupId);
        showToast('Group deleted', 'success');
        await loadData();
        renderListView();
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

export function cleanup() {
    users = [];
    groups = [];
    currentView = 'list';
    currentUserId = null;
    currentGroupId = null;
}
