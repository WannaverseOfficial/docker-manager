// Simple Hash-based Router
import { state, setState } from './state.js';

// Page modules (lazy loaded)
const pageModules = {
    dashboard: () => import('./pages/dashboard.js'),
    containers: () => import('./pages/containers.js'),
    templates: () => import('./pages/templates.js'),
    images: () => import('./pages/images.js'),
    volumes: () => import('./pages/volumes.js'),
    networks: () => import('./pages/networks.js'),
    'git-repos': () => import('./pages/git-repos.js'),
    'deployment-history': () => import('./pages/deployment-history.js'),
    hosts: () => import('./pages/hosts.js'),
    'container-health': () => import('./pages/container-health.js'),
    users: () => import('./pages/users.js'),
    'audit-logs': () => import('./pages/audit-logs.js'),
    'notification-preferences': () => import('./pages/notification-preferences.js'),
    'smtp-settings': () => import('./pages/smtp-settings.js'),
    'email-logs': () => import('./pages/email-logs.js'),
    registries: () => import('./pages/registries.js'),
    'image-policies': () => import('./pages/image-policies.js'),
    ingress: () => import('./pages/ingress.js'),
};

// Page titles
const pageTitles = {
    dashboard: 'Dashboard',
    containers: 'Containers',
    templates: 'Templates',
    images: 'Images',
    volumes: 'Volumes',
    networks: 'Networks',
    'git-repos': 'Git Repositories',
    'deployment-history': 'Deployment History',
    hosts: 'Docker Hosts',
    'container-health': 'Container Health',
    users: 'Users & Groups',
    'audit-logs': 'Audit Logs',
    'notification-preferences': 'Notification Preferences',
    'smtp-settings': 'SMTP Settings',
    'email-logs': 'Email Logs',
    registries: 'Docker Registries',
    'image-policies': 'Image Policies',
    ingress: 'Ingress',
};

// Current page module instance
let currentPageInstance = null;

// Initialize router
export function initRouter() {
    window.addEventListener('hashchange', handleRoute);
    handleRoute();
}

// Handle route change
async function handleRoute() {
    const hash = location.hash.slice(1) || 'dashboard';
    const page = pageModules[hash] ? hash : 'dashboard';

    // Update state
    setState('currentPage', page);

    // Update navigation
    updateNavigation(page);

    // Update page title
    updatePageTitle(page);

    // Load and render page
    await loadPage(page);
}

// Navigate to a page
export function navigateTo(page) {
    location.hash = page;
}

// Refresh current page
export async function refreshCurrentPage() {
    await loadPage(state.currentPage);
}

// Load a page module
async function loadPage(page) {
    const content = document.getElementById('content');

    // Show loading
    content.innerHTML = `
        <div class="loading-container">
            <md-circular-progress indeterminate></md-circular-progress>
        </div>
    `;

    try {
        // Cleanup previous page
        if (currentPageInstance?.cleanup) {
            currentPageInstance.cleanup();
        }

        // Load page module
        const moduleLoader = pageModules[page];
        if (!moduleLoader) {
            throw new Error(`Unknown page: ${page}`);
        }

        const module = await moduleLoader();
        currentPageInstance = module;

        // Render page
        if (module.render) {
            content.innerHTML = module.render();
        }

        // Initialize page
        if (module.init) {
            await module.init();
        }

    } catch (error) {
        console.error(`Failed to load page ${page}:`, error);
        content.innerHTML = `
            <div class="empty-state">
                <span class="material-symbols-outlined">error</span>
                <h3>Failed to load page</h3>
                <p>${error.message}</p>
            </div>
        `;
    }
}

// Update navigation active state
function updateNavigation(page) {
    document.querySelectorAll('.nav-item').forEach(item => {
        const itemPage = item.dataset.page;
        if (itemPage === page) {
            item.classList.add('active');
        } else {
            item.classList.remove('active');
        }
    });
}

// Update page title in header
function updatePageTitle(page) {
    const title = pageTitles[page] || 'Docker Manager';
    const titleEl = document.getElementById('page-title');
    if (titleEl) {
        titleEl.textContent = title;
    }
    document.title = `${title} - Docker Manager`;
}

// Get current page
export function getCurrentPage() {
    return state.currentPage;
}
