// Modal Component
// Uses Material Web's md-dialog component

// Open modal with content
export function openModal(title, content, actions) {
    const dialog = document.getElementById('app-dialog');
    const titleEl = dialog.querySelector('.dialog-title');
    const contentEl = dialog.querySelector('.dialog-content');
    const actionsEl = dialog.querySelector('.dialog-actions');

    titleEl.textContent = title;
    contentEl.innerHTML = content;
    actionsEl.innerHTML = actions;

    dialog.show();
}

// Close modal
export function closeModal() {
    const dialog = document.getElementById('app-dialog');
    dialog.close();
}

// Open modal with form and promise-based result
export function openFormModal(title, content, onSubmit) {
    return new Promise((resolve, reject) => {
        const actions = `
            <md-text-button class="modal-cancel">Cancel</md-text-button>
            <md-filled-button class="modal-submit">Submit</md-filled-button>
        `;

        openModal(title, content, actions);

        const dialog = document.getElementById('app-dialog');
        const cancelBtn = dialog.querySelector('.modal-cancel');
        const submitBtn = dialog.querySelector('.modal-submit');

        cancelBtn.addEventListener('click', () => {
            closeModal();
            resolve(null);
        }, { once: true });

        submitBtn.addEventListener('click', async () => {
            try {
                const result = await onSubmit();
                closeModal();
                resolve(result);
            } catch (error) {
                // Don't close on error, let the handler show the error
                reject(error);
            }
        }, { once: true });

        dialog.addEventListener('close', () => {
            resolve(null);
        }, { once: true });
    });
}

// Quick dialog for displaying information
export function showInfoModal(title, content) {
    const actions = `
        <md-filled-button onclick="document.getElementById('app-dialog').close()">OK</md-filled-button>
    `;
    openModal(title, content, actions);
}

// Show loading in modal
export function showLoadingModal(title = 'Loading...') {
    const content = `
        <div class="loading-container">
            <md-circular-progress indeterminate></md-circular-progress>
        </div>
    `;
    openModal(title, content, '');
}
