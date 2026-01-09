// Change Password Module
import { changePassword as apiChangePassword } from '../api/auth.js';
import { showToast } from '../components/toast.js';
import { startApp } from '../app.js';

// Show change password overlay
export function showChangePassword() {
    document.getElementById('change-password-overlay').classList.remove('hidden');
    document.getElementById('login-overlay').classList.add('hidden');
    document.getElementById('app').classList.add('hidden');
    initChangePasswordForm();
}

// Hide change password overlay
export function hideChangePassword() {
    document.getElementById('change-password-overlay').classList.add('hidden');
}

// Initialize change password form
function initChangePasswordForm() {
    const form = document.getElementById('change-password-form');
    const errorDiv = document.getElementById('password-error');

    // Clear previous state
    document.getElementById('current-password').value = '';
    document.getElementById('new-password').value = '';
    document.getElementById('confirm-password').value = '';
    errorDiv.classList.add('hidden');
    errorDiv.textContent = '';

    // Remove old listeners
    const newForm = form.cloneNode(true);
    form.parentNode.replaceChild(newForm, form);

    // Add submit handler
    newForm.addEventListener('submit', handleChangePasswordSubmit);
}

// Handle change password form submission
async function handleChangePasswordSubmit(e) {
    e.preventDefault();

    const currentPassword = document.getElementById('current-password').value;
    const newPassword = document.getElementById('new-password').value;
    const confirmPassword = document.getElementById('confirm-password').value;
    const errorDiv = document.getElementById('password-error');
    const submitBtn = e.target.querySelector('md-filled-button');

    // Validation
    if (!currentPassword || !newPassword || !confirmPassword) {
        showError('Please fill in all fields');
        return;
    }

    if (newPassword.length < 8) {
        showError('New password must be at least 8 characters');
        return;
    }

    if (newPassword !== confirmPassword) {
        showError('New passwords do not match');
        return;
    }

    // Disable button
    submitBtn.disabled = true;
    submitBtn.textContent = 'Changing...';

    try {
        await apiChangePassword(currentPassword, newPassword);

        hideChangePassword();
        await startApp();
        showToast('Password changed successfully', 'success');

    } catch (error) {
        showError(error.message || 'Failed to change password');
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = 'Change Password';
    }
}

// Show error message
function showError(message) {
    const errorDiv = document.getElementById('password-error');
    errorDiv.textContent = message;
    errorDiv.classList.remove('hidden');
}

// Show change password dialog (for voluntary password change)
export function showChangePasswordDialog() {
    // Use the modal component for in-app password change
    import('../components/modal.js').then(({ openModal, closeModal }) => {
        const content = `
            <form id="dialog-change-password-form" class="dialog-form">
                <md-filled-text-field
                    id="dialog-current-password"
                    label="Current Password"
                    type="password"
                    required>
                </md-filled-text-field>
                <md-filled-text-field
                    id="dialog-new-password"
                    label="New Password"
                    type="password"
                    required>
                </md-filled-text-field>
                <md-filled-text-field
                    id="dialog-confirm-password"
                    label="Confirm New Password"
                    type="password"
                    required>
                </md-filled-text-field>
                <div id="dialog-password-error" class="error-message hidden"></div>
            </form>
        `;

        const actions = `
            <md-text-button onclick="document.getElementById('app-dialog').close()">Cancel</md-text-button>
            <md-filled-button id="dialog-change-password-btn">Change Password</md-filled-button>
        `;

        openModal('Change Password', content, actions);

        // Add submit handler
        setTimeout(() => {
            const btn = document.getElementById('dialog-change-password-btn');
            btn.addEventListener('click', handleDialogChangePassword);
        }, 100);
    });
}

// Handle dialog change password
async function handleDialogChangePassword() {
    const currentPassword = document.getElementById('dialog-current-password').value;
    const newPassword = document.getElementById('dialog-new-password').value;
    const confirmPassword = document.getElementById('dialog-confirm-password').value;
    const errorDiv = document.getElementById('dialog-password-error');
    const btn = document.getElementById('dialog-change-password-btn');

    // Clear previous errors
    errorDiv.classList.add('hidden');

    // Validation
    if (!currentPassword || !newPassword || !confirmPassword) {
        errorDiv.textContent = 'Please fill in all fields';
        errorDiv.classList.remove('hidden');
        return;
    }

    if (newPassword.length < 8) {
        errorDiv.textContent = 'New password must be at least 8 characters';
        errorDiv.classList.remove('hidden');
        return;
    }

    if (newPassword !== confirmPassword) {
        errorDiv.textContent = 'New passwords do not match';
        errorDiv.classList.remove('hidden');
        return;
    }

    btn.disabled = true;

    try {
        await apiChangePassword(currentPassword, newPassword);

        document.getElementById('app-dialog').close();
        showToast('Password changed successfully', 'success');

    } catch (error) {
        errorDiv.textContent = error.message || 'Failed to change password';
        errorDiv.classList.remove('hidden');
    } finally {
        btn.disabled = false;
    }
}
