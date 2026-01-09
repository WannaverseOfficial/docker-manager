// Formatting Utilities

// Format bytes to human readable
export function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    if (!bytes) return 'N/A';

    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));

    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// Format timestamp to locale string
export function formatDate(timestamp) {
    if (!timestamp) return 'N/A';

    const date = new Date(timestamp);
    return date.toLocaleString();
}

// Format timestamp to relative time
export function formatRelativeTime(timestamp) {
    if (!timestamp) return 'N/A';

    const now = Date.now();
    const diff = now - timestamp;

    const seconds = Math.floor(diff / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);

    if (days > 0) return `${days} day${days > 1 ? 's' : ''} ago`;
    if (hours > 0) return `${hours} hour${hours > 1 ? 's' : ''} ago`;
    if (minutes > 0) return `${minutes} minute${minutes > 1 ? 's' : ''} ago`;
    return 'Just now';
}

// Truncate string with ellipsis
export function truncate(str, maxLength = 50) {
    if (!str) return '';
    if (str.length <= maxLength) return str;
    return str.substring(0, maxLength) + '...';
}

// Truncate from middle (useful for IDs)
export function truncateMiddle(str, maxLength = 20) {
    if (!str) return '';
    if (str.length <= maxLength) return str;

    const charsToShow = maxLength - 3;
    const frontChars = Math.ceil(charsToShow / 2);
    const backChars = Math.floor(charsToShow / 2);

    return str.substring(0, frontChars) + '...' + str.substring(str.length - backChars);
}

// Format container ID (first 12 chars)
export function formatContainerId(id) {
    if (!id) return 'N/A';
    return id.substring(0, 12);
}

// Format image name
export function formatImageName(image) {
    if (!image) return 'N/A';
    // Remove sha256: prefix if present
    if (image.startsWith('sha256:')) {
        return image.substring(7, 19);
    }
    return image;
}

// Escape HTML to prevent XSS
export function escapeHtml(text) {
    if (text == null) return '';
    const div = document.createElement('div');
    div.textContent = String(text);
    return div.innerHTML;
}

// Format duration in seconds
export function formatDuration(seconds) {
    if (!seconds || seconds < 0) return 'N/A';

    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);

    const parts = [];
    if (hours > 0) parts.push(`${hours}h`);
    if (minutes > 0) parts.push(`${minutes}m`);
    if (secs > 0 || parts.length === 0) parts.push(`${secs}s`);

    return parts.join(' ');
}

// Format number with commas
export function formatNumber(num) {
    if (num == null) return 'N/A';
    return num.toLocaleString();
}

// Capitalize first letter
export function capitalize(str) {
    if (!str) return '';
    return str.charAt(0).toUpperCase() + str.slice(1).toLowerCase();
}

// Format port bindings
export function formatPorts(ports) {
    if (!ports || ports.length === 0) return 'None';

    return ports.map(p => {
        // Handle both PascalCase (from Docker API) and camelCase
        const publicPort = p.publicPort ?? p.PublicPort;
        const privatePort = p.privatePort ?? p.PrivatePort;
        const type = p.type ?? p.Type ?? 'tcp';

        if (publicPort) {
            return `${publicPort}:${privatePort}/${type}`;
        }
        return `${privatePort}/${type}`;
    }).join(', ');
}

// Convert PascalCase keys to camelCase recursively
export function normalizeKeys(obj) {
    if (Array.isArray(obj)) {
        return obj.map(normalizeKeys);
    }

    if (obj !== null && typeof obj === 'object') {
        return Object.keys(obj).reduce((acc, key) => {
            // Convert first character to lowercase (PascalCase -> camelCase)
            const newKey = key.charAt(0).toLowerCase() + key.slice(1);
            acc[newKey] = normalizeKeys(obj[key]);
            return acc;
        }, {});
    }

    return obj;
}
