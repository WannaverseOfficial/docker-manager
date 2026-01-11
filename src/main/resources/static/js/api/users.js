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

export const RESOURCE_CATEGORIES = {
    'Docker': ['DOCKER_HOSTS', 'CONTAINERS', 'IMAGES', 'VOLUMES', 'NETWORKS'],
    'Git & Deployments': ['GIT_REPOS', 'DEPLOYMENTS', 'COMPOSE_DEPLOYMENTS', 'ROLLBACK'],
    'Ingress': ['INGRESS', 'INGRESS_ROUTES', 'INGRESS_CERTIFICATES'],
    'Pipelines': ['PIPELINES', 'PIPELINE_EXECUTIONS', 'DEPLOYMENT_ENVIRONMENTS'],
    'Administration': ['USERS', 'USER_GROUPS', 'AUDIT_LOGS', 'NOTIFICATIONS'],
    'Other': ['DOCKER_OPERATIONS', 'STATE_SNAPSHOTS', 'IMAGE_POLICIES', 'REGISTRIES', 'TEMPLATES'],
};

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
    AUDIT_LOGS: 'AUDIT_LOGS',
    NOTIFICATIONS: 'NOTIFICATIONS',
    DOCKER_OPERATIONS: 'DOCKER_OPERATIONS',
    STATE_SNAPSHOTS: 'STATE_SNAPSHOTS',
    COMPOSE_DEPLOYMENTS: 'COMPOSE_DEPLOYMENTS',
    ROLLBACK: 'ROLLBACK',
    IMAGE_POLICIES: 'IMAGE_POLICIES',
    REGISTRIES: 'REGISTRIES',
    INGRESS: 'INGRESS',
    INGRESS_ROUTES: 'INGRESS_ROUTES',
    INGRESS_CERTIFICATES: 'INGRESS_CERTIFICATES',
    TEMPLATES: 'TEMPLATES',
    PIPELINES: 'PIPELINES',
    PIPELINE_EXECUTIONS: 'PIPELINE_EXECUTIONS',
    DEPLOYMENT_ENVIRONMENTS: 'DEPLOYMENT_ENVIRONMENTS',
};

export const RESOURCE_LABELS = {
    DOCKER_HOSTS: 'Docker Hosts',
    CONTAINERS: 'Containers',
    IMAGES: 'Images',
    VOLUMES: 'Volumes',
    NETWORKS: 'Networks',
    GIT_REPOS: 'Git Repositories',
    DEPLOYMENTS: 'Deployments',
    USERS: 'Users',
    USER_GROUPS: 'User Groups',
    AUDIT_LOGS: 'Audit Logs',
    NOTIFICATIONS: 'Notifications',
    DOCKER_OPERATIONS: 'Docker Operations',
    STATE_SNAPSHOTS: 'State Snapshots',
    COMPOSE_DEPLOYMENTS: 'Compose Deployments',
    ROLLBACK: 'Rollback',
    IMAGE_POLICIES: 'Image Policies',
    REGISTRIES: 'Registries',
    INGRESS: 'Ingress',
    INGRESS_ROUTES: 'Ingress Routes',
    INGRESS_CERTIFICATES: 'Ingress Certificates',
    TEMPLATES: 'Templates',
    PIPELINES: 'Pipelines',
    PIPELINE_EXECUTIONS: 'Pipeline Executions',
    DEPLOYMENT_ENVIRONMENTS: 'Deployment Environments',
};

export const RESOURCE_ACTIONS = {
    DOCKER_HOSTS: ['list', 'read', 'create', 'update', 'delete', 'ping'],
    CONTAINERS: ['list', 'read', 'create', 'delete', 'start', 'stop', 'restart', 'kill', 'pause', 'unpause', 'rename', 'exec', 'logs', 'inspect', 'processes', 'health'],
    IMAGES: ['list', 'read', 'pull', 'delete', 'inspect', 'cleanup'],
    VOLUMES: ['list', 'read', 'create', 'delete', 'inspect'],
    NETWORKS: ['list', 'read', 'create', 'delete', 'connect', 'disconnect', 'inspect'],
    GIT_REPOS: ['list', 'read', 'create', 'update', 'delete', 'deploy', 'poll', 'regenerate_webhook'],
    DEPLOYMENTS: ['list', 'read', 'cancel', 'logs', 'stream_logs'],
    USERS: ['list', 'read', 'create', 'update', 'delete', 'reset_password', 'manage_permissions'],
    USER_GROUPS: ['list', 'read', 'create', 'update', 'delete', 'manage_members', 'manage_permissions'],
    AUDIT_LOGS: ['list', 'read', 'export'],
    NOTIFICATIONS: ['list', 'read', 'create', 'update', 'delete', 'test'],
    DOCKER_OPERATIONS: ['prune', 'system_info'],
    STATE_SNAPSHOTS: ['list', 'read', 'create', 'delete', 'restore'],
    COMPOSE_DEPLOYMENTS: ['list', 'read', 'create', 'update', 'delete', 'start', 'stop'],
    ROLLBACK: ['execute', 'list', 'read'],
    IMAGE_POLICIES: ['list', 'read', 'create', 'update', 'delete'],
    REGISTRIES: ['list', 'read', 'create', 'update', 'delete', 'test'],
    INGRESS: ['read', 'enable', 'disable', 'reload', 'configure'],
    INGRESS_ROUTES: ['list', 'read', 'create', 'update', 'delete', 'preview'],
    INGRESS_CERTIFICATES: ['list', 'read', 'create', 'delete', 'upload', 'request', 'renew'],
    TEMPLATES: ['list', 'read', 'create', 'update', 'delete', 'deploy'],
    PIPELINES: ['list', 'read', 'create', 'update', 'delete', 'trigger', 'duplicate'],
    PIPELINE_EXECUTIONS: ['list', 'read', 'cancel', 'logs', 'stream_logs', 'artifacts'],
    DEPLOYMENT_ENVIRONMENTS: ['list', 'read', 'create', 'update', 'delete', 'deploy', 'switch', 'rollback'],
};
