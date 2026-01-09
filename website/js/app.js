// Material Web Components (pre-bundled)
import './lib/material-web.js';

// Apply button padding after Material Web is loaded
// This ensures the CSS custom properties are set after components are defined
function applyButtonStyles() {
    const styleSheet = new CSSStyleSheet();
    styleSheet.replaceSync(`
        md-filled-button {
            --md-filled-button-leading-space: 36px;
            --md-filled-button-trailing-space: 36px;
            --md-filled-button-with-leading-icon-leading-space: 24px;
            --md-filled-button-with-leading-icon-trailing-space: 32px;
            --md-filled-button-with-trailing-icon-leading-space: 32px;
            --md-filled-button-with-trailing-icon-trailing-space: 24px;
        }
        md-outlined-button {
            --md-outlined-button-leading-space: 36px;
            --md-outlined-button-trailing-space: 36px;
            --md-outlined-button-with-leading-icon-leading-space: 24px;
            --md-outlined-button-with-leading-icon-trailing-space: 32px;
            --md-outlined-button-with-trailing-icon-leading-space: 32px;
            --md-outlined-button-with-trailing-icon-trailing-space: 24px;
        }
        md-filled-tonal-button {
            --md-filled-tonal-button-leading-space: 36px;
            --md-filled-tonal-button-trailing-space: 36px;
            --md-filled-tonal-button-with-leading-icon-leading-space: 24px;
            --md-filled-tonal-button-with-leading-icon-trailing-space: 32px;
        }
        md-text-button {
            --md-text-button-leading-space: 24px;
            --md-text-button-trailing-space: 24px;
            --md-text-button-with-leading-icon-leading-space: 20px;
            --md-text-button-with-leading-icon-trailing-space: 24px;
        }
    `);
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, styleSheet];
}
applyButtonStyles();

// App Modules
import { state, loadState, setState } from './state.js';
import { initRouter, navigateTo } from './router.js';
import { isAuthenticated, showLogin, initAuth } from './auth/login.js';
import { initSession } from './auth/session.js';
import { initSidebar } from './components/sidebar.js';
import { initHeader } from './components/header.js';
import { showToast } from './components/toast.js';
import { loadHosts, checkHostConnection } from './api/docker.js';

// Initialize App
async function initApp() {
    console.log('Docker Manager initializing...');

    try {
        // Load saved state from localStorage
        loadState();
        console.log('State loaded:', {
            hasToken: !!state.accessToken,
            hasUser: !!state.currentUser
        });

        // Initialize session (token management)
        initSession();
        console.log('Session initialized');

        // Check authentication
        const authenticated = isAuthenticated();
        console.log('Is authenticated:', authenticated);

        if (!authenticated) {
            console.log('Showing login...');
            showLogin();
            return;
        }

        // User is authenticated
        console.log('Starting app...');
        await startApp();
    } catch (error) {
        console.error('initApp error:', error);
    }
}

// Start the main application
export async function startApp() {
    console.log('startApp() called');
    try {
        // Hide any overlays
        hideAllOverlays();
        console.log('Overlays hidden');

        // Show main app
        document.getElementById('app').classList.remove('hidden');
        console.log('App container shown');

        // Initialize components
        initSidebar();
        console.log('Sidebar initialized');
        initHeader();
        console.log('Header initialized');
        initRouter();
        console.log('Router initialized');

        // Load hosts
        console.log('Loading hosts...');
        const hosts = await loadHosts();
        console.log('Hosts loaded:', hosts);

        if (!hosts || hosts.length === 0) {
            // No hosts, show splash screen
            console.log('No hosts found, showing splash');
            showSplash();
            return;
        }

        // Check for saved host or use first one
        if (!state.currentHostId || !hosts.find(h => h.id === state.currentHostId)) {
            setState('currentHostId', hosts[0].id);
        }
        console.log('Current host ID:', state.currentHostId);

        // Update host selector
        updateHostSelector(hosts);
        console.log('Host selector updated');

        // Check connection status
        checkHostConnection(state.currentHostId);

        // Navigate to initial page
        if (!location.hash) {
            navigateTo('dashboard');
        }
        console.log('startApp() completed successfully');

    } catch (error) {
        console.error('Failed to start app:', error);
        showToast('Failed to initialize application: ' + error.message, 'error');

        // Show error in content area
        const content = document.getElementById('content');
        if (content) {
            content.innerHTML = `
                <div class="empty-state">
                    <span class="material-symbols-outlined">error</span>
                    <h3>Failed to start application</h3>
                    <p>${error.message}</p>
                </div>
            `;
        }
    }
}

