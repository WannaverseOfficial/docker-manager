// Status Badge Component

// Get status class from container state
export function getStatusClass(state) {
    const stateMap = {
        running: 'running',
        up: 'running',
        healthy: 'running',
        created: 'created',
        restarting: 'restarting',
        paused: 'paused',
        exited: 'exited',
        stopped: 'stopped',
        dead: 'stopped',
        removing: 'paused',
    };

    const normalized = (state || '').toLowerCase();
    return stateMap[normalized] || 'exited';
}

// Render status badge
export function renderStatusBadge(status, label = null) {
    const statusClass = getStatusClass(status);
    const displayLabel = label || status;

    return `<span class="status-badge ${statusClass}">${displayLabel}</span>`;
}

// Render container status badge
export function renderContainerStatus(container) {
    const state = container.state || container.status || 'unknown';
    return renderStatusBadge(state);
}

// Render job status badge
export function renderJobStatus(status) {
    const statusMap = {
        PENDING: { class: 'created', label: 'Pending' },
        RUNNING: { class: 'running', label: 'Running' },
        SUCCESS: { class: 'running', label: 'Success' },
        FAILED: { class: 'stopped', label: 'Failed' },
        CANCELLED: { class: 'exited', label: 'Cancelled' },
    };

    const info = statusMap[status] || { class: 'exited', label: status };
    return `<span class="status-badge ${info.class}">${info.label}</span>`;
}

// Render boolean status
export function renderBooleanStatus(value, trueLabel = 'Yes', falseLabel = 'No') {
    if (value) {
        return `<span class="status-badge running">${trueLabel}</span>`;
    }
    return `<span class="status-badge exited">${falseLabel}</span>`;
}

// Render connection status
export function renderConnectionStatus(connected) {
    if (connected) {
        return `<span class="status-badge running">Connected</span>`;
    }
    return `<span class="status-badge stopped">Disconnected</span>`;
}

// Render trigger type badges (for git repos)
export function renderTriggerBadges(repo) {
    const badges = [];

    if (repo.webhookEnabled) {
        badges.push('<span class="status-badge running">Webhook</span>');
    }

    if (repo.pollingEnabled) {
        badges.push(`<span class="status-badge paused">Poll ${repo.pollingIntervalSeconds}s</span>`);
    }

    if (badges.length === 0) {
        badges.push('<span class="status-badge exited">None</span>');
    }

    return badges.join(' ');
}

// Render user role badge
export function renderRoleBadge(isAdmin) {
    if (isAdmin) {
        return '<span class="status-badge running">Admin</span>';
    }
    return '<span class="status-badge exited">User</span>';
}

// Render enabled/disabled badge
export function renderEnabledBadge(enabled) {
    if (enabled) {
        return '<span class="status-badge running">Enabled</span>';
    }
    return '<span class="status-badge stopped">Disabled</span>';
}
