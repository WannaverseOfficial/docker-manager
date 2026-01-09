// Session Management Module
import { state, clearState, onStateChange } from '../state.js';
import { logout as apiLogout } from '../api/auth.js';
import { showLogin } from './login.js';
import { showToast } from '../components/toast.js';

// Initialize session management
export function initSession() {
    // Listen for auth token changes
    onStateChange('accessToken', (newValue) => {
        if (!newValue) {
            // Token cleared, redirect to login
            showLogin();
        }
    });

    // Handle visibility change (tab focus)
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') {
            checkSession();
        }
    });

    // Periodic session check (every 5 minutes)
    setInterval(checkSession, 5 * 60 * 1000);
}

// Check if session is still valid
async function checkSession() {
    if (!state.accessToken) {
        return;
    }

    // Token validation is handled by API calls
    // This is just a basic check
}

// Logout user
export async function logoutUser() {
    try {
        await apiLogout();
    } catch (error) {
        console.error('Logout error:', error);
    }

    clearState();
    showLogin();
    showToast('You have been logged out', 'info');
}

// Handle session expiry
export function handleSessionExpiry() {
    clearState();
    showLogin();
    showToast('Your session has expired. Please log in again.', 'error');
}

// Get current user
export function getCurrentUserInfo() {
    return state.currentUser;
}

// Check if current user is admin
export function isCurrentUserAdmin() {
    return state.currentUser?.admin === true;
}
