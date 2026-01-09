// Users & Groups Page (Admin Only)
import { state, isAdmin } from '../state.js';
import {
    listUsers, createUser, updateUser, deleteUser, resetUserPassword,
    getUserPermissions, grantUserPermission, revokeUserPermission,
    listGroups, createGroup, updateGroup, deleteGroup,
    getGroupPermissions, grantGroupPermission, revokeGroupPermission,
    addUserToGroup, removeUserFromGroup,
    RESOURCES, RESOURCE_ACTIONS
} from '../api/users.js';
import { listContainers, listVolumes, listNetworks } from '../api/docker.js';
import { showToast } from '../components/toast.js';
import { openModal, closeModal } from '../components/modal.js';
import { confirmDelete } from '../components/confirm-dialog.js';
import { renderTable, renderActions, setupTableActions } from '../components/data-table.js';
import { renderRoleBadge, renderEnabledBadge } from '../components/status-badge.js';
import { navigateTo } from '../router.js';

// Resources that support scoping to specific items
const SCOPABLE_RESOURCES = ['CONTAINERS', 'VOLUMES', 'NETWORKS'];

let users = [];
let groups = [];

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
                <div class="card-content" id="users-table">
                    <div class="loading-container">
                        <md-circular-progress indeterminate></md-circular-progress>
                    </div>
                </div>
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
                <div class="card-content" id="groups-table">
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
    if (!isAdmin()) return;

    // Setup tabs
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
        permissions: showUserPermissionsModal,
        reset: handleResetPassword,
        delete: handleDeleteUser,
    });

    setupTableActions('groups-table', {
        edit: showEditGroupModal,
        permissions: showGroupPermissionsModal,
        delete: handleDeleteGroup,
    });

    await loadData();
}

// Load data
async function loadData() {
    try {
        [users, groups] = await Promise.all([listUsers(), listGroups()]);
        renderUsersTable();
        renderGroupsTable();
    } catch (error) {
        console.error('Failed to load users:', error);
        showToast('Failed to load users', 'error');
    }
}

// Render users table
function renderUsersTable() {
    const container = document.getElementById('users-table');

    const columns = [
        { key: 'username', label: 'Username' },
        { key: 'email', label: 'Email' },
        { key: 'admin', label: 'Role', render: renderRoleBadge },
        { key: 'enabled', label: 'Status', render: renderEnabledBadge },
        {
            key: 'groups',
            label: 'Groups',
            render: (grps) => grps?.map(g => g.name).join(', ') || 'None'
        },
    ];

    const actions = (user) => renderActions([
        { icon: 'edit', title: 'Edit', action: 'edit' },
        { icon: 'key', title: 'Permissions', action: 'permissions' },
        { icon: 'lock_reset', title: 'Reset Password', action: 'reset' },
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: users,
        actions,
        emptyMessage: 'No users found',
        emptyIcon: 'group',
    });
}

// Render groups table
function renderGroupsTable() {
    const container = document.getElementById('groups-table');

    const columns = [
        { key: 'name', label: 'Name' },
        { key: 'description', label: 'Description' },
        {
            key: 'members',
            label: 'Members',
            render: (members) => members?.length || 0
        },
    ];

    const actions = (group) => renderActions([
        { icon: 'edit', title: 'Edit', action: 'edit' },
        { icon: 'key', title: 'Permissions', action: 'permissions' },
        { icon: 'delete', title: 'Delete', action: 'delete', color: 'var(--status-stopped)' },
    ]);

    container.innerHTML = renderTable({
        columns,
        data: groups,
        actions,
        emptyMessage: 'No groups found',
        emptyIcon: 'groups',
    });
}

// Create user modal
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

                // Show temp password
                openModal('User Created', `
                    <p>Temporary password for <strong>${data.username}</strong>:</p>
                    <div class="terminal" style="margin-top: 16px;">${result.temporaryPassword}</div>
                    <p class="form-hint" style="margin-top: 8px;">The user must change this password on first login.</p>
                `, `<md-filled-button onclick="document.getElementById('app-dialog').close()">OK</md-filled-button>`);

                await loadData();
            } catch (error) {
                showToast('Failed: ' + error.message, 'error');
            }
        });
    }, 100);
}

