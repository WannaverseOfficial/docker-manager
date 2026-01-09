// Toast Notification Component

const MAX_TOASTS = 5;
const DEFAULT_DURATION = 4000;

// Show a toast notification
export function showToast(message, type = 'info', duration = DEFAULT_DURATION) {
    const container = document.getElementById('toast-container');

    // Remove excess toasts
    while (container.children.length >= MAX_TOASTS) {
        container.removeChild(container.firstChild);
    }

    // Create toast element
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;

    // Add icon based on type
    const icon = type === 'success' ? 'check_circle' :
                 type === 'error' ? 'error' :
                 'info';

    toast.innerHTML = `
        <span class="material-symbols-outlined">${icon}</span>
        <span class="toast-message">${escapeHtml(message)}</span>
    `;

    container.appendChild(toast);

    // Auto-remove after duration
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(20px)';
        setTimeout(() => {
            if (toast.parentNode === container) {
                container.removeChild(toast);
            }
        }, 300);
    }, duration);

    // Click to dismiss
    toast.addEventListener('click', () => {
        if (toast.parentNode === container) {
            container.removeChild(toast);
        }
    });
}

// Success toast
export function showSuccess(message) {
    showToast(message, 'success');
}

// Error toast
export function showError(message) {
    showToast(message, 'error');
}

// Info toast
export function showInfo(message) {
    showToast(message, 'info');
}

// Escape HTML to prevent XSS
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
