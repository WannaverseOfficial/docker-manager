// Confirm Dialog Component

let confirmResolve = null;

// Show confirmation dialog
export function showConfirm(options) {
    const {
        title = 'Confirm',
        message = 'Are you sure?',
        confirmText = 'Confirm',
        cancelText = 'Cancel',
        type = 'info', // 'info', 'warning', 'danger'
        icon = null,
        details = null, // Additional HTML content
    } = options;

    return new Promise((resolve) => {
        confirmResolve = resolve;

        const dialog = document.getElementById('confirm-dialog');
        const titleEl = dialog.querySelector('.confirm-title');
        const contentEl = dialog.querySelector('.confirm-content');
        const cancelBtn = dialog.querySelector('.confirm-cancel');
        const okBtn = dialog.querySelector('.confirm-ok');

        // Set icon based on type
        const iconName = icon || (
            type === 'danger' ? 'warning' :
            type === 'warning' ? 'help' :
            'info'
        );

        // Build content
        let contentHtml = `
            <div style="display: flex; gap: 16px; align-items: flex-start;">
                <span class="material-symbols-outlined" style="font-size: 40px; color: var(--md-sys-color-${type === 'danger' ? 'error' : 'primary'});">
                    ${iconName}
                </span>
                <div>
                    <p style="margin: 0; font-size: 14px;">${escapeHtml(message)}</p>
                    ${details ? `<div style="margin-top: 12px;">${details}</div>` : ''}
                </div>
            </div>
        `;

        titleEl.textContent = title;
        contentEl.innerHTML = contentHtml;
        cancelBtn.textContent = cancelText;
        okBtn.textContent = confirmText;

        // Style confirm button based on type
        if (type === 'danger') {
            okBtn.style.setProperty('--md-filled-button-container-color', 'var(--md-sys-color-error)');
        } else {
            okBtn.style.removeProperty('--md-filled-button-container-color');
        }

        // Remove old listeners by cloning and replacing buttons
        const newCancelBtn = cancelBtn.cloneNode(true);
        const newOkBtn = okBtn.cloneNode(true);
        cancelBtn.parentNode.replaceChild(newCancelBtn, cancelBtn);
        okBtn.parentNode.replaceChild(newOkBtn, okBtn);

        // Add new listeners
        newCancelBtn.addEventListener('click', () => {
            dialog.close();
            if (confirmResolve) {
                confirmResolve(false);
                confirmResolve = null;
            }
        });

        newOkBtn.addEventListener('click', () => {
            dialog.close();
            if (confirmResolve) {
                confirmResolve(true);
                confirmResolve = null;
            }
        });

        dialog.addEventListener('close', () => {
            if (confirmResolve) {
                confirmResolve(false);
                confirmResolve = null;
            }
        }, { once: true });

        dialog.show();
    });
}

// Convenience method for delete confirmations
export function confirmDelete(itemName, details = null) {
    return showConfirm({
        title: 'Delete ' + itemName + '?',
        message: `Are you sure you want to delete this ${itemName.toLowerCase()}? This action cannot be undone.`,
        confirmText: 'Delete',
        type: 'danger',
        icon: 'delete',
        details,
    });
}

// Convenience method for destructive actions
export function confirmAction(title, message, confirmText = 'Confirm') {
    return showConfirm({
        title,
        message,
        confirmText,
        type: 'warning',
    });
}

// Escape HTML
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
