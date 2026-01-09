// Notification Preferences Page (All Users)
import { getNotificationPreferences, updateNotificationPreferences, getAvailableEvents, getSmtpStatus } from '../api/notifications.js';
import { showToast } from '../components/toast.js';

let preferences = {};
let availableEvents = [];
let smtpConfigured = false;

// Category metadata for display
const CATEGORIES = {
    deployments: {
        icon: 'rocket_launch',
        title: 'Deployments',
        description: 'Notifications about deployment status'
    },
    containers: {
        icon: 'deployed_code',
        title: 'Containers',
        description: 'Notifications about container health and status'
    },
    security: {
        icon: 'security',
        title: 'Security',
        description: 'Notifications about account security'
    },
    system: {
        icon: 'dns',
        title: 'System',
        description: 'Notifications about Docker host status'
    }
};

// Render page
export function render() {
    return `
        <div class="section-header">
            <h2 class="section-title">Notification Preferences</h2>
            <md-filled-button id="save-prefs-btn">
                <span class="material-symbols-outlined" slot="icon">save</span>
                Save Preferences
            </md-filled-button>
        </div>

        <div id="smtp-warning" class="alert alert-warning hidden" style="margin-bottom: 16px;">
            <span class="material-symbols-outlined">warning</span>
            <span>SMTP is not configured. Contact your administrator to enable email notifications.</span>
        </div>

        <div id="preferences-container">
            <div class="loading-container">
                <md-circular-progress indeterminate></md-circular-progress>
            </div>
        </div>
    `;
}

// Initialize
export async function init() {
    document.getElementById('save-prefs-btn')?.addEventListener('click', handleSave);

    await loadData();
}

// Load preferences and events
async function loadData() {
    try {
        const [prefsResponse, events, statusResponse] = await Promise.all([
            getNotificationPreferences(),
            getAvailableEvents(),
            getSmtpStatus().catch(() => ({ configured: false }))
        ]);

        preferences = prefsResponse.preferences || {};
        availableEvents = events || [];
        smtpConfigured = statusResponse.configured;

        // Show warning if SMTP not configured
        const warningEl = document.getElementById('smtp-warning');
        if (warningEl) {
            warningEl.classList.toggle('hidden', smtpConfigured);
        }

        renderPreferences();

    } catch (error) {
        console.error('Failed to load preferences:', error);
        showToast('Failed to load notification preferences', 'error');

        document.getElementById('preferences-container').innerHTML = `
            <div class="empty-state">
                <span class="material-symbols-outlined">error</span>
                <h3>Failed to Load Preferences</h3>
                <p>${error.message || 'An error occurred while loading your preferences.'}</p>
                <md-filled-button onclick="location.reload()">Try Again</md-filled-button>
            </div>
        `;
    }
}

// Render preferences grouped by category
function renderPreferences() {
    const container = document.getElementById('preferences-container');

    // Group events by category
    const grouped = {};
    for (const event of availableEvents) {
        const cat = event.category || 'other';
        if (!grouped[cat]) {
            grouped[cat] = [];
        }
        grouped[cat].push(event);
    }

    // Order categories
    const categoryOrder = ['deployments', 'containers', 'security', 'system'];
    const orderedCategories = categoryOrder.filter(c => grouped[c]);

    let html = '<div class="preferences-grid">';

    for (const category of orderedCategories) {
        const events = grouped[category];
        const meta = CATEGORIES[category] || { icon: 'notifications', title: category, description: '' };

        html += `
            <div class="card preference-card">
                <div class="card-header">
                    <span class="material-symbols-outlined">${meta.icon}</span>
                    <div>
                        <h3>${meta.title}</h3>
                        <p class="text-secondary text-small">${meta.description}</p>
                    </div>
                </div>
                <div class="card-content">
                    <div class="preference-list">
        `;

        for (const event of events) {
            const isEnabled = preferences[event.name] !== false; // Default to true
            html += `
                <label class="preference-item">
                    <div class="preference-info">
                        <span class="preference-name">${event.displayName}</span>
                    </div>
                    <md-switch
                        id="pref-${event.name}"
                        data-event="${event.name}"
                        ${isEnabled ? 'selected' : ''}
                        ${!smtpConfigured ? 'disabled' : ''}
                    ></md-switch>
                </label>
            `;
        }

        html += `
                    </div>
                </div>
            </div>
        `;
    }

    html += '</div>';

    // Add bulk actions
    html += `
        <div class="bulk-actions" style="margin-top: 16px;">
            <md-text-button id="enable-all-btn" ${!smtpConfigured ? 'disabled' : ''}>
                <span class="material-symbols-outlined" slot="icon">check_box</span>
                Enable All
            </md-text-button>
            <md-text-button id="disable-all-btn" ${!smtpConfigured ? 'disabled' : ''}>
                <span class="material-symbols-outlined" slot="icon">check_box_outline_blank</span>
                Disable All
            </md-text-button>
        </div>
    `;

    container.innerHTML = html;

    // Attach bulk action handlers
    document.getElementById('enable-all-btn')?.addEventListener('click', () => toggleAll(true));
    document.getElementById('disable-all-btn')?.addEventListener('click', () => toggleAll(false));
}

// Toggle all preferences
function toggleAll(enabled) {
    const switches = document.querySelectorAll('md-switch[data-event]');
    switches.forEach(sw => {
        sw.selected = enabled;
    });
}

// Save preferences
async function handleSave() {
    const saveBtn = document.getElementById('save-prefs-btn');
    saveBtn.disabled = true;

    try {
        // Collect all preferences from switches
        const newPrefs = {};
        const switches = document.querySelectorAll('md-switch[data-event]');

        switches.forEach(sw => {
            const eventName = sw.dataset.event;
            newPrefs[eventName] = sw.selected;
        });

        const result = await updateNotificationPreferences(newPrefs);
        preferences = result.preferences || newPrefs;

        showToast('Notification preferences saved', 'success');

    } catch (error) {
        showToast(error.message || 'Failed to save preferences', 'error');
    } finally {
        saveBtn.disabled = false;
    }
}

export function cleanup() {
    preferences = {};
    availableEvents = [];
    smtpConfigured = false;
}
