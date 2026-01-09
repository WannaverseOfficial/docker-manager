// Users & Groups API Module
import { usersApi, groupsApi } from './client.js';

// ==================== Users ====================

export async function listUsers() {
    return usersApi('');
}

export async function getUser(id) {
    return usersApi(`/${id}`);
}

export async function createUser(data) {
    return usersApi('', {
        method: 'POST',
        body: JSON.stringify(data),
    });
}

export async function updateUser(id, data) {
    return usersApi(`/${id}`, {
        method: 'PUT',
        body: JSON.stringify(data),
    });
}

export async function deleteUser(id) {
    return usersApi(`/${id}`, {
        method: 'DELETE',
    });
}

export async function resetUserPassword(id) {
    return usersApi(`/${id}/reset-password`, {
        method: 'POST',
    });
}

// ==================== User Permissions ====================

export async function getUserPermissions(userId) {
    return usersApi(`/${userId}/permissions`);
}

export async function grantUserPermission(userId, permission) {
    return usersApi(`/${userId}/permissions`, {
        method: 'POST',
        body: JSON.stringify(permission),
    });
}

export async function revokeUserPermission(userId, permissionId) {
    return usersApi(`/${userId}/permissions/${permissionId}`, {
        method: 'DELETE',
    });
}

// ==================== User Groups ====================

export async function addUserToGroup(userId, groupId) {
    return usersApi(`/${userId}/groups/${groupId}`, {
        method: 'POST',
    });
}

export async function removeUserFromGroup(userId, groupId) {
    return usersApi(`/${userId}/groups/${groupId}`, {
        method: 'DELETE',
    });
}

// ==================== Groups ====================

export async function listGroups() {
    return groupsApi('');
}

export async function getGroup(id) {
    return groupsApi(`/${id}`);
}

export async function createGroup(data) {
    return groupsApi('', {
        method: 'POST',
        body: JSON.stringify(data),
    });
}

export async function updateGroup(id, data) {
    return groupsApi(`/${id}`, {
        method: 'PUT',
        body: JSON.stringify(data),
    });
}

export async function deleteGroup(id) {
    return groupsApi(`/${id}`, {
        method: 'DELETE',
    });
}

// ==================== Group Permissions ====================

export async function getGroupPermissions(groupId) {
    return groupsApi(`/${groupId}/permissions`);
}

export async function grantGroupPermission(groupId, permission) {
    return groupsApi(`/${groupId}/permissions`, {
        method: 'POST',
        body: JSON.stringify(permission),
    });
}

export async function revokeGroupPermission(groupId, permissionId) {
    return groupsApi(`/${groupId}/permissions/${permissionId}`, {
        method: 'DELETE',
    });
}

// ==================== Permission Resources & Actions ====================

export const RESOURCES = {
    DOCKER_HOSTS: 'DOCKER_HOSTS',
    CONTAINERS: 'CONTAINERS',
    IMAGES: 'IMAGES',
    VOLUMES: 'VOLUMES',
    NETWORKS: 'NETWORKS',
    GIT_REPOS: 'GIT_REPOS',
    DEPLOYMENTS: 'DEPLOYMENTS',
    USERS: 'USERS',
    USER_GROUPS: 'USER_GROUPS',
};

export const RESOURCE_ACTIONS = {
    DOCKER_HOSTS: ['list', 'read', 'create', 'delete', 'ping'],
    CONTAINERS: ['list', 'read', 'create', 'delete', 'start', 'stop', 'restart', 'kill', 'pause', 'unpause', 'rename', 'exec', 'logs', 'inspect', 'processes', 'health'],
    IMAGES: ['list', 'read', 'pull', 'delete', 'inspect', 'cleanup'],
    VOLUMES: ['list', 'read', 'create', 'delete'],
    NETWORKS: ['list', 'read', 'create', 'delete', 'connect', 'disconnect', 'inspect'],
    GIT_REPOS: ['list', 'read', 'create', 'update', 'delete', 'deploy', 'poll', 'regenerate_webhook'],
    DEPLOYMENTS: ['list', 'read', 'cancel', 'logs', 'stream_logs'],
    USERS: ['list', 'read', 'create', 'update', 'delete', 'reset_password', 'manage_permissions'],
    USER_GROUPS: ['list', 'read', 'create', 'update', 'delete', 'manage_members', 'manage_permissions'],
};
