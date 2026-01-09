// Login Module
import { state } from '../state.js';
import { login as apiLogin } from '../api/auth.js';
import { showToast } from '../components/toast.js';
import { startApp } from '../app.js';
import { showChangePassword } from './change-password.js';

// Check if user is authenticated
export function isAuthenticated() {
    return state.accessToken !== null && state.currentUser !== null;
}

// Show login overlay
export function showLogin() {
    document.getElementById('login-overlay').classList.remove('hidden');
    document.getElementById('app').classList.add('hidden');
    initLoginForm();
}

// Hide login overlay
export function hideLogin() {
    document.getElementById('login-overlay').classList.add('hidden');
}

// Initialize login form
function initLoginForm() {
    const form = document.getElementById('login-form');
    const errorDiv = document.getElementById('login-error');

    // Clear previous state
    document.getElementById('login-username').value = '';
    document.getElementById('login-password').value = '';
    errorDiv.classList.add('hidden');
    errorDiv.textContent = '';

    // Remove old listeners
    const newForm = form.cloneNode(true);
    form.parentNode.replaceChild(newForm, form);

    // Add submit handler
    newForm.addEventListener('submit', handleLoginSubmit);
}

// Handle login form submission
async function handleLoginSubmit(e) {
    e.preventDefault();
    console.log('Login form submitted');

    const usernameField = document.getElementById('login-username');
    const passwordField = document.getElementById('login-password');

    console.log('Username field:', usernameField);
    console.log('Password field:', passwordField);

    const username = usernameField?.value?.trim();
    const password = passwordField?.value;

    console.log('Username:', username, 'Password length:', password?.length);

    const errorDiv = document.getElementById('login-error');
    const submitBtn = e.target.querySelector('md-filled-button');

    if (!username || !password) {
        showError('Please enter username and password');
        return;
    }

    console.log('Validation passed, disabling button...');
    console.log('Submit button:', submitBtn);

    // Disable button
    if (submitBtn) {
        submitBtn.disabled = true;
    }

    try {
        console.log('Calling apiLogin...');
        const response = await apiLogin(username, password);
        console.log('Login response:', response);

        hideLogin();

        // Check if password change is required
        if (response.user.mustChangePassword) {
            showChangePassword();
        } else {
            await startApp();
            showToast('Welcome back, ' + response.user.username, 'success');
        }

    } catch (error) {
        console.error('Login error:', error);
        showError(error.message || 'Login failed');
    } finally {
        if (submitBtn) {
            submitBtn.disabled = false;
        }
    }
}

// Show error message
function showError(message) {
    const errorDiv = document.getElementById('login-error');
    errorDiv.textContent = message;
    errorDiv.classList.remove('hidden');
}

// Initialize auth (called on app start)
export function initAuth() {
    // Nothing needed for now
}