// Show splash screen for host selection
function showSplash() {
    document.getElementById('app').classList.add('hidden');
    document.getElementById('splash-overlay').classList.remove('hidden');
    initSplash();
}

// Initialize splash screen
function initSplash() {
    const form = document.getElementById('splash-add-host-form');
    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const urlField = document.getElementById('splash-host-url');
        const url = urlField.value.trim();

        if (!url) return;

        try {
            const { addHost } = await import('./api/docker.js');
            await addHost(url);
            showToast('Host added successfully', 'success');
            hideSplash();
            startApp();
        } catch (error) {
            showToast('Failed to add host: ' + error.message, 'error');
        }
    });
}

function hideSplash() {
    document.getElementById('splash-overlay').classList.add('hidden');
}

// Update host selector dropdown
function updateHostSelector(hosts) {
    const select = document.getElementById('host-select');
    select.innerHTML = hosts.map(host => `
        <md-select-option value="${host.id}" ${host.id === state.currentHostId ? 'selected' : ''}>
            <span slot="headline">${host.dockerHostUrl}</span>
        </md-select-option>
    `).join('');

    select.addEventListener('change', async () => {
        const newHostId = select.value;
        setState('currentHostId', newHostId);
        await checkHostConnection(newHostId);
        // Refresh current page
        const { refreshCurrentPage } = await import('./router.js');
        refreshCurrentPage();
    });
}

// Update connection status display
export function updateConnectionStatus(status) {
    const container = document.getElementById('connection-status');
    const dot = container.querySelector('.status-dot');
    const text = container.querySelector('.status-text');

    dot.className = 'status-dot ' + status;
    text.textContent = status === 'connected' ? 'Connected' :
                       status === 'disconnected' ? 'Disconnected' :
                       status === 'checking' ? 'Checking...' : 'Error';
}

// Hide all overlay screens
function hideAllOverlays() {
    document.getElementById('login-overlay').classList.add('hidden');
    document.getElementById('change-password-overlay').classList.add('hidden');
    document.getElementById('splash-overlay').classList.add('hidden');
}

// Hide loading screen
function hideLoadingScreen() {
    const loadingScreen = document.getElementById('loading-screen');
    if (loadingScreen) {
        loadingScreen.style.display = 'none';
    }
}

// Show error on loading screen
function showLoadingError(message) {
    const loadingScreen = document.getElementById('loading-screen');
    if (loadingScreen) {
        loadingScreen.innerHTML = `
            <div style="font-size:48px;color:#f87171;">&#x26A0;</div>
            <div style="color:#e2e2e6;font-family:system-ui;">${message}</div>
            <div style="color:#9ca3af;font-family:system-ui;font-size:14px;">Check browser console for details</div>
        `;
    }
}

// DOM Ready
document.addEventListener('DOMContentLoaded', async () => {
    try {
        // Wait for custom elements with timeout
        const timeout = new Promise((_, reject) =>
            setTimeout(() => reject(new Error('Timeout loading components')), 10000)
        );

        const componentsReady = Promise.all([
            customElements.whenDefined('md-filled-button'),
            customElements.whenDefined('md-filled-text-field'),
            customElements.whenDefined('md-dialog'),
        ]);

        await Promise.race([componentsReady, timeout]);

        hideLoadingScreen();
        initApp();
    } catch (error) {
        console.error('Failed to load Material Web components:', error);
        showLoadingError('Failed to load UI components. Check your internet connection.');
    }
});

// Export for use in other modules
export { hideAllOverlays };
