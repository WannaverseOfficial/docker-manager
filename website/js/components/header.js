// Header Component
import { state } from '../state.js';
import { logoutUser } from '../auth/session.js';
import { showChangePasswordDialog } from '../auth/change-password.js';
import { refreshCurrentPage } from '../router.js';

// Initialize header
export function initHeader() {
    setupUserMenu();
    setupRefreshButton();
    updateUserDisplay();
}

// Setup user menu
function setupUserMenu() {
    const menuBtn = document.getElementById('user-menu-btn');
    const menu = document.getElementById('user-menu');

    if (menuBtn && menu) {
        menuBtn.addEventListener('click', () => {
            menu.open = !menu.open;
        });

        // Close menu when clicking outside
        document.addEventListener('click', (e) => {
            if (!menuBtn.contains(e.target) && !menu.contains(e.target)) {
                menu.open = false;
            }
        });
    }

    // Menu actions
    const changePasswordItem = document.getElementById('menu-change-password');
    const logoutItem = document.getElementById('menu-logout');

    if (changePasswordItem) {
        changePasswordItem.addEventListener('click', () => {
            menu.open = false;
            showChangePasswordDialog();
        });
    }

    if (logoutItem) {
        logoutItem.addEventListener('click', () => {
            menu.open = false;
            logoutUser();
        });
    }
}

// Setup refresh button
function setupRefreshButton() {
    const refreshBtn = document.getElementById('refresh-btn');

    if (refreshBtn) {
        refreshBtn.addEventListener('click', async () => {
            // Add spinning animation
            const icon = refreshBtn.querySelector('.material-symbols-outlined');
            icon.style.animation = 'spin 1s linear infinite';

            try {
                await refreshCurrentPage();
            } finally {
                // Remove spinning animation
                setTimeout(() => {
                    icon.style.animation = '';
                }, 500);
            }
        });
    }
}

// Update user display in menu
export function updateUserDisplay() {
    const usernameItem = document.getElementById('menu-username');

    if (usernameItem && state.currentUser) {
        const adminBadge = state.currentUser.admin ? ' (Admin)' : '';
        usernameItem.querySelector('[slot="headline"]').textContent =
            state.currentUser.username + adminBadge;
    }
}

// Update page title
export function setPageTitle(title) {
    const titleEl = document.getElementById('page-title');
    if (titleEl) {
        titleEl.textContent = title;
    }
    document.title = `${title} - Docker Manager`;
}