// Edit user modal
async function showEditUserModal(id) {
    const user = users.find(u => u.id === id);
    if (!user) return;

    const groupCheckboxes = groups.map(g => {
        const checked = user.groups?.some(ug => ug.id === g.id) ? 'checked' : '';
        return `<label style="display: flex; align-items: center; gap: 8px;">
            <md-checkbox data-group-id="${g.id}" ${checked}></md-checkbox>${g.name}
        </label>`;
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
                // Update user
                await updateUser(id, {
                    email: document.getElementById('edit-email')?.value,
                    admin: document.getElementById('edit-admin')?.checked,
                    enabled: document.getElementById('edit-enabled')?.checked,
                });

                // Update group memberships
                const selectedGroups = Array.from(document.querySelectorAll('[data-group-id]'))
                    .filter(cb => cb.checked)
                    .map(cb => cb.dataset.groupId);

                const currentGroups = user.groups?.map(g => g.id) || [];

                // Add to new groups
                for (const gid of selectedGroups) {
                    if (!currentGroups.includes(gid)) {
                        await addUserToGroup(id, gid);
                    }
                }

                // Remove from old groups
                for (const gid of currentGroups) {
                    if (!selectedGroups.includes(gid)) {
                        await removeUserFromGroup(id, gid);
                    }
                }

                closeModal();
                showToast('User updated', 'success');
                await loadData();
            } catch (error) {
                showToast('Failed: ' + error.message, 'error');
            }
        });
    }, 100);
}

// User permissions modal
async function showUserPermissionsModal(id) {
    const user = users.find(u => u.id === id);
    if (!user) return;

    try {
        const permissions = await getUserPermissions(id);

        const resourceOptions = Object.keys(RESOURCES).map(r =>
            `<md-select-option value="${r}">${r}</md-select-option>`
        ).join('');

        const permissionsList = permissions.map(p => `
            <div style="display: flex; justify-content: space-between; align-items: center; padding: 8px; background: var(--md-sys-color-surface-container); border-radius: 8px; margin-bottom: 8px;">
                <span>
                    ${p.resource}.${p.action}${p.scopeResourceId ? ` [${p.scopeResourceId}]` : ''}${p.scopeHostId ? ` @${p.scopeHostId.substring(0, 8)}` : ''}${p.source ? ` <span style="color: var(--md-sys-color-outline);">(${p.source})</span>` : ''}
                </span>
                ${p.source === 'direct' || !p.source ? `<md-icon-button data-revoke="${p.id}"><span class="material-symbols-outlined">delete</span></md-icon-button>` : ''}
            </div>
        `).join('') || '<p style="color: var(--md-sys-color-on-surface-variant);">No permissions</p>';

        const content = `
            <div style="margin-bottom: 16px;">
                <h4>Current Permissions</h4>
                <div id="permissions-list" style="max-height: 200px; overflow-y: auto;">${permissionsList}</div>
            </div>
            <div>
                <h4>Grant Permission</h4>
                <div style="display: flex; flex-direction: column; gap: 8px; margin-top: 8px;">
                    <div style="display: flex; gap: 8px;">
                        <md-filled-select id="perm-resource" label="Resource" style="flex: 1;">
                            ${resourceOptions}
                        </md-filled-select>
                        <md-filled-select id="perm-action" label="Action" style="flex: 1;">
                            <md-select-option value="*">* (All)</md-select-option>
                        </md-filled-select>
                    </div>
                    <div id="resource-scope-section" style="display: none;">
                        <div style="display: flex; gap: 8px; align-items: flex-end;">
                            <md-filled-select id="perm-resource-name" label="Resource Name" style="flex: 1;">
                                <md-select-option value="*" selected>* (All)</md-select-option>
                            </md-filled-select>
                            <md-circular-progress id="resource-loading" indeterminate style="display: none; width: 24px; height: 24px;"></md-circular-progress>
                        </div>
                        <p style="font-size: 12px; color: var(--md-sys-color-outline); margin-top: 4px;">
                            Select a specific resource or * for all
                        </p>
                    </div>
                    <md-filled-button id="grant-perm" style="align-self: flex-end;">Grant</md-filled-button>
                </div>
            </div>
        `;

        openModal('Permissions: ' + user.username, content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `);

        setTimeout(() => {
            // Update actions and show/hide resource scope when resource changes
            document.getElementById('perm-resource')?.addEventListener('change', async (e) => {
                const resource = e.target.value;
                const actions = RESOURCE_ACTIONS[resource] || [];
                const actionSelect = document.getElementById('perm-action');
                actionSelect.innerHTML = `
                    <md-select-option value="*">* (All)</md-select-option>
                    ${actions.map(a => `<md-select-option value="${a}">${a}</md-select-option>`).join('')}
                `;

                // Show/hide resource scope section
                const scopeSection = document.getElementById('resource-scope-section');
                const resourceNameSelect = document.getElementById('perm-resource-name');

                if (SCOPABLE_RESOURCES.includes(resource)) {
                    scopeSection.style.display = 'block';
                    await loadResourceOptions(resource, resourceNameSelect);
                } else {
                    scopeSection.style.display = 'none';
                }
            });

            // Grant permission
            document.getElementById('grant-perm')?.addEventListener('click', async () => {
                const resource = document.getElementById('perm-resource')?.value;
                const action = document.getElementById('perm-action')?.value || '*';
                const resourceName = document.getElementById('perm-resource-name')?.value;

                const permission = { resource, action };

                // Add scopeResourceId if a specific resource is selected
                if (SCOPABLE_RESOURCES.includes(resource) && resourceName && resourceName !== '*') {
                    permission.scopeResourceId = resourceName;
                }

                try {
                    await grantUserPermission(id, permission);
                    showToast('Permission granted', 'success');
                    closeModal();
                    showUserPermissionsModal(id);
                } catch (e) {
                    showToast('Failed: ' + e.message, 'error');
                }
            });

            // Revoke permissions
            document.querySelectorAll('[data-revoke]').forEach(btn => {
                btn.addEventListener('click', async () => {
                    try {
                        await revokeUserPermission(id, btn.dataset.revoke);
                        showToast('Permission revoked', 'success');
                        closeModal();
                        showUserPermissionsModal(id);
                    } catch (e) {
                        showToast('Failed: ' + e.message, 'error');
                    }
                });
            });
        }, 100);
    } catch (error) {
        showToast('Failed to load permissions', 'error');
    }
}

// Load resource options for dropdown
async function loadResourceOptions(resource, selectElement) {
    const hostId = state.currentHostId;
    if (!hostId) {
        selectElement.innerHTML = `
            <md-select-option value="*" selected>* (All)</md-select-option>
            <md-select-option disabled>No host selected</md-select-option>
        `;
        return;
    }

    const loadingEl = document.getElementById('resource-loading');
    if (loadingEl) loadingEl.style.display = 'block';

    try {
        let items = [];

        if (resource === 'CONTAINERS') {
            const containers = await listContainers(hostId, true);
            items = containers.map(c => ({
                value: c.names?.[0]?.replace(/^\//, '') || c.id?.substring(0, 12),
                label: c.names?.[0]?.replace(/^\//, '') || c.id?.substring(0, 12)
            }));
        } else if (resource === 'VOLUMES') {
            const volumes = await listVolumes(hostId);
            items = volumes.map(v => ({
                value: v.name,
                label: v.name
            }));
        } else if (resource === 'NETWORKS') {
            const networks = await listNetworks(hostId);
            items = networks.map(n => ({
                value: n.name,
                label: n.name
            }));
        }

        selectElement.innerHTML = `
            <md-select-option value="*" selected>* (All)</md-select-option>
            ${items.map(item => `<md-select-option value="${item.value}">${item.label}</md-select-option>`).join('')}
        `;
    } catch (error) {
        console.error('Failed to load resources:', error);
        selectElement.innerHTML = `
            <md-select-option value="*" selected>* (All)</md-select-option>
            <md-select-option disabled>Failed to load</md-select-option>
        `;
    } finally {
        if (loadingEl) loadingEl.style.display = 'none';
    }
}

// Reset password
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

// Delete user
async function handleDeleteUser(id) {
    const user = users.find(u => u.id === id);
    const confirmed = await confirmDelete('User', `<p><strong>${user?.username}</strong></p>`);
    if (!confirmed) return;

    try {
        await deleteUser(id);
        showToast('User deleted', 'success');
        await loadData();
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

// Create group modal
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
            } catch (error) {
                showToast('Failed: ' + error.message, 'error');
            }
        });
    }, 100);
}

// Edit group modal
async function showEditGroupModal(id) {
    const group = groups.find(g => g.id === id);
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
                await updateGroup(id, {
                    name: document.getElementById('edit-group-name')?.value,
                    description: document.getElementById('edit-group-desc')?.value,
                });
                closeModal();
                showToast('Group updated', 'success');
                await loadData();
            } catch (error) {
                showToast('Failed: ' + error.message, 'error');
            }
        });
    }, 100);
}

// Group permissions modal
async function showGroupPermissionsModal(id) {
    const group = groups.find(g => g.id === id);
    if (!group) return;

    try {
        const permissions = await getGroupPermissions(id);

        const resourceOptions = Object.keys(RESOURCES).map(r =>
            `<md-select-option value="${r}">${r}</md-select-option>`
        ).join('');

        const permissionsList = permissions.map(p => `
            <div style="display: flex; justify-content: space-between; align-items: center; padding: 8px; background: var(--md-sys-color-surface-container); border-radius: 8px; margin-bottom: 8px;">
                <span>${p.resource}.${p.action}${p.scopeResourceId ? ` [${p.scopeResourceId}]` : ''}${p.scopeHostId ? ` @${p.scopeHostId.substring(0, 8)}` : ''}</span>
                <md-icon-button data-revoke="${p.id}"><span class="material-symbols-outlined">delete</span></md-icon-button>
            </div>
        `).join('') || '<p style="color: var(--md-sys-color-on-surface-variant);">No permissions</p>';

        const content = `
            <div style="margin-bottom: 16px;">
                <h4>Group Permissions</h4>
                <div style="max-height: 200px; overflow-y: auto;">${permissionsList}</div>
            </div>
            <div>
                <h4>Grant Permission</h4>
                <div style="display: flex; flex-direction: column; gap: 8px; margin-top: 8px;">
                    <div style="display: flex; gap: 8px;">
                        <md-filled-select id="gperm-resource" label="Resource" style="flex: 1;">
                            ${resourceOptions}
                        </md-filled-select>
                        <md-filled-select id="gperm-action" label="Action" style="flex: 1;">
                            <md-select-option value="*">* (All)</md-select-option>
                        </md-filled-select>
                    </div>
                    <div id="gresource-scope-section" style="display: none;">
                        <div style="display: flex; gap: 8px; align-items: flex-end;">
                            <md-filled-select id="gperm-resource-name" label="Resource Name" style="flex: 1;">
                                <md-select-option value="*" selected>* (All)</md-select-option>
                            </md-filled-select>
                            <md-circular-progress id="gresource-loading" indeterminate style="display: none; width: 24px; height: 24px;"></md-circular-progress>
                        </div>
                        <p style="font-size: 12px; color: var(--md-sys-color-outline); margin-top: 4px;">
                            Select a specific resource or * for all
                        </p>
                    </div>
                    <md-filled-button id="grant-gperm" style="align-self: flex-end;">Grant</md-filled-button>
                </div>
            </div>
        `;

        openModal('Permissions: ' + group.name, content, `
            <md-filled-button onclick="document.getElementById('app-dialog').close()">Close</md-filled-button>
        `);

        setTimeout(() => {
            document.getElementById('gperm-resource')?.addEventListener('change', async (e) => {
                const resource = e.target.value;
                const actions = RESOURCE_ACTIONS[resource] || [];
                const actionSelect = document.getElementById('gperm-action');
                actionSelect.innerHTML = `
                    <md-select-option value="*">* (All)</md-select-option>
                    ${actions.map(a => `<md-select-option value="${a}">${a}</md-select-option>`).join('')}
                `;

                // Show/hide resource scope section
                const scopeSection = document.getElementById('gresource-scope-section');
                const resourceNameSelect = document.getElementById('gperm-resource-name');

                if (SCOPABLE_RESOURCES.includes(resource)) {
                    scopeSection.style.display = 'block';
                    await loadResourceOptionsForGroup(resource, resourceNameSelect);
                } else {
                    scopeSection.style.display = 'none';
                }
            });

            document.getElementById('grant-gperm')?.addEventListener('click', async () => {
                const resource = document.getElementById('gperm-resource')?.value;
                const action = document.getElementById('gperm-action')?.value || '*';
                const resourceName = document.getElementById('gperm-resource-name')?.value;

                const permission = { resource, action };

                // Add scopeResourceId if a specific resource is selected
                if (SCOPABLE_RESOURCES.includes(resource) && resourceName && resourceName !== '*') {
                    permission.scopeResourceId = resourceName;
                }

                try {
                    await grantGroupPermission(id, permission);
                    showToast('Permission granted', 'success');
                    closeModal();
                    showGroupPermissionsModal(id);
                } catch (e) {
                    showToast('Failed: ' + e.message, 'error');
                }
            });

            document.querySelectorAll('[data-revoke]').forEach(btn => {
                btn.addEventListener('click', async () => {
                    try {
                        await revokeGroupPermission(id, btn.dataset.revoke);
                        showToast('Permission revoked', 'success');
                        closeModal();
                        showGroupPermissionsModal(id);
                    } catch (e) {
                        showToast('Failed: ' + e.message, 'error');
                    }
                });
            });
        }, 100);
    } catch (error) {
        showToast('Failed to load permissions', 'error');
    }
}

// Load resource options for group dropdown (uses different IDs)
async function loadResourceOptionsForGroup(resource, selectElement) {
    const hostId = state.currentHostId;
    if (!hostId) {
        selectElement.innerHTML = `
            <md-select-option value="*" selected>* (All)</md-select-option>
            <md-select-option disabled>No host selected</md-select-option>
        `;
        return;
    }

    const loadingEl = document.getElementById('gresource-loading');
    if (loadingEl) loadingEl.style.display = 'block';

    try {
        let items = [];

        if (resource === 'CONTAINERS') {
            const containers = await listContainers(hostId, true);
            items = containers.map(c => ({
                value: c.names?.[0]?.replace(/^\//, '') || c.id?.substring(0, 12),
                label: c.names?.[0]?.replace(/^\//, '') || c.id?.substring(0, 12)
            }));
        } else if (resource === 'VOLUMES') {
            const volumes = await listVolumes(hostId);
            items = volumes.map(v => ({
                value: v.name,
                label: v.name
            }));
        } else if (resource === 'NETWORKS') {
            const networks = await listNetworks(hostId);
            items = networks.map(n => ({
                value: n.name,
                label: n.name
            }));
        }

        selectElement.innerHTML = `
            <md-select-option value="*" selected>* (All)</md-select-option>
            ${items.map(item => `<md-select-option value="${item.value}">${item.label}</md-select-option>`).join('')}
        `;
    } catch (error) {
        console.error('Failed to load resources:', error);
        selectElement.innerHTML = `
            <md-select-option value="*" selected>* (All)</md-select-option>
            <md-select-option disabled>Failed to load</md-select-option>
        `;
    } finally {
        if (loadingEl) loadingEl.style.display = 'none';
    }
}

// Delete group
async function handleDeleteGroup(id) {
    const group = groups.find(g => g.id === id);
    const confirmed = await confirmDelete('Group', `<p><strong>${group?.name}</strong></p>`);
    if (!confirmed) return;

    try {
        await deleteGroup(id);
        showToast('Group deleted', 'success');
        await loadData();
    } catch (error) {
        showToast('Failed: ' + error.message, 'error');
    }
}

export function cleanup() {
    users = [];
    groups = [];
}
